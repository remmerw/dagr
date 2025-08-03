package io.github.remmerw.dagr

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeBuffer
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class ServerConnection(
    private val socket: Socket,
    private val acceptor: Acceptor,
    private val listener: Listener
) : Writer, Connection {
    @Volatile
    private var lastActive: ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)


    override suspend fun writeBuffer(buffer: Buffer) {
        lastActive = TimeSource.Monotonic.markNow()
        try {
            sendChannel.writeBuffer(buffer)
            yield()
        } catch (throwable: Throwable) {
            println("Connection.writeBuffer " + throwable.message)
            close()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load()

    suspend fun reading() {
        lastActive = TimeSource.Monotonic.markNow()

        while (!isClosed) {
            try {
                val request = receiveChannel.readLong()
                require(request >= 0) { "Invalid read token received" }

                acceptor.request(this, request)

                yield()
            } catch (throwable: Throwable) {
                println("Connection.reading " + throwable.message)
                close()
                break
            }
        }
    }


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

    override fun localPort(): Int {
        return socket.localAddress.port()
    }

    override fun incoming(): Boolean {
        return true
    }
}
