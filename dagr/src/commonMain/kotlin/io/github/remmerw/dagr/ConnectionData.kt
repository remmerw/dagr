package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
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

    private var processedToOffset: Long = 0 // no concurrency


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



        if (frames.isEmpty()) {
            if (isFinal) {
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
        readingBuffer.clear()
    }

    fun resetSending() {
        sendingOffset = 0
        isSendingFinal = false
        sendingBuffer.clear()
    }


    open suspend fun close() {
        terminate()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal open suspend fun terminate() {
        reset.compareAndSet(expectedValue = false, newValue = true)
        sendingBuffer.clear()
        readingBuffer.clear()

        try {
            requestFinish.release()
        } catch (_: Throwable) {
        }
    }

    internal abstract suspend fun sendDataFrame(dataFrame: Frame)

    suspend fun write(buffer: Buffer, autoFlush: Boolean = true) {
        this.isSendingFinal = autoFlush

        var offset = 0L
        while(!buffer.exhausted()){
            val read = min(1200, buffer.remaining)
            var finalFrame = false
            if(read < 1200 && autoFlush){
                finalFrame = true
            }


            val byteArray = buffer.readByteArray(read.toInt())

            val dataFrame = createDataFrame(
                offset, byteArray, finalFrame
            )
            offset += read

            sendDataFrame(dataFrame)

        }

    }


    // this is a blocking request with the given timeout [fin is written, no more writing data allowed]
    suspend fun request(timeout: Long, data: Buffer): ByteArray {
        write(data)

        try {
            return withTimeout(timeout * 1000L) {
                requestFinish.acquire()
                response().readByteArray()
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


                var maxBytesToSend = sendingBuffer.size.toInt()


                val dummyFrameLength = frameLength(sendingOffset, 0)

                maxBytesToSend = min(
                    maxBytesToSend,
                    maxFrameSize - dummyFrameLength - 1
                ) // Take one byte extra for length field var int


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
                }

                return dataFrame

            }
        }
        return null
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


    abstract fun responder(): Responder?


    internal suspend fun processDataFrame(frame: FrameReceived.DataFrame) {
        val added = addFrame(frame)
        if (added) {
            broadcast() // this blocks the parsing of further packets
        }
    }


}

