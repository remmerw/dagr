package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer


import kotlin.time.TimeSource


internal data class PacketStatus(
    val packet: Packet,
    val timeSent: TimeSource.Monotonic.ValueTimeMark
)


internal interface Packet {

    /**
     * Returns whether the frame is ack eliciting
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions)
     * "Ack-eliciting packet: A QUIC packet that contains frames other than ACK, PADDING, and CONNECTION_CLOSE."
     *
     * @return true when the frame is ack-eliciting
     */
    fun isAckEliciting(): Boolean
    fun packetNumber(): Long
    fun generatePacketBytes(): Buffer

}

@Suppress("ArrayInDataClass")
data class InitPacket(
    val peerId: PeerId,
    private val packetNumber: Long,
    private val isAckEliciting: Boolean,
    private val frame: ByteArray
) :
    Packet {


    override fun generatePacketBytes(): Buffer {
        val buffer = Buffer()
        buffer.writeByte(0.toByte())
        buffer.write(peerId.hash)
        buffer.writeLong(packetNumber)
        buffer.write(frame)

        require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }

        return buffer
    }

    override fun isAckEliciting(): Boolean {
        return isAckEliciting
    }

    override fun packetNumber(): Long {
        return packetNumber
    }

}

@Suppress("ArrayInDataClass")
data class AppPacket(
    private val packetNumber: Long,
    private val isAckEliciting: Boolean,
    private val frame: ByteArray
) :
    Packet {


    override fun packetNumber(): Long {
        return packetNumber
    }

    override fun isAckEliciting(): Boolean {
        return isAckEliciting
    }

    override fun generatePacketBytes(): Buffer {
        val buffer = Buffer()
        buffer.writeByte(1.toByte())
        buffer.writeLong(packetNumber)
        buffer.write(frame)

        require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
        return buffer

    }
}
