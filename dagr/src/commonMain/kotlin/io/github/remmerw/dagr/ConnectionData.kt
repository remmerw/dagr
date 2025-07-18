package io.github.remmerw.dagr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

abstract class ConnectionData() :
    ConnectionFlow() {

    private val streamFlowControl = StreamFlowControl(
        this as Connection, Settings.INITIAL_MAX_STREAM_DATA
    )
    private val receiverMaxDataIncrement: Long

    private val frames: MutableList<FrameReceived.DataFrame> = mutableListOf() // no concurrency
    private val readingBuffer = Buffer()

    private val sendingBuffer = Buffer()
    private val mutex = Mutex()

    @OptIn(ExperimentalAtomicApi::class)
    private val reset = AtomicBoolean(false)

    private val sendRequestQueue = sendRequestQueue(Level.APP)
    private val requestFinish = Semaphore(1, 1)

    // meaning that no more bytes can be written by caller.
    @Volatile
    private var isSendingFinal = false

    // Current offset is the offset of the next byte in the stream that will be sent.
    // Thread safety: only used by sender thread, no concurrency
    private var sendingOffset: Long = 0 // no concurrency

    @Volatile
    private var allDataReceived = false
    private var receiverFlowControlLimit =
        initialMaxStreamDataBidiRemote // no concurrency
    private var lastCommunicatedMaxData: Long // no concurrency
    private var processedToOffset: Long = 0 // no concurrency

    // Stream offset at which the stream was last blocked, for detecting the first time
    // stream is blocked at a certain offset.
    private var sendingBlockedOffset: Long = 0 // no concurrency


    init {
        this.lastCommunicatedMaxData = receiverFlowControlLimit
        this.receiverMaxDataIncrement = (receiverFlowControlLimit *
                Settings.RECEIVER_MAX_DATA_INCREMENT_FACTOR).toLong()
    }


    private suspend fun broadcast() {
        var bytesRead = 0


        val removes: MutableList<FrameReceived.DataFrame> = mutableListOf()

        val iterator = frames.sorted().iterator()
        var isFinal = false
        while (iterator.hasNext()) {
            val frame = iterator.next()

            if (frame.offset <= processedToOffset) {
                val upToOffset = frame.offsetLength()
                if (upToOffset >= processedToOffset) {
                    bytesRead += frame.length

                    readingBuffer.write(frame.bytes)

                    processedToOffset = frame.offsetLength()

                    if (frame.isFinal) {
                        isFinal = true
                    }

                    removes.add(frame)
                }
            } else {
                break
            }
        }
        frames.removeAll(removes)

        if (bytesRead > 0) {
            updateAllowedFlowControl(bytesRead)
        }


        if (frames.isEmpty()) {
            allDataReceived = isFinal
            if (allDataReceived) {
                if (responder() == null) {
                    requestFinish.release()
                } else {
                    responder()!!.data(this as Connection, readingBuffer.readByteArray())
                    resetReading()
                }
            }
        }
    }

    fun resetReading() {
        frames.clear()
        processedToOffset = 0
        allDataReceived = false
        readingBuffer.clear()
    }

    fun resetSending() {
        sendingBlockedOffset = 0
        sendingOffset = 0
        isSendingFinal = false
        sendingBuffer.clear()
    }

    private suspend fun updateAllowedFlowControl(bytesRead: Int) {
        // Slide flow control window forward (with as much bytes as are read)
        receiverFlowControlLimit += bytesRead.toLong()
        (this as Connection).updateConnectionFlowControl(bytesRead)
        // Avoid sending flow control updates with every single read; check diff with last
        // send max data
        if (receiverFlowControlLimit - lastCommunicatedMaxData > receiverMaxDataIncrement) {

            lastCommunicatedMaxData = receiverFlowControlLimit
        }
    }


    open suspend fun close() {
        terminate()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal open suspend fun terminate() {
        reset.compareAndSet(expectedValue = false, newValue = true)
        sendingBuffer.clear()
        streamFlowControl.unregister()
        readingBuffer.clear()

        try {
            requestFinish.release()
        } catch (_: Throwable) {
        }


    }


    suspend fun write(buffer: Buffer, autoFlush: Boolean = true) {
        this.isSendingFinal = autoFlush

        mutex.withLock {
            sendingBuffer.write(buffer, buffer.size)
        }

        sendRequestQueue.appendRequest(object : FrameSupplier {
            override suspend fun nextFrame(maxSize: Int): Frame? {
                return sendFrame(maxSize)
            }
        }, Settings.MIN_FRAME_SIZE)
    }


    // this is a blocking request with the given timeout [fin is written, no more writing data allowed]
    suspend fun request(timeout: Long, data: Buffer): ByteArray {
        write(data)

        try {
            return withTimeout(timeout * 1000L) {
                requestFinish.acquire()
                if (!allDataReceived) {
                    byteArrayOf()
                } else {
                    response().readByteArray()
                }
            }
        } catch (throwable: Throwable) {
            debug(throwable)
            close()
            return byteArrayOf()
        } finally {
            resetReading()
        }
    }

    private fun response(): Buffer {
        return readingBuffer
    }


    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun sendFrame(maxFrameSize: Int): Frame? {
        if (reset.load()) {
            return null
        }
        mutex.withLock {
            if (!sendingBuffer.exhausted()) {
                val flowControlLimit: Long = streamFlowControl.flowControlLimit

                if (flowControlLimit < 0) {
                    return null
                }

                var maxBytesToSend = sendingBuffer.size.toInt()

                if (flowControlLimit > sendingOffset || maxBytesToSend == 0) {
                    val dummyFrameLength = frameLength(sendingOffset, 0)

                    maxBytesToSend = min(
                        maxBytesToSend,
                        maxFrameSize - dummyFrameLength - 1
                    ) // Take one byte extra for length field var int
                    val maxAllowedByFlowControl = (streamFlowControl.increaseFlowControlLimit(
                        sendingOffset + maxBytesToSend
                    ) - sendingOffset).toInt()
                    if (maxAllowedByFlowControl < 0) {
                        return null
                    }
                    maxBytesToSend = min(maxAllowedByFlowControl, maxBytesToSend)

                    val dataToSend = sendingBuffer.readByteArray(maxBytesToSend)
                    var finalFrame = false

                    if (sendingBuffer.exhausted()) {
                        finalFrame = this.isSendingFinal
                    }

                    val dataFrame = createDataFrame(
                        sendingOffset, dataToSend, finalFrame
                    )

                    sendingOffset += maxBytesToSend


                    if (!sendingBuffer.exhausted()) {
                        sendRequestQueue.appendRequest(object : FrameSupplier {
                            override suspend fun nextFrame(maxSize: Int): Frame? {
                                return sendFrame(maxSize)
                            }
                        }, Settings.MIN_FRAME_SIZE)
                    }

                    if (finalFrame) {
                        // Done! Retransmissions may follow, but don't need flow control.
                        resetSending()
                        streamFlowControl.unregister()
                    }

                    return dataFrame
                } else {
                    // So flowControlLimit <= currentOffset
                    // Check if this condition hasn't been handled before
                    if (sendingOffset != sendingBlockedOffset) {
                        // Not handled before, remember this offset, so this isn't executed twice for the same offset
                        sendingBlockedOffset = sendingOffset
                        // And let peer know
                        // https://www.rfc-editor.org/rfc/rfc9000.html#name-data-flow-control
                        // "A sender SHOULD send a STREAM_DATA_BLOCKED or DATA_BLOCKED frame to indicate to the receiver
                        //  that it has data to write but is blocked by flow control limits."
                        val frame = sendBlockReason()
                        if (frame != null) {
                            sendRequestQueue.appendRequest(frame)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Sends StreamDataBlockedFrame or DataBlockedFrame to the peer, provided the blocked condition is still true.
     */
    private fun sendBlockReason(): Frame? {
        // Retrieve actual block reason; could be "none" when an update has been received in the meantime.
        val blockReason: BlockReason = streamFlowControl.flowControlBlockReason
        return when (blockReason) {


            BlockReason.DATA_BLOCKED -> createDataBlockedFrame(streamFlowControl.maxDataAllowed())
            else -> null
        }
    }


    /**
     * Add a stream frame to this stream. The frame can contain any number of bytes positioned anywhere in the stream;
     * the read method will take care of returning stream bytes in the right order, without gaps.
     *
     * @return true if the frame is adds bytes to this stream; false if the frame does not add bytes to the stream
     * (because the frame is a duplicate or its stream bytes where already received with previous frames).
     */
    private fun addFrame(frame: FrameReceived.DataFrame): Boolean {
        if (frame.offset >= processedToOffset) {
            return frames.add(frame)
        } else {
            debug("Frame not added $frame")
            return false
        }
    }


    private class StreamFlowControl(
        private val connection: Connection, // The maximum amount of data that a stream would be allowed to send (to the peer), ignoring possible connection limit
        @field:Volatile private var maxStreamDataAllowed: Long
    ) {
        // The maximum amount of data that is already assigned to a stream (i.e. already sent, or upon being sent)
        @Volatile
        private var maxStreamDataAssigned = 0L


        val flowControlLimit: Long
            /**
             * Returns the maximum flow control limit for the given stream, if it was requested now. Note that this limit
             * cannot be used to send data on the stream, as the flow control credits are not yet reserved.
             */
            get() {
                val currentMasStreamDataAssigned = maxStreamDataAssigned
                val currentMaxStreamDataAllowed = maxStreamDataAllowed
                if (currentMasStreamDataAssigned != Settings.UNREGISTER &&
                    currentMaxStreamDataAllowed != Settings.UNREGISTER
                ) {
                    return currentMasStreamDataAssigned + currentStreamCredits(
                        currentMasStreamDataAssigned, currentMaxStreamDataAllowed
                    )
                }
                return Settings.UNREGISTER
            }


        /**
         * Returns the maximum possible flow control limit for the given stream, taking into account both stream and connection
         * flow control limits. Note that the returned limit is not yet reserved for use by this stream!
         */
        fun currentStreamCredits(maxStreamDataAssigned: Long, maxStreamDataAllowed: Long): Long {
            var maxStreamIncrement = maxStreamDataAllowed - maxStreamDataAssigned
            val maxPossibleDataIncrement =
                connection.maxDataAllowed() - connection.maxDataAssigned()
            if (maxStreamIncrement > maxPossibleDataIncrement) {
                maxStreamIncrement = maxPossibleDataIncrement
            }
            return maxStreamIncrement
        }

        /**
         * Request to increase the flow control limit for the indicated stream to the indicated value. Whether this is
         * possible depends on whether the stream flow control limit allows this and whether the connection flow control
         * limit has enough "unused" credits.
         *
         * @return the new flow control limit for the stream: the offset of the last byte sent on the stream may not past this limit.
         */
        fun increaseFlowControlLimit(requestedLimit: Long): Long {
            val currentMasStreamDataAssigned = maxStreamDataAssigned
            val currentMaxStreamDataAllowed = maxStreamDataAllowed

            if (currentMasStreamDataAssigned != Settings.UNREGISTER &&
                currentMaxStreamDataAllowed != Settings.UNREGISTER
            ) {
                val possibleStreamIncrement = currentStreamCredits(
                    currentMasStreamDataAssigned, currentMaxStreamDataAllowed
                )
                val requestedIncrement = requestedLimit - currentMasStreamDataAssigned
                val proposedStreamIncrement =
                    min(requestedIncrement, possibleStreamIncrement)

                require(requestedIncrement >= 0)

                connection.addMaxDataAssigned(proposedStreamIncrement)
                maxStreamDataAssigned = currentMasStreamDataAssigned + proposedStreamIncrement

                return maxStreamDataAssigned
            }
            return Settings.UNREGISTER
        }

        fun unregister() {
            maxStreamDataAllowed = Settings.UNREGISTER
            maxStreamDataAssigned = Settings.UNREGISTER
        }

        /**
         * Returns the current connection flow control limit (maxDataAllowed).
         *
         * @return current connection flow control limit (maxDataAllowed)
         */
        fun maxDataAllowed(): Long {
            return connection.maxDataAllowed()
        }


        val flowControlBlockReason: BlockReason
            /**
             * Returns the reason why a given stream is blocked, which can be due that the stream flow control limit is reached
             * or the connection data limit is reached.
             */
            get() {
                if (maxStreamDataAssigned == maxStreamDataAllowed) {
                    return BlockReason.STREAM_DATA_BLOCKED
                }
                if (connection.maxDataAllowed() == connection.maxDataAssigned()) {
                    return BlockReason.DATA_BLOCKED
                }

                return BlockReason.NOT_BLOCKED
            }

    }

    abstract fun responder(): Responder?


    internal suspend fun processDataFrame(frame: FrameReceived.DataFrame) {
        val added = addFrame(frame)
        if (added) {
            broadcast() // this blocks the parsing of further packets
        }
    }


}

