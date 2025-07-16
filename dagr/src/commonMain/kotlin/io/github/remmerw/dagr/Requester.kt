package io.github.remmerw.dagr


interface Requester {
    suspend fun data(stream: Stream, data: ByteArray)

    fun done()
}
