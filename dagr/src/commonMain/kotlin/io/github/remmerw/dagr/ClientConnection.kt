package io.github.remmerw.dagr

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.RawSink
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

open class ClientConnection(
    private val socket: Socket,
    private val listener: Listener
) : Connection {

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)

    private val mutex = Mutex()

    override fun localPort(): Int {
        return socket.localAddress.port()
    }

    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load()


    @OptIn(ExperimentalAtomicApi::class)
    override fun close() {
        if (!closed.exchange(true)) {
            try {
                socket.close()
            } catch (throwable: Throwable) {
                debug(throwable)
            }
            try {
                listener.close(this)
            } catch (throwable: Throwable) {
                debug(throwable)
            }
            try {
                receiveChannel.cancel()
            } catch (throwable: Throwable) {
                debug(throwable)
            }
            try {
                sendChannel.close(Exception("Connection closed"))
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
    }

    override fun incoming(): Boolean {
        return false
    }


    suspend fun request(request: Long, sink: RawSink): Int {
        mutex.withLock {
            try {
                sendChannel.writeLong(request)
                val count = receiveChannel.readInt()
                val source = receiveChannel.readBuffer(count)
                sink.write(source, source.size)
                return count
            } catch (throwable: Throwable) {
                close()
                throw throwable
            }
        }
    }
}
