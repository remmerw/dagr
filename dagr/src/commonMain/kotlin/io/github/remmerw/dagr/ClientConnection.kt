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

    fun request(request: Long, sink: RawSink): Int {
        lock.withLock {
            try {
                sendChannel.writeLong(request)
                sendChannel.flush()
                val count = receiveChannel.readInt()
                require(count > 0) { "Invalid response $count bytes returned" }
                receiveChannel.readTo(sink, count.toLong())
                return count
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }
}
