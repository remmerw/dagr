package io.github.remmerw.dagr


interface Listener {
    fun close(connection: Connection)
}
