package io.github.remmerw.dagr


interface Acceptor {

    suspend fun accept(connection: Connection)
}