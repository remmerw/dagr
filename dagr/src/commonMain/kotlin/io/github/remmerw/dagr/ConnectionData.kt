package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

abstract class ConnectionData() :
    ConnectionFlow() {

    private val frames: MutableList<DataFrame> = mutableListOf() // no concurrency
    private val readingBuffer = Buffer()

    @OptIn(ExperimentalAtomicApi::class)
    private val reset = AtomicBoolean(false)

    private val requestFinish = Semaphore(1, 1)

    private var processedToOffset: Long = 0 // no concurrency

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

                    readingBuffer.write(frame.bytes)

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
                if (responder() == null) {
                    requestFinish.release()
                } else {
                    responder()!!.data(this as Connection, readingBuffer.readByteArray())
                    resetReading()
                }
            }
        }
    }

    fun resetReading() {
        frames.clear()
        processedToOffset = 0
        readingBuffer.clear()
    }


    open suspend fun close() {
        terminate()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal open suspend fun terminate() {
        reset.compareAndSet(expectedValue = false, newValue = true)
        readingBuffer.clear()

        try {
            requestFinish.release()
        } catch (_: Throwable) {
        }
    }

    internal abstract suspend fun sendFrame(level: Level, isAckEliciting: Boolean, frame: ByteArray)

    suspend fun write(buffer: Buffer, autoFlush: Boolean = true) {
        var offset = 0L
        while (!buffer.exhausted()) {
            val read = min(Settings.MAX_DATAGRAM_SIZE, buffer.remaining)
            var finalFrame = false
            if (read < Settings.MAX_DATAGRAM_SIZE && autoFlush) {
                finalFrame = true
            }

            // todo optimize from here
            val byteArray = buffer.readByteArray(read.toInt())

            val dataFrame = createDataFrame(
                offset, byteArray, finalFrame
            )
            offset += read

            sendFrame(Level.APP, true, dataFrame)
        }
    }


    // this is a blocking request with the given timeout [fin is written, no more writing data allowed]
    suspend fun request(timeout: Long, data: Buffer): ByteArray {
        write(data)

        try {
            return withTimeout(timeout * 1000L) {
                requestFinish.acquire()
                response().readByteArray()
            }
        } catch (throwable: Throwable) {
            debug(throwable)
            close()
            return byteArrayOf()
        } finally {
            resetReading()
        }
    }

    private fun response(): Buffer {
        return readingBuffer
    }

    private fun addFrame(frame: DataFrame): Boolean {
        if (frame.offset >= processedToOffset) {
            return frames.add(frame)
        } else {
            debug("Frame not added $frame")
            return false
        }
    }


    abstract fun responder(): Responder?


    internal suspend fun processDataFrame(frame: DataFrame) {

        val added = addFrame(frame)
        if (added) {
            broadcast() // this blocks the parsing of further packets
        }
    }
}

