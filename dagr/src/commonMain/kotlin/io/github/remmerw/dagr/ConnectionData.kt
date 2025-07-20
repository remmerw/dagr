package io.github.remmerw.dagr

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeSource
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.readByteArray

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableMap<Long, Source> = mutableMapOf()// no concurrency

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

    suspend fun writeByteArray(data: ByteArray) {

        for (chunk in data.indices step Settings.MAX_DATAGRAM_SIZE.toInt()) {
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


    open suspend fun terminate() {
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

    internal suspend fun processData(packetNumber: Long, source: Source) {
        if (packetNumber > processedPacket) {

            if (packetNumber == processedPacket + 1) {
                appendSource(source)
            } else {
                frames.put(packetNumber, source)
                evaluateFrames() // this blocks the parsing of further packets
            }
        } else {
            debug("Data frame not added $packetNumber")
        }
    }

    private suspend fun appendSource(source: Source) {
        reader.writeSource(source) // todo byteArray instead of source
        processedPacket++
    }

    suspend fun readLong(): Long {
        return reader.readLong()
    }

    suspend fun readByteArray(count: Int): ByteArray {
        return reader.readByteArray(count)
    }

}

