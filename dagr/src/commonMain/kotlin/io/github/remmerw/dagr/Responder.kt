package io.github.remmerw.dagr


interface Responder {

    suspend fun data(connection: Connection, data: ByteArray)
}