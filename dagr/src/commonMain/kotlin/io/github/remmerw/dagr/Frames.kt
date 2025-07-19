package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray


fun parseConnectionCloseFrame(source: Source): ConnectionCloseFrame {
    val errorCode = source.readLong()

    return ConnectionCloseFrame(errorCode)
}


fun parseVerifyRequestFrame(buffer: Source): VerifyRequestFrame {
    val token = buffer.readByteArray(Settings.TOKEN_SIZE)
    return VerifyRequestFrame(token)
}

fun parseVerifyResponseFrame(buffer: Source): VerifyResponseFrame {
    val signature = buffer.readByteArray(Settings.SIGNATURE_SIZE)
    return VerifyResponseFrame(signature)
}

fun parseDataFrame(source: Source): DataFrame {
    val offset: Long = source.readLong()
    val length: Int = source.readInt()
    val isFinal: Boolean = source.readByte() == 1.toByte()

    return DataFrame(isFinal, offset, length, source)
}

data class ConnectionCloseFrame(
    val errorCode: Long
) {

    fun hasError(): Boolean {
        return errorCode != 0L
    }
}

@Suppress("ArrayInDataClass")
data class VerifyRequestFrame(
    val token: ByteArray
)


@Suppress("ArrayInDataClass")
data class VerifyResponseFrame(
    val signature: ByteArray
)


data class DataFrame(
    val isFinal: Boolean,
    val offset: Long,
    val length: Int,
    val source: Source
) :
    Comparable<DataFrame> {
    override fun compareTo(other: DataFrame): Int {
        return if (this.offset == other.offset) {
            length.compareTo(other.length)
        } else {
            offset.compareTo(other.offset)
        }
    }

    fun offsetLength(): Long {
        return offset + length
    }
}


internal val PING = createPingFrame()

internal fun createAckFrame(packet: Long): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(packet)
    return buffer.readByteArray()
}

internal fun createConnectionCloseFrame(
    transportError: TransportError = TransportError(
        TransportError.Code.NO_ERROR
    )
): ByteArray {
    val frameType = 0x1c
    val errorCode = transportError.errorCode()

    val buffer = Buffer()
    buffer.writeByte(frameType.toByte())
    buffer.writeLong(errorCode)

    return buffer.readByteArray()
}


private fun createPingFrame(): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    return buffer.readByteArray()
}

internal fun createVerifyRequestFrame(token: ByteArray): ByteArray {
    require(token.size == Settings.TOKEN_SIZE) { "Invalid token size" }
    val buffer = Buffer()
    buffer.writeByte(0x18.toByte())
    buffer.write(token)
    return buffer.readByteArray()
}

internal fun createVerifyResponseFrame(signature: ByteArray): ByteArray {
    require(signature.size == Settings.SIGNATURE_SIZE) { "Invalid size of signature" }
    val buffer = Buffer()
    buffer.writeByte(0x19.toByte())
    buffer.write(signature)
    return buffer.readByteArray()
}

