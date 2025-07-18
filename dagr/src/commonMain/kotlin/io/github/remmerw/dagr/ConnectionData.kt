package io.github.remmerw.dagr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

abstract class ConnectionData() :
    ConnectionFlow() {
    private val streams: MutableMap<Int, Stream> = mutableMapOf()
    private val mutex = Mutex()

    @OptIn(ExperimentalAtomicApi::class)
    private val maxOpenStreamIdUni = AtomicLong(Settings.MAX_STREAMS_UNI.toLong())

    @OptIn(ExperimentalAtomicApi::class)
    private val maxOpenStreamIdBidi = AtomicLong(Settings.MAX_STREAMS_BIDI.toLong())

    @OptIn(ExperimentalAtomicApi::class)
    private val nextStreamId = AtomicInt(0)

    @Suppress("SameParameterValue")
    protected suspend fun createStream(connection: Connection, bidirectional: Boolean): Stream {
        mutex.withLock {
            val streamId = generateStreamId(bidirectional)
            val stream = Stream(connection, streamId, null)
            streams[streamId] = stream
            return stream
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun generateStreamId(bidirectional: Boolean): Int {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-17#section-2.1:
        // "0x0  | Client-Initiated, Bidirectional"
        // "0x1  | Server-Initiated, Bidirectional"
        var id = (nextStreamId.fetchAndIncrement() shl 2)
        if (!bidirectional) {
            // "0x2  | Client-Initiated, Unidirectional |"
            // "0x3  | Server-Initiated, Unidirectional |"
            id += 0x02
        }
        return id
    }

    abstract fun responder(): Responder?

    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun processStreamFrame(
        connection: Connection,
        frame: FrameReceived.DataFrame
    ) {
        val streamId = frame.streamId
        var stream = mutex.withLock { streams[streamId] }
        if (stream != null) {
            stream.add(frame)

        } else {
            if (isRemoteInitiated(streamId)) {
                if (isUni(streamId) && streamId < maxOpenStreamIdUni.load() ||
                    isBidi(streamId) && streamId < maxOpenStreamIdBidi.load()
                ) {
                    stream = Stream(connection, streamId, responder())
                    mutex.withLock {
                        streams[streamId] = stream
                    }
                    stream.add(frame)

                } else {
                    // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-19.11
                    // "An endpoint MUST terminate a connection with a STREAM_LIMIT_ERROR error
                    // if a peer opens more streams than was permitted."
                    throw TransportError(TransportError.Code.STREAM_LIMIT_ERROR)
                }
            } else {
                // happens because of timeout (local created stream -> not remote)
                debug(
                    "Receiving frame for non-existent stream $streamId FRAME $frame"
                )
            }
        }
    }


    abstract fun clientConnection(): Boolean

    private fun isRemoteInitiated(streamId: Int): Boolean {
        return if (clientConnection()) {
            streamId % 2 == (1)
        } else {
            streamId % 2 == (0)
        }
    }

    override suspend fun cleanup() {
        super.cleanup()
        val streams = mutex.withLock { this.streams.values.toList() }
        streams.forEach { stream: Stream -> stream.terminate() }

        mutex.withLock {
            this.streams.clear()
        }
    }

    suspend fun unregisterStream(streamId: Int) {
        mutex.withLock {
            streams.remove(streamId)
        }
    }

    private fun isUni(streamId: Int): Boolean {
        return streamId % 4 > 1
    }

    private fun isBidi(streamId: Int): Boolean {
        return streamId % 4 < 2
    }

}

