package io.github.remmerw.dagr

interface Handler {

    suspend fun data(stream: Stream, data: ByteArray)

}