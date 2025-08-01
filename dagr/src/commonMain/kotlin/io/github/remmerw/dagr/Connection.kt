package io.github.remmerw.dagr

import kotlinx.io.Buffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

open class Connection(
    incoming: Boolean,
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    val acceptor: Acceptor,
    val listener: Listener
) : ConnectionData(incoming), AutoCloseable {

    @OptIn(ExperimentalAtomicApi::class)
    private val localPacketNumber: AtomicLong = AtomicLong(Settings.PAKET_OFFSET)

    @Volatile
    private var remotePacketTimeStamp = TimeSource.Monotonic.markNow()

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

    fun localPort(): Int {
        return socket.localPort
    }

    fun state(): State {
        return state
    }

    fun state(state: State) {
        this.state = state
    }


    val isConnected: Boolean
        get() = state.isConnected


    @OptIn(ExperimentalAtomicApi::class)
    private fun sendAck(packetNumber: Long) {
        val packet = createAckPacket(packetNumber)
        sendPacket(2, packet, false)
    }


    override fun terminate() {
        super.terminate()
        state(State.Closed)

        try {
            listener.close(this)
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    override fun close() {

        if (state.isClosed) {
            debug("Immediate close ignored because already closing")
            return
        }

        try {
            // only outgoing connections (client) notify the server
            if (!incoming()) {
                sendPacket(5, createClosePacket(), false)
            }
        } catch (_: SocketException) {
        } catch (throwable: Throwable) {
            debug(throwable)
        } finally {
            terminate()
        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    private fun checkIdle() {
        if (remotePacketTimeStamp.elapsedNow().inWholeMilliseconds >
            Settings.MAX_IDLE_TIMEOUT.toLong()
        ) {

            // just tor prevent that another close is scheduled
            remotePacketTimeStamp = TimeSource.Monotonic.markNow()

            debug("Idle timeout: silently closing connection $remoteAddress")

            close()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun remotePacketTimeStamp() {
        remotePacketTimeStamp = TimeSource.Monotonic.markNow()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal fun maintenance(): Int {
        try {
            val lost = detectLostPackets()
            checkIdle() // only happens when enabled
            return lost
        } catch (_: InterruptedException) {
        } catch (_: SocketException) {
        } catch (throwable: Throwable) {
            debug(throwable)
            terminate()
        }
        return 0
    }


    override fun sendPacket(
        packetNumber: Long,
        packet: ByteArray,
        shouldBeAcked: Boolean
    ) {
        val datagram = DatagramPacket(
            packet, packet.size, remoteAddress
        )

        if (shouldBeAcked) {
            packetSent(packetNumber, packet)
        }
        socket.send(datagram)
    }

    @OptIn(ExperimentalAtomicApi::class)
    override fun fetchPacketNumber(): Long {
        return localPacketNumber.incrementAndFetch()
    }


    internal fun processDatagram(
        type: Byte,
        data: ByteArray,
        length: Int,
    ) {
        try {
            if (state().isClosed) {
                return
            }

            val packetNumber = parseLong(data, 1)

            if (state() == State.Created) {
                state(State.Connected)
                listener.connected(this)
            }

            when (type) {
                CONNECT -> { // connect frame
                    require(incoming()) { "only for incoming connections" }
                    require(packetNumber == 1L) { "Invalid packet number Ignore Packet" }
                    sendAck(packetNumber)
                    remotePacketTimeStamp()
                }

                ACK -> { // ack frame
                    require(packetNumber == 2L) { "Invalid packet number Ignore Packet" }
                    require(length == (Settings.DATAGRAM_MIN_SIZE + Long.SIZE_BYTES)) {
                        "Invalid length for ack frame"
                    }
                    val pn = parseLong(data, Settings.DATAGRAM_MIN_SIZE)
                    processAckFrameReceived(pn)
                    remotePacketTimeStamp()
                }

                REQUEST -> {
                    require(incoming()) { "Request coming only from incoming connections" }

                    sendAck(packetNumber)

                    val request = Buffer()

                    require(length == Settings.DATAGRAM_MIN_SIZE + Long.SIZE_BYTES) {
                        "invalid size of request"
                    }

                    request.write(
                        data, Settings.DATAGRAM_MIN_SIZE,
                        Settings.DATAGRAM_MIN_SIZE + Long.SIZE_BYTES
                    )

                    acceptor.request(this, request.readLong())

                    remotePacketTimeStamp()

                }

                DATA -> { // data frame
                    require(!incoming()) { "Data coming only from outgoing connections" }
                    sendAck(packetNumber)
                    if (packetProtector(packetNumber)) {
                        processData(
                            packetNumber, data,
                            Settings.DATAGRAM_MIN_SIZE, length
                        )
                        remotePacketTimeStamp()
                    }
                }

                else -> {
                    throw Exception("should never reach this point")
                }
            }

        } catch (throwable: Throwable) {
            debug(throwable)
            terminate()
        }
    }
}
