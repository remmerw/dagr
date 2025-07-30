package io.github.remmerw.dagr


import kotlinx.io.Buffer
import kotlinx.io.readByteArray


internal const val CONNECT = 0x01.toByte()
internal const val ACK = 0x02.toByte()
internal const val REQUEST = 0x03.toByte()
internal const val DATA = 0x04.toByte()
internal const val CLOSE = 0x05.toByte()

internal fun parseLong(data: ByteArray, startIndex: Int): Long {
    require(data.size >= startIndex + 8) { "Wrong packet number parsing" }
    val buffer = Buffer()
    buffer.write(data, startIndex, startIndex + 8)
    return buffer.readLong()
}


internal fun createClosePacket(): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(CLOSE)
    buffer.writeLong(5)
    return buffer.readByteArray()
}


internal fun createAckPacket(
    packet: Long
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(ACK)
    buffer.writeLong(2)
    buffer.writeLong(packet)
    return buffer.readByteArray()
}


internal fun createRequestPacket(
    packetNumber: Long,
    request: Long
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(REQUEST)
    buffer.writeLong(packetNumber)
    buffer.writeLong(request)
    return buffer.readByteArray()
}

internal fun createConnectPacket(
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(CONNECT)
    buffer.writeLong(1)
    return buffer.readByteArray()
}

