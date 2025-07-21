package io.github.remmerw.dagr

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

open class Connection(
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    private val listener: Listener
) : ConnectionData() {

    @OptIn(ExperimentalAtomicApi::class)
    private val localPacketNumber: AtomicLong = AtomicLong(Settings.PAKET_OFFSET)

    private val mutex = Mutex()

    @Volatile
    private var lastAction: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val enableKeepAlive = AtomicBoolean(false)

    @Volatile
    private var lastPing = TimeSource.Monotonic.markNow()

    @Volatile
    private var state = State.Created
    private val missingPackets: MutableSet<Long> = mutableSetOf() // no concurrency
    private var remotePacketNumber: Long = Settings.PAKET_OFFSET // no concurrency

    private fun packetProtector(packetNumber: Long): Boolean {


        val oldValue = remotePacketNumber
        if (packetNumber > remotePacketNumber) {
            // standard use case [everything is fine]
            remotePacketNumber = packetNumber

            // just check if there is a gap and add them to missing packets

            val diff = packetNumber - oldValue
            if (diff > 1) {
                val newEntries = diff.toInt() - 1 // -1 because of current packetNumber

                debug("New missed packets $newEntries")

                repeat(newEntries) { i ->
                    missingPackets.add(i + 1 + oldValue) // + 1 because zero based
                }

                // check if missingPackets is bigger then 25 (just close the connection)
                if (missingPackets.size > Settings.MISSED_PACKETS) {
                    debug("To many missed packets, just closing")
                    terminate()
                    return false
                }
            }

            return true
        } else if (packetNumber > Settings.PAKET_OFFSET) {
            // check if packet number is in the missing packets

            return if (missingPackets.remove(packetNumber)) {
                // missing packet detected
                // ack is already send so everything is fine
                debug("detect a really missing packet $packetNumber")
                true
            } else {
                // a packet with this number has already been send
                // so no further actions (indicate by the false)
                debug("packet $packetNumber has already been processed")
                false
            }
        } else {
            return true
        }
    }

    fun remoteAddress(): InetSocketAddress {
        return remoteAddress
    }

    fun localAddress(): InetSocketAddress {
        return InetSocketAddress(socket.localAddress, socket.localPort)
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
                val packet = createPingPacket()
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
        val packet = createAckPacket(packetNumber)
        sendPacket(packet)
    }


    override fun terminate() {
        super.terminate()
        listener.close(this)
        state(State.Closed)
    }

    suspend fun close() {

        if (state.isClosed) {
            debug("Immediate close ignored because already closing")
            return
        }

        disableKeepAlive()

        terminateLossDetector()

        try {
            sendPacket(createClosePacket())
        } catch (_: Throwable) {
        } finally {
            terminate()
        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun checkIdle() {
        if (lastAction.elapsedNow().inWholeMilliseconds >
            Settings.MAX_IDLE_TIMEOUT.toLong()
        ) {

            // just tor prevent that another close is scheduled
            lastAction = TimeSource.Monotonic.markNow()

            debug("Idle timeout: silently closing connection $remoteAddress")

            close()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun packetProcessed() {
        lastAction = TimeSource.Monotonic.markNow()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun runRequester(): Unit = coroutineScope {
        while (isActive) {

            sendLostPackets()
            keepAlive() // only happens when enabled
            checkIdle() // only happens when enabled

            delay(Settings.MAX_DELAY.toLong())

        }
    }

    private suspend fun sendLostPackets() {
        lossDetection().forEach { packet -> sendPacket(packet) }
    }


    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun sendPacket(packet: Packet) {
        mutex.withLock {
            val datagram = DatagramPacket(
                packet.bytes,
                packet.bytes.size, remoteAddress
            )


            packetSent(packet)
            socket.send(datagram)
            lastAction = TimeSource.Monotonic.markNow()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun fetchPacketNumber(): Long {
        return localPacketNumber.incrementAndFetch()
    }


    internal suspend fun processDatagram(packet: DatagramPacket,
                                         callbackConnected: () -> Unit) {
        if (state().isClosed) {
            return
        }

        // check if the remoteAddress is correct
        val address = packet.socketAddress as InetSocketAddress

        if (address.port != remoteAddress.port) {
            debug("Invalid remote address port Ignore Packet")
            return
        }

        if (!address.address.isLoopbackAddress) { // not sure if good solution
            if (address.address != remoteAddress.address) {
                debug("Invalid remote address Ignore Packet")
                return
            }
        }

        val data = packet.data
        val length = packet.length

        if (length < Settings.DATAGRAM_MIN_SIZE ||
            length > Settings.MAX_PACKET_SIZE
        ) {
            debug("Invalid packet length Ignore Packet")
            return
        }

        val type = data[0]

        when(type){
            0x01.toByte(),
            0x02.toByte(),
            0x03.toByte(),
            0x04.toByte()-> {}
            else -> {
                debug("Probably hole punch detected $type")
                return
            }
        }


        val packetNumber = parseLong(data, 1)


        if (state() == State.Created) {
            state(State.Connected)
            callbackConnected.invoke()
        }

        when (type) {
            0x01.toByte() -> { // ping frame
                if (packetNumber != 1L) {
                    debug("Invalid packet number Ignore Packet")
                    return
                }
                sendAck(packetNumber)
                packetProcessed()
            }

            0x02.toByte() -> { // ack frame
                if (packetNumber != 2L) {
                    debug("Invalid packet number Ignore Packet")
                    return
                }
                if (length != (Settings.DATAGRAM_MIN_SIZE + 8)) {
                    debug("Invalid length for ack frame")
                    return
                }
                val pn = parseLong(data, Settings.DATAGRAM_MIN_SIZE)
                processAckFrameReceived(pn)
                packetProcessed()
            }

            0x03.toByte() -> { // data frame
                if (packetProtector(packetNumber)) {
                    val source = data.copyOfRange(Settings.DATAGRAM_MIN_SIZE, length)
                    processData(packetNumber, source)
                    packetProcessed()
                }
            }

            0x04.toByte() -> { // close frame
                if (packetNumber != 4L) {
                    debug("Invalid packet number Ignore Packet")
                    return
                }
                terminate()
            }

            else -> {
                throw Exception("should never reach this point")
            }
        }
    }
}
