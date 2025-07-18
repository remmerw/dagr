package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer


import kotlin.time.TimeSource


internal data class PacketStatus(
    val packet: Packet,
    val timeSent: TimeSource.Monotonic.ValueTimeMark
)


internal interface Packet {
    fun packetNumber(): Long
    fun frames(): List<Frame>
    fun generatePacketBytes(): Buffer

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
            require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }

            return buffer
        }

        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun frames(): List<Frame> {
            return frames
        }

    }


    data class AppPacket(val packetNumber: Long, val frames: List<Frame>) :
        Packet {
        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun frames(): List<Frame> {
            return frames
        }

        override fun generatePacketBytes(): Buffer {
            val buffer = Buffer()
            buffer.writeByte(1.toByte())
            buffer.writeLong(packetNumber)

            frames.forEach { frame ->
                buffer.write(frame.frameBytes)
            }
            require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
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
