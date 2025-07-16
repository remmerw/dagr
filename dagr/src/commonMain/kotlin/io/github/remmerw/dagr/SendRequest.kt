package io.github.remmerw.dagr


internal interface FrameSupplier {
    suspend fun nextFrame(maxSize: Int): Frame?
}

internal data class SendRequest(val estimatedSize: Int, val frameSupplier: FrameSupplier)

