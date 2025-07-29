package io.github.remmerw.dagr

import kotlinx.io.Buffer


interface Listener {
    fun close(connection: Connection)
    fun connected(connection: Connection)
}

interface Writer {
    fun writeBuffer(buffer: Buffer)
}

interface Acceptor {
    fun request(writer: Writer, request: Long)
}