package io.github.remmerw.dagr

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeBuffer
import io.ktor.utils.io.writeLong
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

open class ServerConnection(
    private val socket: Socket,
    private val acceptor: Acceptor,
    private val dagr: Dagr
) : Connection {
    @Volatile
    private var lastActive: ValueTimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalAtomicApi::class)
    private val closed = AtomicBoolean(false)
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = false)


    @OptIn(ExperimentalAtomicApi::class)
    val isClosed: Boolean
        get() = closed.load() || socket.isClosed || inactive()

    suspend fun reading() {
        while (!isClosed) {
            try {
                lastActive = TimeSource.Monotonic.markNow()
                val request = receiveChannel.readLong()
                require(request >= 0) { "Invalid request received" }
                val offset = receiveChannel.readLong()
                require(request >= 0) { "Invalid offset received" }

                val data = acceptor.request(request, offset)
                data.source.use { source ->
                    sendChannel.writeLong(data.length)
                    sendChannel.writeBuffer(source)
                    sendChannel.flush()
                }
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
