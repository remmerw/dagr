package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentSet

interface Terminate {
    fun terminate(connection: Connection)
    suspend fun shutdown()

    fun connections(): Set<Connection>
}

class Connector() : Terminate {
    private val connections: MutableSet<Connection> = ConcurrentSet()


    override fun connections(): Set<Connection> {
        return connections.toSet()
    }

    override suspend fun shutdown() {
        connections.forEach { connection: Connection -> connection.close() }
        connections.clear()

    }

    fun addConnection(connection: Connection) {
        require(connection.isConnected) { "Connection not connected" }
        connections.add(connection)

    }

    override fun terminate(connection: Connection) {
        connections.remove(connection)
    }
}
