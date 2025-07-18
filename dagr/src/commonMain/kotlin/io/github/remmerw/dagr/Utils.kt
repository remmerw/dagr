package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray

internal fun longToBytes(value: Long): ByteArray {
    var x = value
    val length = Long.SIZE_BYTES
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[length - i - 1] = (x and 0xffL).toByte()
        x = x shr 8
    }
    return bytes
}

internal fun intToBytes(value: Int): ByteArray {
    var x = value
    val length = Int.SIZE_BYTES
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[length - i - 1] = (x and 0xff).toByte()
        x = x shr 8
    }
    return bytes
}


internal fun parseInt(buffer: Source): Int {
    val value = parseLong(buffer)
    if (value <= Int.MAX_VALUE) {
        return value.toInt()
    } else {
        // If value can be larger than int, parseLong should have called.
        error("value to large for Java int")
    }
}


internal fun parseLong(buffer: Source): Long {
    if (buffer.remaining < 1) {
        error("Invalid size encoding")
    }

    val value: Long
    val firstLengthByte = buffer.readByte()
    when ((firstLengthByte.toInt() and 0xc0) shr 6) {
        0 -> value = firstLengthByte.toLong()
        1 -> {
            if (buffer.remaining < 1) {
                error("Invalid size encoding")
            }
            val data = Buffer()
            data.writeByte(firstLengthByte)
            data.writeByte(buffer.readByte())

            value = (data.readShort().toInt() and 0x3fff).toLong()
        }

        2 -> {
            if (buffer.remaining < 3) {
                error("Invalid size encoding")
            }
            val data = Buffer()
            data.writeByte(firstLengthByte)
            data.write(buffer.readByteArray(3))
            value = (data.readInt() and 0x3fffffff).toLong()
        }

        3 -> {
            if (buffer.remaining < 7) {
                error("Invalid size encoding")
            }
            val data = Buffer()
            data.writeByte(firstLengthByte)
            data.write(buffer.readByteArray(7))
            value = data.readLong() and 0x3fffffffffffffffL
        }

        else ->  // Impossible, just to satisfy the compiler
            error("Not handled size encoding")
    }
    return value
}

internal fun bytesNeeded(value: Long): Int {
    return if (value <= 63) {
        1
    } else if (value <= 16383) {
        2
    } else if (value <= 1073741823) {
        4
    } else {
        8
    }
}

internal fun encode(value: Int, buffer: Buffer): Int {
    // https://tools.ietf.org/html/draft-ietf-quic-transport-20#section-16
    // | 2Bit | Length | Usable Bits | Range                 |
    // +------+--------+-------------+-----------------------+
    // | 00   | 1      | 6           | 0-63                  |
    // | 01   | 2      | 14          | 0-16383               |
    // | 10   | 4      | 30          | 0-1073741823          |
    if (value <= 63) {
        buffer.writeByte(value.toByte())
        return 1
    } else if (value <= 16383) {
        buffer.writeByte(((value / 256) or 0x40).toByte())
        buffer.writeByte((value % 256).toByte())
        return 2
    } else if (value <= 1073741823) {
        val bytes = intToBytes(value)
        bytes[0] = (bytes[0].toInt() or 0x80.toByte().toInt()).toByte()
        buffer.write(bytes)
        return 4
    } else {
        val bytes = longToBytes(value.toLong())
        bytes[0] = (buffer[0].toInt() or 0xc0.toByte().toInt()).toByte()
        buffer.write(bytes)
        return 8
    }
}

internal fun encode(value: Long, buffer: Buffer): Int {
    if (value <= Int.MAX_VALUE) {
        return encode(value.toInt(), buffer)
    } else if (value <= 4611686018427387903L) {
        val bytes = longToBytes(value)
        bytes[0] = (buffer[0].toInt() or 0xc0.toByte().toInt()).toByte()
        buffer.write(bytes)
        return 8
    } else {
        throw IllegalStateException("value cannot be encoded in variable-length integer")
    }
}

