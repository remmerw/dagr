package io.github.remmerw.dagr

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeByteArray
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val reader = ByteChannel(true)

    suspend fun writeLong(value: Long) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeLong(value)
        sendPacket(Packet(packetNumber, true, buffer.readByteArray()))

    }

    suspend fun writeInt(value: Int) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeInt(value)
        sendPacket(Packet(packetNumber, true, buffer.readByteArray()))

    }

    suspend fun writeByteArray(data: ByteArray) {

        for (chunk in data.indices step Settings.MAX_DATAGRAM_SIZE) {
            val endIndex = kotlin.math.min(
                chunk + Settings.MAX_DATAGRAM_SIZE, data.size
            )

            val packetNumber = fetchPacketNumber()
            val buffer = Buffer()
            buffer.writeByte(0x03.toByte())
            buffer.writeLong(packetNumber)
            buffer.write(data, chunk, endIndex)
            sendPacket(Packet(packetNumber, true, buffer.readByteArray()))
        }
    }

    suspend fun writeBuffer(buffer: RawSource) {


        // readout everything in the channel
        val sink = Buffer()

        do {
            val length = buffer.readAtMostTo(
                sink,
                Settings.MAX_DATAGRAM_SIZE.toLong()
            )

            if (length > 0) {
                val packet = createDataPacket(
                    fetchPacketNumber(), sink.readByteArray()
                )

                sendPacket(packet)
            }

        } while (length > 0)

    }


    private suspend fun evaluateFrames() {

        val pn = frames.keys.minOrNull()

        if (pn != null) {
            if (pn == processedPacket + 1) {
                val source = frames.remove(pn)!!
                appendSource(source)
                if (!frames.isEmpty()) {
                    evaluateFrames()
                }
            }
        }
    }


    internal open fun terminate() {
        terminateLossDetector()
        try {
            frames.clear()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        try {
            reader.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    internal abstract suspend fun sendPacket(packet: Packet)
    internal abstract suspend fun fetchPacketNumber(): Long

    internal suspend fun processData(packetNumber: Long, source: ByteArray) {
        if (packetNumber > processedPacket) {

            if (packetNumber == processedPacket + 1) {
                appendSource(source)
                if(frames.isNotEmpty()) {
                    evaluateFrames()
                }
            } else {
                frames.put(packetNumber, source) // for future evaluations
                debug("Data frame in the future $packetNumber")
            }
        } else {
            debug("Data frame not added $packetNumber")
        }
    }

    private suspend fun appendSource(source: ByteArray) {
        reader.writeByteArray(source)
        processedPacket++
    }

    suspend fun readLong(): Long {
        return reader.readLong()
    }

    suspend fun readInt(): Int {
        return reader.readInt()
    }

    suspend fun readByteArray(count: Int): ByteArray {
        return reader.readByteArray(count)
    }

    suspend fun readBuffer(length: Int): Buffer {
        return reader.readBuffer(length)
    }

}

