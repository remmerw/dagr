package io.github.remmerw.dagr

import kotlinx.io.RawSource

interface Connection : AutoCloseable

data class Data(val source: RawSource, val length: Int)

interface Acceptor {
    fun request(request: Long): Data
}