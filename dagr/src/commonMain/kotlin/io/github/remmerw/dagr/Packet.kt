package io.github.remmerw.dagr


import kotlinx.io.Buffer
import kotlinx.io.readByteArray


internal const val CONNECT = 0x01.toByte()
internal const val ACK = 0x02.toByte()
internal const val REQUEST = 0x03.toByte()
internal const val DATA = 0x04.toByte()

internal fun parseLong(data: ByteArray, startIndex: Int): Long {
    require(data.size >= startIndex + 8) { "Wrong packet number parsing" }
    val buffer = Buffer()
    buffer.write(data, startIndex, startIndex + 8)
    return buffer.readLong()
}


internal fun createAckPacket(
    packet: Long,
    processed: Long,
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(ACK)
    buffer.writeLong(2)
    buffer.writeLong(packet)
    buffer.writeLong(processed)
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


// https://tools.ietf.org/html/draft-ietf-quic-transport-34#section-14
// "A client MUST expand the payload of all UDP datagrams carrying Initial packets to at least the smallest
//  allowed maximum datagram size of 1200 bytes... "
internal fun createConnectPacket(
): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(CONNECT) // 1 byte
    buffer.writeLong(1) // 8 bytes (default packetNumber)
    buffer.write(ByteArray(Settings.PADDING))
    return buffer.readByteArray()
}

