package io.github.remmerw.dagr

// Note: a protocol handler is only invoked, when a remote peer initiate a
// stream over an existing connection
interface Handler {

    // is invoked, when the your protocol is requested
    suspend fun protocol(stream: Stream)

    suspend fun data(stream: Stream, data: ByteArray)

}