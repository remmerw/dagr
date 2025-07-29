package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class ConnectionData() :
    ConnectionFlow(), Writer {

    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val pipe = Pipe()
    private val lock = ReentrantLock()

    private fun writeLong(value: Long) {
        val packetNumber = fetchPacketNumber()
        val buffer = Buffer()
        buffer.writeByte(0x03.toByte())
        buffer.writeLong(packetNumber)
        buffer.writeLong(value)
        sendPacket(packetNumber, buffer.readByteArray(), true)
    }


    override fun writeByteArray(data: ByteArray) {
        try {
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
        } finally {
            flush()
        }
    }

    override fun writeBuffer(buffer: Buffer) {
        try {
            while (!buffer.exhausted()) {

                val packetNumber = fetchPacketNumber()
                val sink = Buffer()
                sink.writeByte(0x03.toByte())
                sink.writeLong(packetNumber)

                buffer.readAtMostTo(
                    sink, Settings.MAX_DATAGRAM_SIZE.toLong()
                )

                sendPacket(packetNumber, sink.readByteArray(), true)
            }
        } finally {
            flush()
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


    internal open fun terminate() {
        try {
            terminateLossDetector()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

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
    }

    internal abstract fun fetchPacketNumber(): Long

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


    fun request(request: Long, count: Int, timeout: Int? = null): ByteArray {
        val sink = Buffer()
        request(request, sink, count, timeout)
        return sink.readByteArray()
    }

    fun request(request: Long, sink: RawSink, count: Int, timeout: Int? = null) {
        lock.withLock {
            writeLong(request)
            pipe.readBuffer(sink, count, timeout)
        }
    }

}

