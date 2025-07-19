package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray


import kotlin.time.TimeSource


internal data class PacketStatus(
    val packet: Packet,
    val timeSent: TimeSource.Monotonic.ValueTimeMark
)


@Suppress("ArrayInDataClass")
internal data class Packet(
    val packetNumber: Long,
    val isAckEliciting: Boolean,
    val bytes: ByteArray
) {

    /**
     * Returns whether the frame is ack eliciting
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions)
     * "Ack-eliciting packet: A QUIC packet that contains frames other than ACK, PADDING, and CONNECTION_CLOSE."
     *
     * @return true when the frame is ack-eliciting
     */
    fun generatePacketBytes(): Buffer {
        val buffer = Buffer()
        buffer.write(bytes)
        return buffer
    }
}

internal fun createDataPacket(
    packetNumber: Long, ackEliciting: Boolean,
    source: Source, offset: Long,
    length: Int, fin: Boolean
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x03.toByte())
    buffer.writeLong(packetNumber)
    buffer.writeLong(offset)
    buffer.writeInt(length)
    if (fin) {
        buffer.writeByte(1.toByte())
    } else {
        buffer.writeByte(0.toByte())
    }
    val data = source.readByteArray(length)
    buffer.write(data)

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}


internal fun createVerifyResponsePacket(
    packetNumber: Long,
    ackEliciting: Boolean,
    signature: ByteArray
): Packet {
    require(signature.size == Settings.SIGNATURE_SIZE) { "Invalid size of signature" }
    val buffer = Buffer()
    buffer.writeByte(0x04.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(signature)

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}

internal fun createConnectionClosePacket(
    packetNumber: Long, ackEliciting: Boolean,
    transportError: TransportError = TransportError(
        TransportError.Code.NO_ERROR
    )
): Packet {

    val buffer = Buffer()
    buffer.writeByte(0x05.toByte())
    buffer.writeLong(packetNumber)
    buffer.writeLong(transportError.errorCode())

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}


internal fun createAckPacket(
    packetNumber: Long,
    ackEliciting: Boolean,
    packet: Long
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(packetNumber)
    buffer.writeLong(packet)
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}


internal fun createPingPacket(
    packetNumber: Long,
    ackEliciting: Boolean
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    buffer.writeLong(packetNumber)
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}


internal fun createVerifyRequestPacket(
    peerId: PeerId,
    packetNumber: Long,
    ackEliciting: Boolean,
    token: ByteArray
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x00.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(peerId.hash)
    buffer.write(token)

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, ackEliciting, buffer.readByteArray())
}
