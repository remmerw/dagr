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
    packetNumber: Long,
    source: Source, offset: Int,
    length: Short, fin: Boolean
): Packet {
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
    val data = source.readByteArray(length.toInt())
    buffer.write(data)

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
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

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
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

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
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

    require(buffer.size <= Settings.MAX_PACKAGE_SIZE) { "Invalid packet size" }
    return Packet(packetNumber, true, buffer.readByteArray())
}
