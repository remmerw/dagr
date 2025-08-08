package io.github.remmerw.dagr

import kotlinx.io.RawSource

interface Connection : AutoCloseable

interface Writer {
    suspend fun writeBuffer(source: RawSource)
}

interface Acceptor {
    suspend fun request(writer: Writer, request: Long)
}