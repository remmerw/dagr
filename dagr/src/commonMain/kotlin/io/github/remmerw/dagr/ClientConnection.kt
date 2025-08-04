package io.github.remmerw.dagr

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.RawSink
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

open class ClientConnection(
    private val selectorManager: SelectorManager,
    private val socket: Socket
) : Connection {

    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()

    fun localPort(): Int {
        return socket.localAddress.port()
    }

    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load() || socket.isClosed


    @OptIn(ExperimentalAtomicApi::class)
    override fun close() {
        if (!closed.exchange(true)) {
            try {
                selectorManager.close()
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
                val count = receiveChannel.readInt()
                receiveChannel.readTo(sink, count.toLong())
                return count
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }
}


@OptIn(InternalAPI::class)
suspend fun ByteReadChannel.readTo(sink: RawSink, byteCount: Long) {

    var remaining = byteCount

    while (remaining > 0 && !isClosedForRead) {
        if (readBuffer.exhausted()) awaitContent()

        val size = minOf(remaining, readBuffer.remaining)
        readBuffer.readTo(sink, size)
        remaining -= size.toInt()
    }
}

