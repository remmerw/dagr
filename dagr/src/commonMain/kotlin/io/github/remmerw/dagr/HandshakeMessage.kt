package io.github.remmerw.dagr


internal interface HandshakeMessage {
    val type: HandshakeType
    val bytes: ByteArray
}
