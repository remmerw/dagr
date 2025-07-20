package io.github.remmerw.dagr


import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer
import kotlinx.io.readByteArray


@Suppress("ArrayInDataClass")
internal data class Packet(
    val packetNumber: Long,
    val shouldBeAcked: Boolean,
    val bytes: ByteArray
) {
    fun generatePacketBytes(): Buffer {
        val buffer = Buffer()
        buffer.write(bytes)
        return buffer
    }
}

internal fun createDataPacket(
    sink: Buffer, packetNumber: Long,
    offset: Int, length: Short,
): Packet {
    val data = sink.readByteArray(length.toInt())
    val fin = length < Settings.MAX_DATAGRAM_SIZE
    val buffer = Buffer()
    buffer.writeByte(0x03.toByte())
    buffer.writeLong(packetNumber)
    buffer.writeInt(offset)
    buffer.writeShort(length)
    if (fin) {
        buffer.writeByte(1.toByte())
    } else {
        buffer.writeByte(0.toByte())
    }
    buffer.write(data)

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}


internal fun createVerifyPacket(
    packetNumber: Long,
    signature: ByteArray
): Packet {
    require(signature.size == Settings.SIGNATURE_SIZE) { "Invalid size of signature" }
    val buffer = Buffer()
    buffer.writeByte(0x04.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(signature)

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}

internal fun createClosePacket(
    packetNumber: Long,
    transportError: TransportError = TransportError(
        TransportError.Code.NO_ERROR
    )
): Packet {

    val buffer = Buffer()
    buffer.writeByte(0x05.toByte())
    buffer.writeLong(packetNumber)
    buffer.writeLong(transportError.errorCode())

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, false, buffer.readByteArray())
}


internal fun createAckPacket(
    packetNumber: Long,
    packet: Long
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(packetNumber)
    buffer.writeLong(packet)
    return Packet(packetNumber, false, buffer.readByteArray())
}


internal fun createPingPacket(
    packetNumber: Long,
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    buffer.writeLong(packetNumber)
    return Packet(packetNumber, true, buffer.readByteArray())
}


internal fun createConnectPacket(
    peerId: PeerId,
    packetNumber: Long,
    token: ByteArray
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x00.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(peerId.hash)
    buffer.write(token)

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}
