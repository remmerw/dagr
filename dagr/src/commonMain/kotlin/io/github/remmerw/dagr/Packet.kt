package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer

internal interface Packet {
    fun packetNumber(): Long
    fun level(): Level
    fun frames(): List<Frame>
    fun estimateLength(): Int

    fun framesLength(): Int {
        var sum = 0
        for (frame in frames()) {
            sum += frame.frameLength()
        }
        return sum
    }

    fun generatePacketBytes(): Buffer

    val isAckOnly: Boolean
        get() {
            for ((frameType) in frames()) {
                if (frameType != FrameType.AckFrame) {
                    return false
                }
            }
            return true
        }


    data class InitPacket(
        val peerId: PeerId,
        val packetNumber: Long,
        val frames: List<Frame>
    ) :
        Packet {


        override fun generatePacketBytes(): Buffer {
            val buffer = Buffer()
            buffer.writeByte(0.toByte())
            buffer.write(peerId.hash)
            buffer.writeLong(packetNumber)

            frames.forEach { frame ->
                buffer.write(frame.frameBytes)
            }
            require(buffer.size < Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }

            return buffer
        }

        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun level(): Level {
            return Level.INIT
        }

        override fun frames(): List<Frame> {
            return frames
        }

        override fun estimateLength(): Int {
            val payloadLength = framesLength()
            return (1 + peerId.hash.size + Long.SIZE_BYTES + payloadLength)
        }

    }


    data class AppPacket(val packetNumber: Long, val frames: List<Frame>) :
        Packet {
        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun level(): Level {
            return Level.APP
        }

        override fun frames(): List<Frame> {
            return frames
        }

        override fun estimateLength(): Int {
            val payloadLength = framesLength()
            return (1 + Long.SIZE_BYTES + payloadLength)
        }

        override fun generatePacketBytes(): Buffer {
            val buffer = Buffer()
            buffer.writeByte(1.toByte())
            buffer.writeLong(packetNumber)

            frames.forEach { frame ->
                buffer.write(frame.frameBytes)
            }
            require(buffer.size < Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
            return buffer

        }

    }
}

// https://tools.ietf.org/html/draft-ietf-quic-recovery-33#section-2
// "Packets that contain ack-eliciting frames elicit an ACK from the receiver (...)
// and are called ack-eliciting packets."
internal fun isAckEliciting(packet: Packet): Boolean {
    for (frame in packet.frames()) {
        if (isAckEliciting(frame)) {
            return true
        }
    }
    return false
}

internal fun isInflightPacket(packet: Packet): Boolean {
    for (frame in packet.frames()) {
        if (isAckEliciting(frame) || frame.frameType == FrameType.PaddingFrame) {
            return true
        }
    }
    return false
}