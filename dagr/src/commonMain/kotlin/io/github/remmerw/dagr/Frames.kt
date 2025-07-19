package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray


fun parseConnectionCloseFrame(source: Source): ConnectionCloseFrame {
    val errorCode = source.readLong()

    return ConnectionCloseFrame(errorCode)
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


private fun createPingFrame(): ByteArray {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    return buffer.readByteArray()
}


