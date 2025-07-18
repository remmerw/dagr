package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
import kotlinx.io.Source
import kotlinx.io.readByteArray


internal interface FrameReceived {
    /**
     * Represents a connection close frame.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-connection_close-frames)
     */

    data class ConnectionCloseFrame(
        val errorCode: Long
    ) : FrameReceived {

        fun hasError(): Boolean {
            return errorCode != 0L
        }
    }

    // https://www.rfc-editor.org/rfc/rfc9000.html#name-transport-parameter-definit
    // "...  a default value of 3 is assumed (indicating a multiplier of 8)."

    data class AckFrame(val packetNumber: Long) : FrameReceived


    /**
     * Represents a new connection id frame.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-new_connection_id-frames)
     */

    @Suppress("ArrayInDataClass")
    data class VerifyRequestFrame(
        val token: ByteArray
    ) : FrameReceived


    @Suppress("ArrayInDataClass")
    data class VerifyResponseFrame(
        val signature: ByteArray
    ) : FrameReceived


    /**
     * Represents a ping frame.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-ping-frames)
     */
    @Suppress("unused")
    class PingFrame : FrameReceived


    @Suppress("ArrayInDataClass")
    data class DataFrame(
        val isFinal: Boolean,
        val offset: Long,
        val length: Int,
        val bytes: ByteArray
    ) :
        FrameReceived, Comparable<DataFrame> {
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

    companion object {


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

    }
}

