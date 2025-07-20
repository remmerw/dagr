package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentSet

interface Listener {
    fun close(connection: Connection)
}

class Connector() : Listener {
    private val connections: MutableSet<Connection> = ConcurrentSet()


    fun connections(): Set<Connection> {
        return connections.toSet()
    }

    suspend fun shutdown() {
        connections.forEach { connection: Connection -> connection.close() }
        connections.clear()

    }

    fun addConnection(connection: Connection) {
        require(connection.isConnected) { "Connection not connected" }
        connections.add(connection)

    }

    override fun close(connection: Connection) {
        connections.remove(connection)
    }
}
