package io.github.remmerw.dagr

import kotlinx.io.Source


fun parseCloseFrame(source: Source): CloseFrame {
    val errorCode = source.readLong()

    return CloseFrame(errorCode)
}


data class CloseFrame(
    val errorCode: Long
) {

    fun hasError(): Boolean {
        return errorCode != 0L
    }
}






