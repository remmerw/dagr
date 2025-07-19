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
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

abstract class Connection(
    private val socket: BoundDatagramSocket,
    private val peerId: PeerId,
    private val remotePeerId: PeerId,
    private val remoteAddress: InetSocketAddress,
    private val terminate: Terminate
) : ConnectionData() {

    @OptIn(ExperimentalAtomicApi::class)
    private val packetNumberGenerator: AtomicLong = AtomicLong(0)

    @Volatile
    private var lastAction: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enableKeepAlive = AtomicBoolean(false)

    @Volatile
    private var lastPing = TimeSource.Monotonic.markNow()

    @Volatile
    private var state = State.Created


    fun remoteAddress(): InetSocketAddress {
        return remoteAddress
    }

    fun localAddress(): InetSocketAddress {
        return socket.localAddress as InetSocketAddress
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
                sendFrame(Level.APP, true, PING)
                lastPing = TimeSource.Monotonic.markNow()
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun enableKeepAlive() {
        if (enableKeepAlive.compareAndSet(expectedValue = false, newValue = true)) {
            lastPing = TimeSource.Monotonic.markNow()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun disableKeepAlive() {
        enableKeepAlive.store(false)
    }

    val isConnected: Boolean
        get() = state.isConnected


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun processFrames(source: Source, packetNumber: Long) {

        var frameType: Byte

        while (!source.exhausted()) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-12.4
            // "Each frame begins with a Frame Type, indicating its payloadType,
            // followed by additional payloadType-dependent fields"
            frameType = source.readByte()

            when (frameType.toInt()) {
                0x01 ->  // ping frame nothing to parse
                    sendAck(packetNumber)

                0x02 ->  // isAckEliciting = false
                {
                    val packetNumber = source.readLong()
                    lossDetector().processAckFrameReceived(packetNumber)
                }

                0x18 -> {
                    sendAck(packetNumber)
                    process(parseVerifyRequestFrame(source))
                }

                0x19 -> {
                    sendAck(packetNumber)
                    process(parseVerifyResponseFrame(source))
                }

                0x1c, 0x1d ->  // isAckEliciting is false;
                    process(parseConnectionCloseFrame(source))


                else -> {
                    if ((frameType >= 0x08) && (frameType <= 0x0f)) {
                        sendAck(packetNumber)
                        process(parseDataFrame(frameType, source))
                    } else {
                        error("Receipt a frame of unknown type $frameType")
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun sendAck(packetNumber: Long) {
        sendFrame(Level.APP, false, createAckFrame(packetNumber))
    }

    internal suspend fun processPacket(
        level: Level, source: Source, packetNumber: Long,
    ) {

        if (isDiscarded(level)) {
            return
        }

        if (!state.isClosing) {

            processFrames(source, packetNumber)

            packetIdleProcessed()
        }
    }


    internal abstract suspend fun process(verifyFrame: VerifyRequestFrame)
    internal abstract suspend fun process(verifyFrame: VerifyResponseFrame)


    private suspend fun process(dataFrame: DataFrame) {
        try {
            processDataFrame(dataFrame)
        } catch (transportError: TransportError) {
            sendCloseFrame(transportError)
        }
    }


    internal suspend fun sendCloseFrame(transportError: TransportError) {
        if (state.isClosing) {
            debug("Immediate close ignored because already closing")
            return
        }

        disableKeepAlive()

        clearRequests() // all outgoing messages are cleared -> purpose send connection close

        sendFrame(
            Level.APP, false,
            createConnectionCloseFrame(transportError)
        )


        // "After sending a CONNECTION_CLOSE frame, an endpoint immediately enters the closing state;"
        state(State.Closing)

        terminate()
    }


    private suspend fun process(closing: ConnectionCloseFrame) {
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
        sendCloseFrame(TransportError(TransportError.Code.NO_ERROR))
    }


    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun checkIdle() {
        if (lastAction.elapsedNow().inWholeMilliseconds >
            Settings.MAX_IDLE_TIMEOUT.toLong()
        ) {

            // just tor prevent that another close is scheduled
            lastAction = TimeSource.Monotonic.markNow()

            debug("Idle timeout: silently closing connection $remoteAddress")

            sendCloseFrame(
                TransportError(
                    TransportError.Code.NO_ERROR
                )
            )
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetIdleProcessed() {
        lastAction = TimeSource.Monotonic.markNow()
    }


    @OptIn(ExperimentalAtomicApi::class)
    suspend fun runRequester(): Unit = coroutineScope {
        // Determine whether this loop must be ended _before_ composing packets, to avoid
        // race conditions with
        // items being queued just after the packet assembler (for that level) has executed.
        while (isActive) {

            sendLostPackets()
            keepAlive() // only happens when enabled
            checkIdle() // only happens when enabled

            delay(Settings.MAX_DELAY.toLong())
        }
    }

    private suspend fun sendLostPackets() {
        lossDetection().forEach { packet -> send(packet) }
    }


    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun send(packet: Packet) {
        val buffer = packet.generatePacketBytes()
        val datagram = Datagram(buffer, remoteAddress)

        socket.send(datagram)
        lastAction = TimeSource.Monotonic.markNow()

        packetSent(PacketStatus(packet, lastAction))

    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun sendFrame(level: Level, isAckEliciting: Boolean, frame: ByteArray) {
        val packetNumber = packetNumberGenerator.incrementAndFetch()

        val packet = if (level == Level.APP) {
            Packet.AppPacket(packetNumber, isAckEliciting, frame)
        } else {
            Packet.InitPacket(peerId, packetNumber, isAckEliciting, frame)
        }
        try {
            send(packet)
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
        }
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
