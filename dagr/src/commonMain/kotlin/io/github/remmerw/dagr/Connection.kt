package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

abstract class Connection(
    private val socket: BoundDatagramSocket,
    private val remotePeerId: PeerId,
    private val remoteAddress: InetSocketAddress,
    private val listener: Listener
) : ConnectionData() {

    @OptIn(ExperimentalAtomicApi::class)
    private val localPacket: AtomicLong = AtomicLong(0)

    @Volatile
    private var lastAction: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enableKeepAlive = AtomicBoolean(false)

    @Volatile
    private var lastPing = TimeSource.Monotonic.markNow()

    @Volatile
    private var state = State.Created


    @OptIn(ExperimentalAtomicApi::class)
    private val remotePacket: AtomicLong = AtomicLong(-1)

    suspend fun packetProtector(packetNumber: Long, shouldSendAck: Boolean): Boolean {

        if (shouldSendAck) {
            sendAck(packetNumber)
        }

        return true
    }

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
                val packet = createPingPacket(
                    fetchPackageNumber()
                )
                sendPacket(packet)
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
    private suspend fun sendAck(packetNumber: Long) {
        val packet = createAckPacket(
            fetchPackageNumber(), packetNumber
        )
        sendPacket(packet)
    }

    internal suspend fun process(dataFrame: DataFrame) {
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

        terminateLossDetector()

        sendPacket(
            createClosePacket(
                fetchPackageNumber(), transportError
            )
        )

        state(State.Closing)

        terminate()
    }


    suspend fun process(closing: CloseFrame) {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2.2
        // "The draining state is entered once an endpoint receives a CONNECTION_CLOSE frame,
        // which indicates that its peer is closing or draining."
        if (!state.isClosing) {  // Can occur due to race condition (both peers closing simultaneously)
            if (closing.hasError()) {
                debug("Connection closed with code " + closing.errorCode)
            }

            terminate()
        }
    }


    override suspend fun terminate() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-10.2
        // "Once its closing or draining state ends, an endpoint SHOULD discard all
        // connection state."
        super.terminate()
        listener.close(this)
        state(State.Closed)
    }

    suspend fun close() {
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
    internal fun packetProcessed() {
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
        packetSent(packet)

    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun fetchPackageNumber(): Long {
        return localPacket.incrementAndFetch()
    }

    override suspend fun sendPacket(packet: Packet) {
        try {
            send(packet)
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
        }
    }


    fun remotePeerId(): PeerId {
        return remotePeerId
    }


}
