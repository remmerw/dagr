package io.github.remmerw.dagr


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
    sink: Buffer, packetNumber: Long
): Packet {
    val data = sink.readByteArray()
    val buffer = Buffer()
    buffer.writeByte(0x03.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(data)

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}


internal fun createClosePacket(
    transportError: TransportError = TransportError(
        TransportError.Code.NO_ERROR
    )
): Packet {

    val buffer = Buffer()
    buffer.writeByte(0x04.toByte())
    buffer.writeLong(4)
    buffer.writeLong(transportError.errorCode())

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(4, false, buffer.readByteArray())
}


internal fun createAckPacket(
    packet: Long
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(2)
    buffer.writeLong(packet)
    return Packet(2, false, buffer.readByteArray())
}


internal fun createPingPacket(
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    buffer.writeLong(0)
    return Packet(0, true, buffer.readByteArray())
}

