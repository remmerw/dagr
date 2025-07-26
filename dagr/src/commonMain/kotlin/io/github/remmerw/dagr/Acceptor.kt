package io.github.remmerw.dagr


interface Acceptor {
    fun accept(connection: Connection)
}