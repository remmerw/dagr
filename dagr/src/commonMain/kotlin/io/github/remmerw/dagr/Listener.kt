package io.github.remmerw.dagr

import kotlinx.io.Buffer

interface Connection : AutoCloseable {
    fun incoming(): Boolean
    fun localPort(): Int
}

interface Listener {
    fun close(connection: Connection)
}

interface Writer {
    suspend fun writeBuffer(buffer: Buffer)
}

interface Acceptor {
    suspend fun request(writer: Writer, request: Long)
}