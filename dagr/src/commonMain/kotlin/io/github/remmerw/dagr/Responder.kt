package io.github.remmerw.dagr

import kotlinx.io.Buffer

interface Responder {

    suspend fun data(connection: Connection, buffer: Buffer)
}