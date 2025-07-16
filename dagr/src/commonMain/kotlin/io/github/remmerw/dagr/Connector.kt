package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentSet


class Connector() {
    private val connections: MutableSet<DagrClient> = ConcurrentSet()


    fun connections(): Set<DagrClient> {
        return connections.toSet()
    }

    suspend fun shutdown() {
        connections.forEach { connection: DagrClient -> connection.close() }
        connections.clear()

    }

    fun addConnection(connection: DagrClient) {
        require(connection.isConnected) { "Connection not connected" }
        connections.add(connection)

    }

    fun removeConnection(connection: DagrClient) {
        connections.remove(connection)
    }
}
