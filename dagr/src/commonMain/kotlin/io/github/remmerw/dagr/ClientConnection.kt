package io.github.remmerw.dagr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.RawSink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.net.Socket
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

open class ClientConnection(
    private val socket: Socket
) : Connection {

    private val receiveChannel = socket.inputStream.asSource().buffered()
    private val sendChannel = socket.outputStream.asSink().buffered()

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()


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

    suspend fun request(request: Long, sink: RawSink): Int {
        mutex.withLock {
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
