package io.github.remmerw.dagr

import kotlinx.io.Buffer

interface Responder {

    suspend fun data(stream: Stream, buffer: Buffer)
}