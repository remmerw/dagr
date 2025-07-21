package io.github.remmerw.dagr


import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal fun parseLong(data: ByteArray, startIndex: Int): Long {
    require(data.size >= startIndex + 8) { "Wrong packet number parsing" }
    val buffer = Buffer()
    buffer.write(data, startIndex, startIndex + 8)
    return buffer.readLong()
}

@Suppress("ArrayInDataClass")
internal data class Packet(
    val packetNumber: Long,
    val shouldBeAcked: Boolean,
    val bytes: ByteArray
)

internal fun createDataPacket(
    packetNumber: Long, data: ByteArray
): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x03.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(data)

    require(buffer.size <= Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}


internal fun createClosePacket(): Packet {
    val buffer = Buffer()
    buffer.writeByte(0x04.toByte())
    buffer.writeLong(4)
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
    buffer.writeLong(1)
    return Packet(1, true, buffer.readByteArray())
}

