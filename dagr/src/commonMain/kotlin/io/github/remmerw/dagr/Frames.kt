package io.github.remmerw.dagr

import kotlinx.io.Source


fun parseCloseFrame(source: Source): CloseFrame {
    val errorCode = source.readLong()

    return CloseFrame(errorCode)
}

fun parseDataFrame(source: Source): DataFrame {
    val offset: Long = source.readLong()
    val length: Int = source.readInt()
    val isFinal: Boolean = source.readByte() == 1.toByte()

    return DataFrame(isFinal, offset, length, source)
}

data class CloseFrame(
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




