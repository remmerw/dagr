package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class ConnectionData(incoming: Boolean) :
    ConnectionFlow(incoming), Writer {

    private val frames: MutableMap<Long, ByteArray> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency
    private val pipe = Pipe()
    private val lock = ReentrantLock()

    private fun createRequest(request: Long) {
        val packetNumber = fetchPacketNumber()
        sendPacket(
            packetNumber,
            createRequestPacket(packetNumber, request), true
        )
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


    override fun terminate() {
        super.terminate()

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
        } catch (throwable:Throwable) {
            debug(throwable)
            terminate()
            throw throwable
        }
    }
}

