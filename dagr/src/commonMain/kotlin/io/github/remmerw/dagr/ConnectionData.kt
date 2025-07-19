package io.github.remmerw.dagr

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.writeSource
import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableList<DataFrame> = mutableListOf() // no concurrency

    private var processedToOffset: Long = 0 // no concurrency

    @OptIn(ExperimentalAtomicApi::class)
    private val reader: AtomicReference<ByteChannel?> = AtomicReference(null)

    @OptIn(ExperimentalAtomicApi::class)
    fun openReadChannel(): ByteReadChannel = ByteChannel(false).also { channel ->
        reader.store(channel)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun broadcast() {
        //var bytesRead = 0

        val iterator = frames.iterator()
        var isFinal = false
        while (iterator.hasNext()) {
            val frame = iterator.next()

            if (frame.offset <= processedToOffset) {
                val upToOffset = frame.offsetLength()
                if (upToOffset >= processedToOffset) {

                    reader.load()?.writeSource(frame.source)

                    processedToOffset = frame.offsetLength()

                    if (frame.isFinal) {
                        isFinal = true
                    }
                    iterator.remove()

                }
            } else {
                break
            }
        }



        if (frames.isEmpty()) {
            if (isFinal) {
                resetReading()
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun resetReading() {
        reader.load()?.flush()
        frames.clear()
        processedToOffset = 0
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
    internal abstract suspend fun fetchPackageNumber(): Long

    suspend fun write(source: Source, autoFlush: Boolean = true) {
        var offset = 0L
        while (!source.exhausted()) {
            val length = min(Settings.MAX_DATAGRAM_SIZE, source.remaining)
            var finalFrame = false
            if (length < Settings.MAX_DATAGRAM_SIZE && autoFlush) {
                finalFrame = true
            }

            val packet = createDataPacket(
                fetchPackageNumber(), source, offset,
                length.toInt(), finalFrame
            )
            offset += length

            sendPacket(packet)
        }
    }

    private fun addFrame(frame: DataFrame): Boolean {
        if (frame.offset >= processedToOffset) {
            return frames.add(frame)
        } else {
            debug("Frame not added $frame")
            return false
        }
    }


    internal suspend fun processDataFrame(frame: DataFrame) {

        val added = addFrame(frame)
        if (added) {
            broadcast() // this blocks the parsing of further packets
        }
    }
}

