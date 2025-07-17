package io.github.remmerw.dagr

data class Responder(val handler: Handler) {

    suspend fun data(stream: Stream, data: ByteArray) {
        handler.data(stream, data)
    }
}