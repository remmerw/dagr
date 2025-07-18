package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray


internal interface FrameReceived {
    /**
     * Represents a connection close frame.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-connection_close-frames)
     */

    @Suppress("ArrayInDataClass")
    data class ConnectionCloseFrame(
        val frameType: Int,
        val triggeringFrameType: Int,
        val reasonPhrase: ByteArray?,
        val errorCode: Long,
        val tlsError: Int
    ) : FrameReceived {
        fun hasTransportError(): Boolean {
            return frameType == 0x1c && errorCode != 0L
        }

        fun hasTlsError(): Boolean {
            return tlsError != -1
        }

        fun hasReasonPhrase(): Boolean {
            return reasonPhrase != null
        }

        fun hasApplicationProtocolError(): Boolean {
            return frameType == 0x1d && errorCode != 0L
        }

        fun hasError(): Boolean {
            return hasTransportError() || hasApplicationProtocolError()
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
     * Represents a number of consecutive padding frames.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-padding-frames)
     *
     *
     * Usually, padding will consist of multiple padding frames, each being exactly one (zero) byte. This class can
     * represent an arbitrary number of consecutive padding frames, by recording padding length.
     */

    data class PaddingFrame(val length: Int) : FrameReceived


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


        fun parseConnectionCloseFrame(type: Byte, buffer: Buffer): ConnectionCloseFrame {
            var triggeringFrameType = 0
            val errorCode = parseLong(buffer)
            if (type.toInt() == 0x1c) {
                triggeringFrameType = parseInt(buffer)
            }
            var reasonPhrase = Settings.BYTES_EMPTY
            val reasonPhraseLength = parseInt(buffer)
            if (reasonPhraseLength > 0) {
                reasonPhrase = buffer.readByteArray(reasonPhraseLength)
            }
            var tlsError = -1
            if (type.toInt() == 0x1c && errorCode >= 0x0100 && errorCode < 0x0200) {
                tlsError = (errorCode - 256).toInt()
            }

            return ConnectionCloseFrame(
                type.toInt(), triggeringFrameType, reasonPhrase,
                errorCode, tlsError
            )
        }


        fun parseAckFrame(type: Byte, buffer: Buffer): AckFrame {
            //  If the frame payloadType is 0x03, ACK frames also contain the cumulative count
            //  of QUIC packets with associated ECN marks received on the connection
            //  up until this point.

            if (type.toInt() == 0x03) {
                debug("AckFrame of payloadType 0x03 is not yet fully supported")
            }

            val packetNumber = buffer.readLong()
            return AckFrame(packetNumber)
        }


        fun parseVerifyRequestFrame(buffer: Buffer): VerifyRequestFrame {
            val token = buffer.readByteArray(Settings.TOKEN_SIZE)
            return VerifyRequestFrame(token)
        }

        fun parseVerifyResponseFrame(buffer: Buffer): VerifyResponseFrame {
            val signature = buffer.readByteArray(Settings.SIGNATURE_SIZE)
            return VerifyResponseFrame(signature)
        }

        /**
         * Strictly speaking, a padding frame consists of one single byte. For convenience,
         * here all subsequent padding bytes are collected in one padding object.
         */
        fun parsePaddingFrame(buffer: Buffer): PaddingFrame {
            var length = 0

            while (buffer.peek().readByte().toInt() == 0) {
                buffer.skip(1)
                length++
            }

            return PaddingFrame(length)
        }


        fun parseDataFrame(type: Byte, buffer: Buffer): DataFrame {
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
                buffer.size.toInt()
            }

            val streamData = buffer.readByteArray(length)

            return DataFrame(isFinal, offset, length, streamData)
        }

    }
}

