package io.github.remmerw.dagr

import kotlinx.io.RawSource

interface Connection : AutoCloseable

data class Data(val source: RawSource, val length: Long)

interface Acceptor {
    suspend fun request(request: Long, offset: Long): Data
}