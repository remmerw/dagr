package io.github.remmerw.dagr


interface Responder {

    suspend fun handleConnection(connection: Connection)
}