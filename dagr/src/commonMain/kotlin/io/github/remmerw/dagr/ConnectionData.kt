package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val pipe = Pipe(UShort.MAX_VALUE.toLong())

    fun writeLong(value: Long) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeLong(value)
        sendPacket(packetNumber, buffer.readByteArray(), true)

    }

    fun flush() {
        sync()
    }

    fun writeInt(value: Int) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeInt(value)
        sendPacket(packetNumber, buffer.readByteArray(), true)

    }

    fun writeByteArray(data: ByteArray) {

        for (chunk in data.indices step Settings.MAX_DATAGRAM_SIZE) {
            val endIndex = kotlin.math.min(
                chunk + Settings.MAX_DATAGRAM_SIZE, data.size
            )

            val packetNumber = fetchPacketNumber()
            val buffer = Buffer()
            buffer.writeByte(0x03.toByte())
            buffer.writeLong(packetNumber)
            buffer.write(data, chunk, endIndex)
            sendPacket(packetNumber, buffer.readByteArray(), true)
        }
    }

    fun writeBuffer(buffer: RawSource) {


        // readout everything in the channel
        val sink = Buffer()

        do {

            val length = buffer.readAtMostTo(
                sink,
                Settings.MAX_DATAGRAM_SIZE.toLong()
            )

            if (length > 0) {
                val packetNumber = fetchPacketNumber()
                val packet = createDataPacket(
                    packetNumber, sink.readByteArray()
                )

                sendPacket(packetNumber, packet, true)
            }

        } while (length > 0)

    }


    private fun evaluateFrames() {

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
            pipe.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    internal abstract fun fetchPacketNumber(): Long

    internal fun processData(packetNumber: Long, source: ByteArray) {
        if (packetNumber > processedPacket) {

            if (packetNumber == processedPacket + 1) {
                appendSource(source)
                if (frames.isNotEmpty()) {
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

    private fun appendSource(bytes: ByteArray) {
        pipe.sink.write(bytes)
        processedPacket++
    }

    fun readLong(timeout: Int? = null): Long {
        val sink = Buffer()
        readBuffer(sink, Long.SIZE_BYTES, timeout)
        return sink.readLong()
    }

    fun readInt(timeout: Int? = null): Int {
        val sink = Buffer()
        readBuffer(sink, Int.SIZE_BYTES, timeout)
        return sink.readInt()
    }

    fun readByteArray(count: Int, timeout: Int? = null): ByteArray {
        val sink = Buffer()
        readBuffer(sink, count, timeout)
        return sink.readByteArray()
    }

    fun readBuffer(sink: Buffer, count: Int, timeout: Int? = null) {
        pipe.readBuffer(sink, count, timeout)
    }

}

