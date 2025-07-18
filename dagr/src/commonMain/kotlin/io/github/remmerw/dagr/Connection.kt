package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.io.Source
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

abstract class Connection(
    private val socket: BoundDatagramSocket,
    private val peerId: PeerId,
    private val remotePeerId: PeerId,
    private val remoteAddress: InetSocketAddress,
    private val terminate: Terminate
) : ConnectionData() {
    private val closeFramesSendRateLimiter = RateLimiter()

    @Volatile
    private var lastAction: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enableKeepAlive = AtomicBoolean(false)

    @Volatile
    private var lastPing = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val idleCounter = AtomicInt(0)

    @Volatile
    private var state = State.Created


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


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun processFrames(level: Level, source: Source): Boolean {
        // <a href="https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions">...</a>
        // "Ack-eliciting packet: A QUIC packet that contains frames other than ACK, PADDING,
        // and CONNECTION_CLOSE."

        var isAckEliciting = false


        var frameType: Byte

        while (!source.exhausted()) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-12.4
            // "Each frame begins with a Frame Type, indicating its payloadType,
            // followed by additional payloadType-dependent fields"
            frameType = source.readByte()

            when (frameType.toInt()) {
                0x01 ->  // ping frame nothing to parse
                    isAckEliciting = true

                0x02, 0x03 ->  // isAckEliciting = false
                    process(
                        FrameReceived.parseAckFrame(
                            frameType, source,
                        ), level
                    )


                0x18 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseVerifyRequestFrame(source))
                }

                0x19 -> {
                    isAckEliciting = true
                    process(FrameReceived.parseVerifyResponseFrame(source))
                }

                0x1c, 0x1d ->  // isAckEliciting is false;
                    process(FrameReceived.parseConnectionCloseFrame(source))


                else -> {
                    if ((frameType >= 0x08) && (frameType <= 0x0f)) {
                        isAckEliciting = true
                        process(FrameReceived.parseDataFrame(frameType, source))
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


    internal suspend fun processPacket(
        level: Level, source: Source, packetNumber: Long,
    ) {

        if (isDiscarded(level)) {
            return
        }

        if (!state.isClosing) {

            val isAckEliciting = processFrames(level, source)

            ackGenerator(level).packetReceived(isAckEliciting, packetNumber)

            // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
            // "An endpoint restarts its idle timer when a packet from its peer is received
            // and processed successfully."
            packetIdleProcessed()
        } else if (state.isClosing) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.1
            // "An endpoint in the closing state sends a packet containing a CONNECTION_CLOSE
            // frame in response
            //  to any incoming packet that it attributes to the connection."
            handlePacketInClosingState(level)
        }
    }


    internal abstract suspend fun process(verifyFrame: FrameReceived.VerifyRequestFrame)
    internal abstract suspend fun process(verifyFrame: FrameReceived.VerifyResponseFrame)


    private suspend fun process(dataFrame: FrameReceived.DataFrame) {
        try {
            processDataFrame(dataFrame)
        } catch (transportError: TransportError) {
            scheduledClose(Level.APP, transportError)
        }
    }


    internal suspend fun scheduledClose(level: Level, transportError: TransportError) {
        if (state.isClosing) {
            debug("Immediate close ignored because already closing")
            return
        }

        disableKeepAlive()

        clearRequests() // all outgoing messages are cleared -> purpose send connection close
        addRequest(level, createConnectionCloseFrame(transportError))


        // "After sending a CONNECTION_CLOSE frame, an endpoint immediately enters the closing state;"
        state(State.Closing)


        scheduleTerminate(Settings.MAX_ACK_DELAY)

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

    private suspend fun process(closing: FrameReceived.ConnectionCloseFrame) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.2
        // "The draining state is entered once an endpoint receives a CONNECTION_CLOSE frame,
        // which indicates that its peer is closing or draining."
        if (!state.isClosing) {  // Can occur due to race condition (both peers closing simultaneously)
            if (closing.hasError()) {
                debug("Connection closed with code " + closing.errorCode)
            }
            clearRequests()

            terminate()
        }
    }


    override suspend fun terminate() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "Once its closing or draining state ends, an endpoint SHOULD discard all
        // connection state."
        super.cleanup()
        terminate.terminate(this)
        state(State.Closed)
    }

    override suspend fun close() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        scheduledClose(Level.APP, TransportError(TransportError.Code.NO_ERROR))
    }

    suspend fun scheduleTerminate(pto: Int) {
        delay(pto.toLong())
        terminate()
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun checkIdle() {
        if (lastAction.elapsedNow().inWholeMilliseconds >
            Settings.MAX_IDLE_TIMEOUT.toLong()
        ) {

            // just tor prevent that another close is scheduled
            lastAction = TimeSource.Monotonic.markNow()

            debug("Idle timeout: silently closing connection $remoteAddress")

            scheduledClose(
                Level.APP, TransportError(
                    TransportError.Code.NO_ERROR
                )
            )
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetIdleProcessed() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
        // "An endpoint restarts its idle timer when a packet from its peer is received
        // and processed successfully."
        lastAction = TimeSource.Monotonic.markNow()

    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetIdleSent(packet: Packet, sendTime: TimeSource.Monotonic.ValueTimeMark) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-31#section-10.1
        // "An endpoint also restarts its idle timer when sending an ack-eliciting packet
        // if no other ack-eliciting packets have been sent since last receiving and
        // processing a packet. "
        if (isAckEliciting(packet)) {
            lastAction = sendTime
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun runRequester(): Unit = coroutineScope {
        // Determine whether this loop must be ended _before_ composing packets, to avoid
        // race conditions with
        // items being queued just after the packet assembler (for that level) has executed.
        while (isActive) {
            lossDetection()
            sendIfAny()

            keepAlive() // only happens when enabled
            checkIdle() // only happens when enabled
        }
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

            val datagram = Datagram(buffer, remoteAddress)

            val timeSent = TimeSource.Monotonic.markNow()
            socket.send(datagram)

            idleCounter.store(0)
            packetSent(packet, timeSent)
            packetIdleSent(packet, timeSent)

        }
    }


    private suspend fun assemblePackets(): List<Packet> {


        val packets: MutableList<Packet> = arrayListOf()

        for (level in Level.levels()) {
            if (!isDiscarded(level)) {
                val assembler = packetAssembler(level)
                val item = assembler.assemble(peerId)
                if (item != null) {
                    packets.add(item)
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
        Connected,
        Closing,
        Closed;

        val isClosing: Boolean
            get() = this == Closing

        val isConnected: Boolean
            get() = this == Connected
    }

}
