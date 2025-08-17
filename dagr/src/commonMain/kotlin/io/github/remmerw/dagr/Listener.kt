package io.github.remmerw.dagr

import kotlinx.io.Source

interface Connection : AutoCloseable

data class Data(val source: Source, val length: Long)

interface Acceptor {
    suspend fun request(request: Long, offset: Long): Data
}