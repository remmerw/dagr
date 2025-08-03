package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import kotlin.time.TimeSource

open class Connection(
    private val incoming: Boolean,
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    val acceptor: Acceptor,
    val listener: Listener
) : Writer, AutoCloseable {

    private val sendLog: MutableMap<Long, ByteArray> = ConcurrentHashMap()
    private val largestAcked: AtomicLong = AtomicLong(-1L)
    private val localPacketNumber: AtomicLong = AtomicLong(Settings.PAKET_OFFSET)

    @Volatile
    private var remotePacketTimeStamp = TimeSource.Monotonic.markNow()

    @Volatile
    private var state = State.Created
    private val missingPackets: MutableSet<Long> = mutableSetOf() // no concurrency
    private var remotePacketNumber: Long = Settings.PAKET_OFFSET // no concurrency
    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency
    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val pipe = Pipe()
    private val lock = ReentrantLock()


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


    private fun sendAck(packetNumber: Long) {
        val packet = createAckPacket(packetNumber)
        sendPacket(2, packet, false)
    }


    fun terminate() {

        resetSendLog()
        state(State.Closed)

        try {
            frames.clear()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            pipe.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }


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
        terminate()
    }

    private fun checkIdle() {
        if (remotePacketTimeStamp.elapsedNow().inWholeMilliseconds >
            Settings.IDLE_TIMEOUT.toLong()
        ) {

            // just tor prevent that another close is scheduled
            remotePacketTimeStamp = TimeSource.Monotonic.markNow()

            debug("Idle timeout: silently closing connection $remoteAddress")

            close()
        }
    }

    private fun remotePacketTimeStamp() {
        remotePacketTimeStamp = TimeSource.Monotonic.markNow()
    }


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


    internal fun sendPacket(
        packetNumber: Long,
        packet: ByteArray,
        shouldBeAcked: Boolean
    ) {
        val datagram = DatagramPacket(
            packet, packet.size, remoteAddress
        )

        if (shouldBeAcked) {
            packetSend(packetNumber, packet)
        }
        socket.send(datagram)
    }

    fun fetchPacketNumber(): Long {
        return localPacketNumber.incrementAndGet()
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
                    ackFrameReceived(pn)
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

                    // reset sending log
                    resetSendLog()

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


    internal fun ackFrameReceived(packetNumber: Long) {

        largestAcked.updateAndGet { oldValue ->
            if (packetNumber > oldValue) {
                packetNumber
            } else {
                oldValue
            }
        }

        sendLog.remove(packetNumber)

    }

    internal fun resetSendLog() {
        sendLog.clear()
    }

    internal fun detectLostPackets(): Int {
        var result = 0
        sendLog.keys.forEach { pn ->
            if (packetTooOld(pn)) {
                val packet = sendLog.remove(pn)
                if (packet != null) {
                    result++
                    sendPacket(pn, packet, !incoming())
                }
            }
        }
        return result
    }


    private fun packetTooOld(pn: Long): Boolean {
        if (pn < largestAcked.get()) {
            debug("Loss too old packet $pn")
            return true
        }
        return false
    }

    internal fun packetSend(packetNumber: Long, packet: ByteArray) {
        sendLog[packetNumber] = packet

    }


    private fun createRequest(request: Long) {
        val packetNumber = fetchPacketNumber()
        sendPacket(
            packetNumber,
            createRequestPacket(packetNumber, request), true
        )
    }

    fun incoming(): Boolean {
        return incoming
    }

    override fun writeBuffer(buffer: Buffer) {
        require(buffer.size <= Settings.MAX_SIZE + Int.SIZE_BYTES) {
            "not supported amount of bytes (only 64 kB)"
        }

        while (!buffer.exhausted()) {

            val packetNumber = fetchPacketNumber()
            val sink = Buffer()
            sink.writeByte(DATA)
            sink.writeLong(packetNumber)

            buffer.readAtMostTo(
                sink, Settings.MAX_DATAGRAM_SIZE.toLong()
            )

            sendPacket(packetNumber, sink.readByteArray(), true)
        }
    }


    private fun evaluateFrames() {

        val pn = frames.keys.minOrNull()

        if (pn != null) {
            if (pn == processedPacket + 1) {
                val source = frames.remove(pn)!!
                appendSource(source, 0, source.size)
                if (!frames.isEmpty()) {
                    evaluateFrames()
                }
            }
        }
    }


    internal fun processData(
        packetNumber: Long, source: ByteArray,
        startIndex: Int, endIndex: Int
    ) {
        if (packetNumber > processedPacket) {

            if (packetNumber == processedPacket + 1) {
                appendSource(source, startIndex, endIndex)
                if (frames.isNotEmpty()) {
                    evaluateFrames()
                }
            } else {
                val copy = source.copyOfRange(startIndex, endIndex)
                frames.put(packetNumber, copy) // for future evaluations
                debug("Data frame in the future $packetNumber")
            }
        } else {
            debug("Data frame not added $packetNumber")
        }
    }

    private fun appendSource(bytes: ByteArray, startIndex: Int, endIndex: Int) {
        if (bytes.isNotEmpty()) {
            pipe.sink.write(bytes, startIndex, endIndex)
        }
        processedPacket++
    }

    private fun readInt(timeout: Int? = null): Int {
        val sink = Buffer()
        pipe.readBuffer(sink, Int.SIZE_BYTES, timeout)
        return sink.readInt()
    }

    fun request(request: Long, sink: RawSink, timeout: Int? = null): Int {
        try {
            lock.withLock {
                createRequest(request)
                val count = readInt(timeout)
                pipe.readBuffer(sink, count, timeout)
                return count
            }
        } catch (throwable: Throwable) {
            terminate()
            throw throwable
        }
    }
}
