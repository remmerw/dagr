package io.github.remmerw.dagr

import kotlinx.io.Buffer

interface Connection : AutoCloseable

interface Writer {
    suspend fun writeBuffer(buffer: Buffer)
}

interface Acceptor {
    suspend fun request(writer: Writer, request: Long)
}