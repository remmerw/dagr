package io.github.remmerw.dagr

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeBuffer
import kotlinx.coroutines.yield
import kotlinx.io.RawSource
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class ServerConnection(
    private val socket: Socket,
    private val acceptor: Acceptor,
    private val dagr: Dagr
) : Writer, Connection {
    @Volatile
    private var lastActive: ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)


    override suspend fun writeBuffer(source: RawSource) {
        lastActive = TimeSource.Monotonic.markNow()
        try {
            sendChannel.writeBuffer(source)
            yield()
        } catch (_: Throwable) {
            close()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load() || socket.isClosed || inactive()

    suspend fun reading() {
        while (!isClosed) {
            try {
                lastActive = TimeSource.Monotonic.markNow()
                val request = receiveChannel.readLong()
                require(request >= 0) { "Invalid read token received" }

                acceptor.request(this, request)

                yield()
            } catch (_: Throwable) {
                close()
                break
            }
        }
    }

    fun inactive(): Boolean {
        return lastActive.elapsedNow().inWholeMilliseconds > (dagr.timeout() * 1000)
    }

    @OptIn(ExperimentalAtomicApi::class)
    override fun close() {
        if (!closed.exchange(true)) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            try {
                dagr.closed(this)
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
    }
}
