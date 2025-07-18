package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
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

fun parseDataFrame(type: Byte, buffer: Source): DataFrame {
    val withOffset = ((type.toInt() and 0x04) == 0x04)
    val withLength = ((type.toInt() and 0x02) == 0x02)
    val isFinal = ((type.toInt() and 0x01) == 0x01)

    var offset: Long = 0
    if (withOffset) {
        offset = parseLong(buffer)
    }
    val length = if (withLength) {
        parseInt(buffer)
    } else {
        buffer.remaining.toInt()
    }

    val streamData = buffer.readByteArray(length)

    return DataFrame(isFinal, offset, length, streamData)
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


@Suppress("ArrayInDataClass")
data class DataFrame(
    val isFinal: Boolean,
    val offset: Long,
    val length: Int,
    val bytes: ByteArray
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


internal fun frameLength(offset: Long, length: Int): Int {
    return (1 // frame payloadType
            + bytesNeeded(offset)
            + bytesNeeded(length.toLong())
            + length)
}

internal fun createDataFrame(
    offset: Long, applicationData: ByteArray, fin: Boolean
): ByteArray {
    val frameLength = frameLength(offset, applicationData.size)

    val buffer = Buffer()
    val baseType = 0x08.toByte()
    var frameType =
        (baseType.toInt() or 0x04 or 0x02).toByte() // OFF-bit, LEN-bit, (no) FIN-bit
    if (fin) {
        frameType = (frameType.toInt() or 0x01).toByte()
    }
    buffer.writeByte(frameType)
    encode(offset, buffer)
    encode(applicationData.size, buffer)
    buffer.write(applicationData)
    require(buffer.size.toInt() == frameLength)

    return buffer.readByteArray()
}

/**
 * Represents a connection close frame.
 * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-connection_close-frames)
 */
/**
 * Creates a connection close frame for a normal connection close without errors
 */
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

