package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val pipe = Pipe(UShort.MAX_VALUE.toLong())

    suspend fun writeLong(value: Long) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeLong(value)
        sendPacket(packetNumber, buffer.readByteArray(), true)

    }

    suspend fun flush() {
        sync()
    }

    suspend fun writeInt(value: Int) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeInt(value)
        sendPacket(packetNumber, buffer.readByteArray(), true)

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
            sendPacket(packetNumber, buffer.readByteArray(), true)
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
                val packetNumber = fetchPacketNumber()
                val packet = createDataPacket(
                    packetNumber, sink.readByteArray()
                )

                sendPacket(packetNumber, packet, true)
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
            pipe.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    internal abstract suspend fun fetchPacketNumber(): Long

    internal suspend fun processData(packetNumber: Long, source: ByteArray) {
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

    fun readLong(): Long {
        val sink = Buffer()
        var bytes = Long.SIZE_BYTES.toLong()
        do {
            bytes -= pipe.source.read(sink, bytes)
        } while (bytes > 0)
        return sink.readLong()
    }

    fun readInt(): Int {
        val sink = Buffer()
        var bytes = Int.SIZE_BYTES.toLong()
        do {
            bytes -= pipe.source.read(sink, bytes)
        } while (bytes > 0)
        return sink.readInt()
    }

    fun readByteArray(count: Int): ByteArray {
        return readBuffer(count).readByteArray()
    }

    fun readBuffer(count: Int): Buffer {
        val sink = Buffer()
        var bytes = count.toLong()
        do {
            bytes -= pipe.source.read(sink, bytes)
        } while (bytes > 0)
        return sink
    }

}

