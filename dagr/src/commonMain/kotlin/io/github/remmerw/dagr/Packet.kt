package io.github.remmerw.dagr


import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal fun parseLong(data: ByteArray, startIndex: Int): Long {
    require(data.size >= startIndex + 8) { "Wrong packet number parsing" }
    val buffer = Buffer()
    buffer.write(data, startIndex, startIndex + 8)
    return buffer.readLong()
}


internal fun createDataPacket(
    packetNumber: Long, data: ByteArray
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x03.toByte())
    buffer.writeLong(packetNumber)
    buffer.write(data)
    return buffer.readByteArray()
}


internal fun createClosePacket(): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x04.toByte())
    buffer.writeLong(4)
    return buffer.readByteArray()
}


internal fun createAckPacket(
    packet: Long
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(2)
    buffer.writeLong(packet)
    return buffer.readByteArray()
}


internal fun createPingPacket(
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    buffer.writeLong(1)
    return buffer.readByteArray()
}

