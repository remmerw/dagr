package io.github.remmerw.dagr

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeSource
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableMap<Long, Source> = mutableMapOf()// no concurrency

    private var processedPacket: Long = Settings.PAKET_OFFSET // no concurrency

    @OptIn(ExperimentalAtomicApi::class)
    private val reader: AtomicReference<ByteChannel?> = AtomicReference(null)


    @OptIn(ExperimentalAtomicApi::class)
    fun openReadChannel(): ByteReadChannel = ByteChannel(false).also { channel ->
        reader.store(channel)
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun writeBuffer(buffer: RawSource) {


        // readout everything in the channel
        val sink = Buffer()

        do {
            val length = buffer.readAtMostTo(
                sink,
                Settings.MAX_DATAGRAM_SIZE.toLong()
            )

            if (length > 0) {
                val packet = createDataPacket(sink, fetchPacketNumber())

                sendPacket(packet)
            }

        } while (length > 0)

    }

    @OptIn(ExperimentalAtomicApi::class)
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


    @OptIn(ExperimentalAtomicApi::class)
    suspend fun resetReading() {
        reader.load()?.flush()
        frames.clear()
    }


    @OptIn(ExperimentalAtomicApi::class)
    open suspend fun terminate() {
        terminateLossDetector()
        try {
            resetReading()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        try {
            reader.load()?.close()
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

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun appendSource(source: Source) {
        reader.load()?.writeSource(source)
        reader.load()?.flush()
        processedPacket++
    }

}

