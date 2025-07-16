package io.github.remmerw.dagr

internal data class State(private val responder: Responder) : StreamState() {

    override suspend fun accept(stream: Stream, frame: ByteArray) {
        if (frame.isNotEmpty()) {
            responder.data(stream, frame)
        }
    }
}