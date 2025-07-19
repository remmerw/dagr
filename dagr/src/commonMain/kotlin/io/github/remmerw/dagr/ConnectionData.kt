package io.github.remmerw.dagr

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.writeFully
import kotlinx.io.Source
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableList<DataFrame> = mutableListOf() // no concurrency

    @OptIn(ExperimentalAtomicApi::class)
    private val reset = AtomicBoolean(false)

    private var processedToOffset: Long = 0 // no concurrency

    @OptIn(ExperimentalAtomicApi::class)
    private val reader: AtomicReference<ByteChannel?> = AtomicReference(null)

    @OptIn(ExperimentalAtomicApi::class)
    fun openReadChannel(): ByteReadChannel = ByteChannel(false).also { channel ->
        reader.store(channel)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun broadcast() {
        var bytesRead = 0

        val iterator = frames.iterator()
        var isFinal = false
        while (iterator.hasNext()) {
            val frame = iterator.next()

            if (frame.offset <= processedToOffset) {
                val upToOffset = frame.offsetLength()
                if (upToOffset >= processedToOffset) {
                    bytesRead += frame.length

                    reader.load()?.writeFully(frame.bytes)

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
                reader.load()?.flush() // todo ??
                resetReading()
            }
        }
    }

    fun resetReading() {
        frames.clear()
        processedToOffset = 0
    }


    open suspend fun close() { // todo
        terminate()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal open suspend fun terminate() {
        reset.compareAndSet(expectedValue = false, newValue = true)
        reader.load()?.close() // TODO ??
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

            val packet = createAppDataPacket(
                fetchPackageNumber(),
                true,
                source, offset, length.toInt(), finalFrame
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

