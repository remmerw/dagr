package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.Buffer
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

abstract class Connection(
    private val socket: BoundDatagramSocket,
    private val remotePeerId: PeerId,
    private val remoteAddress: InetSocketAddress,
    private val terminate: Terminate
) : ConnectionStreams() {

    @OptIn(ExperimentalAtomicApi::class)
    protected val remoteDelayScale = AtomicInt(Settings.ACK_DELAY_SCALE)
    private val largestPacketNumber = LongArray(Level.LENGTH)
    private val closeFramesSendRateLimiter = RateLimiter()
    private val flowControlIncrement: Long // no concurrency

    @OptIn(ExperimentalAtomicApi::class)
    private val idleTimeout = AtomicLong(Settings.MAX_IDLE_TIMEOUT.toLong())

    @Volatile
    private var lastIdleAction: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enableKeepAlive = AtomicBoolean(false)

    @Volatile
    private var lastPing = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enabledIdle = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    private val idleCounter = AtomicInt(0)

    @OptIn(ExperimentalAtomicApi::class)
    private val marked = AtomicBoolean(false)
    private var flowControlMax =
        Settings.INITIAL_MAX_DATA.toLong() // no concurrency
    private var flowControlLastAdvertised: Long // no concurrency

    @Volatile
    private var state = State.Created

    init {
        this.flowControlLastAdvertised = flowControlMax
        this.flowControlIncrement = flowControlMax / 10
    }


    internal suspend fun sendVerifyFrame(level: Level, token: ByteArray, signature: ByteArray) {
        insertRequest(level, createVerifyFrame(token, signature))
    }


    fun remoteAddress(): InetSocketAddress {
        return remoteAddress
    }


    fun state(): State {
        return state
    }

    fun state(state: State) {
        this.state = state
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun keepAlive() {
        if (enableKeepAlive.load()) {

            if (lastPing.elapsedNow().inWholeMilliseconds > Settings.PING_INTERVAL) {
                addRequest(Level.APP, PING)
                lastPing = TimeSource.Monotonic.markNow()
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun enableKeepAlive() {
        if (enableKeepAlive.compareAndSet(expectedValue = false, newValue = true)) {
            lastPing = TimeSource.Monotonic.markNow()
            idleCounter.store(0)
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun disableKeepAlive() {
        enableKeepAlive.store(false)
    }

    val isConnected: Boolean
        get() = state.isConnected

    suspend fun updateConnectionFlowControl(size: Int) {
        flowControlMax += size.toLong()
        if (flowControlMax - flowControlLastAdvertised > flowControlIncrement) {
            addRequest(Level.APP, createMaxDataFrame(flowControlMax))
            flowControlLastAdvertised = flowControlMax
        }
    }


    internal suspend fun createStream(): Stream {
        return createStream(this, false)
    }

    internal suspend fun nextPacket(reader: Reader) {
        if (reader.remaining() < 2) {
            return
        }

        val posFlags = reader.position()
        val flags = reader.getByte()
        val level = PacketParser.parseLevel(reader, flags) ?: return
        val dcid = PacketParser.dcid(reader, level) ?: return
        parsePackets(reader, level, dcid, flags, posFlags)
    }

    private suspend fun parsePackets(
        reader: Reader,
        level: Level, dcid: Number, flags: Byte, posFlags: Int
    ) {
        try {

            val packetHeader = parsePacket(reader, level, dcid, flags, posFlags)
                ?: return  // when not valid

            processPacket(packetHeader)

            nextPacket(reader)
        } catch (throwable: Throwable) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-24#section-12.2
            // "if decryption fails (...), the receiver (...) MUST attempt to process the
            // remaining packets."
            debug(throwable)
            nextPacket(reader)
        }
    }


    internal fun parsePacket(
        reader: Reader,
        level: Level, dcid: Number,
        flags: Byte, posFlags: Int
    ): PacketHeader? {

        // check if the level is not discarded (level can be null, to avoid expensive
        // exception handling
        if (isDiscarded(level)) {
            return null
        }


        val lpn = largestPacketNumber[level.ordinal]


        val packetHeader = when (level) {

            Level.INIT -> PacketParser.parseInitPackageHeader(
                reader, dcid, flags, posFlags, lpn
            )

            Level.APP -> PacketParser.parseShortPacketHeader(
                reader, dcid, flags, posFlags, lpn
            )
        }

        if (packetHeader == null) {
            return null
        }

        updateKeysAndPackageNumber(packetHeader)
        return packetHeader
    }

    private fun updateKeysAndPackageNumber(packetHeader: PacketHeader) {
        val level = packetHeader.level
        updatePackageNumber(level, packetHeader)
    }


    private fun updatePackageNumber(level: Level, packetHeader: PacketHeader) {
        if (packetHeader.packetNumber > largestPacketNumber[level.ordinal]) {
            largestPacketNumber[level.ordinal] = packetHeader.packetNumber
        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun processFrames(packetHeader: PacketHeader): Boolean {
        // <a href="https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions">...</a>
        // "Ack-eliciting packet: A QUIC packet that contains frames other than ACK, PADDING,
        // and CONNECTION_CLOSE."

        var isAckEliciting = false


        val buffer = Buffer()
        buffer.write(packetHeader.framesBytes)

        var frameType: Byte

        while (buffer.size > 0) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-12.4
            // "Each frame begins with a Frame Type, indicating its payloadType,
            // followed by additional payloadType-dependent fields"
            frameType = buffer.readByte()

            when (frameType.toInt()) {
                0x00 ->  // isAckEliciting = false
                    FrameReceived.parsePaddingFrame(buffer)

                0x01 ->  // ping frame nothing to parse
                    isAckEliciting = true

                0x02, 0x03 ->  // isAckEliciting = false
                    process(
                        FrameReceived.parseAckFrame(
                            frameType, buffer,
                            remoteDelayScale.load()
                        ), packetHeader.level
                    )

                0x04 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseResetStreamFrame(buffer))
                }

                0x05 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseStopSendingFrame(buffer))
                }

                0x07 -> {
                    isAckEliciting = true
                    FrameReceived.parseNewTokenFrame(buffer)
                }

                0x10 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseMaxDataFrame(buffer))
                }

                0x011 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseMaxStreamDataFrame(buffer))
                }

                0x12, 0x13 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseMaxStreamsFrame(frameType, buffer))
                }

                0x14 -> {
                    isAckEliciting = true
                    debug("parseDataBlockedFrame")
                    FrameReceived.parseDataBlockedFrame(buffer)
                    // type will be supported someday in the future
                }

                0x15 -> {
                    isAckEliciting = true
                    debug("parseStreamDataBlockedFrame")
                    FrameReceived.parseStreamDataBlockedFrame(buffer)
                    // type will be supported someday in the future
                }

                0x16, 0x17 -> {
                    isAckEliciting = true
                    debug("parseStreamDataBlockedFrame")
                    FrameReceived.parseStreamsBlockedFrame(frameType, buffer)
                    // type will be supported someday in the future
                }

                0x18 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseVerifyFrame(buffer))
                }


                0x1c, 0x1d ->  // isAckEliciting is false;
                    process(
                        FrameReceived.parseConnectionCloseFrame(
                            frameType, buffer
                        ), packetHeader.level
                    )


                else -> {
                    if ((frameType >= 0x08) && (frameType <= 0x0f)) {
                        isAckEliciting = true
                        process(FrameReceived.parseStreamFrame(frameType, buffer))
                    } else {
                        // https://tools.ietf.org/html/draft-ietf-quic-transport-24#section-12.4
                        // "An endpoint MUST treat the receipt of a frame of unknown payloadType
                        // as a connection error of payloadType FRAME_ENCODING_ERROR."
                        error("Receipt a frame of unknown type $frameType")
                    }
                }
            }
        }

        return isAckEliciting
    }


    private suspend fun process(packetHeader: PacketHeader): Boolean {
        return when (packetHeader.level) {
            Level.INIT -> {
                processFrames(packetHeader)
            }

            Level.APP -> {

                processFrames(packetHeader)
            }
        }
    }


    internal suspend fun processPacket(packetHeader: PacketHeader) {


        if (!state.closingOrDraining()) {
            val level = packetHeader.level

            val isAckEliciting = process(packetHeader)


            // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-13.1
            // "A packet MUST NOT be acknowledged until packet protection has been successfully
            // removed and all frames contained in the packet have been processed."
            // "Once the packet has been fully processed, a receiver acknowledges receipt by
            // sending one or more ACK frames containing the packet number of the received
            // packet." boolean isAckEliciting  = PacketHeader.isAckEliciting(frames);
            ackGenerator(level).packetReceived(
                level, isAckEliciting,
                packetHeader.packetNumber
            )


            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
            // "An endpoint restarts its idle timer when a packet from its peer is received
            // and processed successfully."
            packetIdleProcessed()
        } else if (state.isClosing) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.1
            // "An endpoint in the closing state sends a packet containing a CONNECTION_CLOSE
            // frame in response
            //  to any incoming packet that it attributes to the connection."
            handlePacketInClosingState(packetHeader.level)
        }

    }

    internal abstract suspend fun process(verifyFrame: FrameReceived.VerifyFrame)


    private suspend fun process(maxStreamDataFrame: FrameReceived.MaxStreamDataFrame) {
        try {
            processMaxStreamDataFrame(maxStreamDataFrame)
        } catch (transportError: TransportError) {
            immediateCloseWithError(Level.APP, transportError)
        }
    }


    private suspend fun process(streamFrame: FrameReceived.StreamFrame) {
        try {
            println("Process stream frame " + streamFrame.streamId)
            processStreamFrame(this, streamFrame)
        } catch (transportError: TransportError) {
            immediateCloseWithError(Level.APP, transportError)
        }
    }

    /**
     *
     */
    fun determineIdleTimeout(maxIdleTimout: Long, peerMaxIdleTimeout: Long) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
        // "If a max_idle_timeout is specified by either peer in its transport parameters
        // (Section 18.2), the
        //  connection is silently closed and its state is discarded when it remains idle
        //  for longer than the minimum of both peers max_idle_timeout values."

        var idleTimeout = min(maxIdleTimout, peerMaxIdleTimeout)
        if (idleTimeout == 0L) {
            // Value of 0 is the same as not specified.
            idleTimeout = max(maxIdleTimout, peerMaxIdleTimeout)
        }
        if (idleTimeout != 0L) {
            // Initialise the idle timer that will take care of (silently) closing connection
            // if idle longer than idle timeout
            setIdleTimeout(idleTimeout)
        } else {
            // Both or 0 or not set: [Note: this does not occur within this application]
            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-18.2
            // "Idle timeout is disabled when both endpoints omit this transport parameter or
            // specify a value of 0."
            setIdleTimeout(Long.MAX_VALUE)
        }
    }


    internal suspend fun immediateCloseWithError(level: Level, transportError: TransportError) {
        if (state.closingOrDraining()) {
            debug("Immediate close ignored because already closing")
            return
        }

        disableKeepAlive()

        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "An endpoint sends a CONNECTION_CLOSE frame (Section 19.19) to terminate the connection immediately."
        clearRequests() // all outgoing messages are cleared -> purpose send connection close
        addRequest(level, createConnectionCloseFrame(transportError))


        // "After sending a CONNECTION_CLOSE frame, an endpoint immediately enters the closing state;"
        state(State.Closing)


        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "The closing and draining connection states exist to ensure that connections
        // close cleanly and that
        // delayed or reordered packets are properly discarded. These states SHOULD persist
        // for at least three times the current Probe Timeout (PTO) interval"
        scheduleTerminate(pto)

    }

    private suspend fun handlePacketInClosingState(level: Level) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.2
        // "An endpoint MAY enter the draining state from the closing state if it receives
        // a CONNECTION_CLOSE frame, which indicates that the peer is also closing or draining."
        // NOT DONE HERE (NO DRAINING)

        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.1
        // "An endpoint in the closing state sends a packet containing a CONNECTION_CLOSE frame
        // in response to any incoming packet that it attributes to the connection."
        // "An endpoint SHOULD limit the rate at which it generates packets in the closing state."

        closeFramesSendRateLimiter.execute(object : Limiter {
            override suspend fun run() {
                addRequest(
                    level, createConnectionCloseFrame()
                )
            }
        })
        // No flush necessary, as this method is called while processing a received packet.
    }

    private suspend fun process(closing: FrameReceived.ConnectionCloseFrame, level: Level) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.2
        // "The draining state is entered once an endpoint receives a CONNECTION_CLOSE frame,
        // which indicates that its peer is closing or draining."
        if (!state.closingOrDraining()) {  // Can occur due to race condition (both peers closing simultaneously)
            if (closing.hasError()) {
                debug("Connection closed with " + determineClosingErrorMessage(closing))
            }
            clearRequests()

            // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.2
            // "An endpoint that receives a CONNECTION_CLOSE frame MAY send a single packet
            // containing a CONNECTION_CLOSE
            // frame before entering the draining state, using a CONNECTION_CLOSE frame and a
            // NO_ERROR code if appropriate.An endpoint MUST NOT send further packets."
            addRequest(level, createConnectionCloseFrame())

            drain()
        }
    }

    private suspend fun drain() {
        state(State.Draining)
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "The closing and draining connection states exist to ensure that connections close
        // cleanly and that
        // delayed or reordered packets are properly discarded. These states SHOULD persist
        // for at least three
        // times the current Probe Timeout (PTO) interval"
        val pto = pto
        scheduleTerminate(pto)
    }


    open suspend fun terminate() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "Once its closing or draining state ends, an endpoint SHOULD discard all
        // connection state."
        super.cleanup()
        terminate.terminate(this)
        state(State.Closed)
    }

    suspend fun close() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        immediateCloseWithError(Level.APP, TransportError(TransportError.Code.NO_ERROR))
    }

    suspend fun scheduleTerminate(pto: Int) {
        delay(pto.toLong())
        terminate()
    }

    internal abstract fun token(): ByteArray
    internal abstract fun peerId(): PeerId

    @OptIn(ExperimentalAtomicApi::class)
    private fun setIdleTimeout(idleTimeoutInMillis: Long) {
        if (!enabledIdle.exchange(true)) {
            lastIdleAction = TimeSource.Monotonic.markNow()
            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
            // To avoid excessively small idle timeout periods, endpoints MUST increase
            // the idle timeout period to be at least three times the current Probe Timeout (PTO)
            idleTimeout.store(max(idleTimeoutInMillis, (3L * pto)))
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun checkIdle() {
        if (enabledIdle.load()) {

            if (lastIdleAction.elapsedNow().inWholeMilliseconds > idleTimeout.load()) {
                enabledIdle.store(false)

                // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.1
                // "If a max_idle_timeout is specified by either peer (...), the connection is silently
                // closed and its state is
                //  discarded when it remains idle for longer than the minimum of both
                //  peers max_idle_timeout values."
                debug("Idle timeout: silently closing connection $remoteAddress")

                clearRequests()
                terminate()
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetIdleProcessed() {
        if (enabledIdle.load()) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
            // "An endpoint restarts its idle timer when a packet from its peer is received
            // and processed successfully."
            lastIdleAction = TimeSource.Monotonic.markNow()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetIdleSent(packet: Packet, sendTime: TimeSource.Monotonic.ValueTimeMark) {
        if (enabledIdle.load()) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
            // "An endpoint also restarts its idle timer when sending an ack-eliciting packet
            // if no other ack-eliciting packets have been sent since last receiving and
            // processing a packet. "
            if (isAckEliciting(packet)) {
                lastIdleAction = sendTime
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun runRequester() {
        try {
            // Determine whether this loop must be ended _before_ composing packets, to avoid
            // race conditions with
            // items being queued just after the packet assembler (for that level) has executed.
            while (true) {
                lossDetection()
                sendIfAny()

                keepAlive() // only happens when enabled
                checkIdle() // only happens when enabled

                val time = min(
                    (Settings.MAX_ACK_DELAY * (idleCounter.load() + 1)),
                    1000
                ).toLong() // time is max 1s
                delay(time)
            }
        } catch (_: CancellationException) {
            // ignore exception
        } catch (_: Throwable) {
            socket?.isClosed?.let {
                if (!it) {
                    abortConnection()
                }
            }
        }
    }

    /**
     * Abort connection due to a fatal error in this client.
     * No message is sent to peer; just inform client it's all over.
     *
     */
    private suspend fun abortConnection() {
        state(State.Failed)
        clearRequests()
        terminate()
    }

    private suspend fun sendIfAny() {
        var items: List<Packet>
        do {
            items = assemblePackets()
            if (items.isNotEmpty()) {
                send(items)
            }
        } while (items.isNotEmpty())
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun send(itemsToSend: List<Packet>) {
        for (packet in itemsToSend) {
            val buffer = packet.generatePacketBytes()
            val size = buffer.size

            val datagram = Datagram(buffer, remoteAddress)

            val timeSent = TimeSource.Monotonic.markNow()
            socket.send(datagram)

            idleCounter.store(0)
            packetSent(packet, size.toInt(), timeSent)
            packetIdleSent(packet, timeSent)

        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    fun mark() {
        marked.store(true)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun isMarked(): Boolean {
        return marked.load()
    }

    private suspend fun assemblePackets(): List<Packet> {

        val peerId = peerId()


        val packets: MutableList<Packet> = arrayListOf()
        var size = 0

        val minPacketSize = Long.SIZE_BYTES + 1 // Computed for short header packet
        var remaining = min(remainingCwnd().toInt(), Settings.MAX_PACKAGE_SIZE)

        for (level in Level.levels()) {
            if (!isDiscarded(level)) {
                val assembler = packetAssembler(level)

                val item = assembler.assemble(
                    remaining,
                    Settings.MAX_PACKAGE_SIZE - size, peerId
                )

                if (item != null) {
                    packets.add(item)
                    val packetSize = item.estimateLength()
                    size += packetSize
                    remaining -= packetSize
                }
                if (remaining < minPacketSize && (Settings.MAX_PACKAGE_SIZE - size) < minPacketSize) {
                    // Trying a next level to produce a packet is useless
                    break
                }
            }
        }

        return packets
    }

    fun remotePeerId(): PeerId {
        return remotePeerId
    }


    enum class State {
        Created,
        Handshaking,
        Connected,
        Closing,
        Draining,
        Closed,
        Failed;

        fun closingOrDraining(): Boolean {
            return this == Closing || this == Draining
        }

        val isClosing: Boolean
            get() = this == Closing

        val isConnected: Boolean
            get() = this == Connected
    }

    private fun determineClosingErrorMessage(closing: FrameReceived.ConnectionCloseFrame): String {
        return if (closing.hasTransportError()) {
            if (closing.hasTlsError()) {
                "TLS error " + closing.tlsError + (if (closing.hasReasonPhrase()) ": " + closing.reasonPhrase else "")
            } else {
                "transport error " + closing.errorCode + (if (closing.hasReasonPhrase()) ": " + closing.reasonPhrase else "")
            }
        } else if (closing.hasApplicationProtocolError()) {
            "application protocol error " + closing.errorCode +
                    (if (closing.hasReasonPhrase()) ": " + closing.reasonPhrase else "")
        } else {
            ""
        }
    }


}
