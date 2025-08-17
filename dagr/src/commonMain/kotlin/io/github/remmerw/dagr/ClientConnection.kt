package io.github.remmerw.dagr

import kotlinx.io.RawSink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.withLock
import kotlin.math.min

open class ClientConnection(
    private val socket: Socket
) : Connection {

    private val receiveChannel = socket.inputStream.asSource().buffered()
    private val sendChannel = socket.outputStream.asSink().buffered()

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()


    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load() || socket.isClosed


    @OptIn(ExperimentalAtomicApi::class)
    override fun close() {
        if (!closed.exchange(true)) {
            try {
                receiveChannel.close()
            } catch (_: Throwable) {
            }
            try {
                sendChannel.close()
            } catch (_: Throwable) {
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun request(request: Long, offset: Long, sink: RawSink, progress: (Float) -> Unit = {}): Long {
        lock.withLock {
            try {
                sendChannel.writeLong(request)
                sendChannel.writeLong(offset)
                sendChannel.flush()
                val count = receiveChannel.readLong()
                var totalRead = 0L
                var remember = 0
                var rest: Long

                require(count > 0) { "Invalid response $count bytes returned" }
                do {
                    rest = count - totalRead
                    val read = min(rest, 8192)

                    receiveChannel.readTo(sink, read)

                    totalRead += read

                    if (totalRead > 0) {
                        val percent = ((totalRead * 100.0f) / count).toInt()
                        if (percent > remember) {
                            remember = percent
                            progress.invoke(percent / 100.0f)
                        }
                    }

                } while (rest > 0)
                return count
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }
}
