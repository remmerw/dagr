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

    @Suppress("ArrayInDataClass")
    data class AckFrame(
        val acknowledgedRanges: LongArray, val largestAcknowledged: Long,
        val ackDelay: Int
    ) : FrameReceived


    /**
     * Represents a data blocked frame.
     * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-data_blocked-frames)
     */

    data class DataBlockedFrame(val streamDataLimit: Long) : FrameReceived


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
        val streamId: Int,
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


        fun parseAckFrame(type: Byte, buffer: Buffer, delayScale: Int): AckFrame {
            //  If the frame payloadType is 0x03, ACK frames also contain the cumulative count
            //  of QUIC packets with associated ECN marks received on the connection
            //  up until this point.

            if (type.toInt() == 0x03) {
                debug("AckFrame of payloadType 0x03 is not yet fully supported")
            }


            // ACK frames are formatted as shown in Figure 25.
            //
            //   ACK Frame {
            //     Type (i) = 0x02..0x03,
            //     Largest Acknowledged (i),
            //     ACK Delay (i),
            //     ACK Range Count (i),
            //     First ACK Range (i),
            //     ACK Range (..) ...,
            //     [ECN Counts (..)],
            //   }


            // A variable length integer is an encoding of 64-bit unsigned
            // integers into between 1 and 9 bytes.

            // A variable-length integer representing the
            // largest packet number the peer is acknowledging; this is usually
            // the largest packet number that the peer has received prior to
            // generating the ACK frame.  Unlike the packet number in the QUIC
            //long or short header, the value in an ACK frame is not truncated.
            val largestAcknowledged = parseLong(buffer)

            // Parse as long to protect to against buggy peers. Convert to int as MAX_INT is large enough to hold the
            // largest ack delay that makes sense (even with an delay exponent of 0, MAX_INT is approx 2147 seconds, approx. half an hour).
            val ackDelay = (parseLong(buffer).toInt() * delayScale) / 1000

            val ackBlockCount = parseLong(buffer).toInt()

            val acknowledgedRanges = LongArray((ackBlockCount + 1) * 2)

            var currentSmallest = largestAcknowledged
            // The smallest of the first block is the largest - (rangeSize - 1).
            val rangeSize = 1 + parseInt(buffer)
            var acknowledgedRangesIndex = 0
            acknowledgedRanges[acknowledgedRangesIndex] = largestAcknowledged
            acknowledgedRangesIndex++
            acknowledgedRanges[acknowledgedRangesIndex] = largestAcknowledged - rangeSize - 1
            currentSmallest -= (rangeSize - 1).toLong()

            repeat(ackBlockCount) {
                // https://tools.ietf.org/html/draft-ietf-quic-transport-17#section-19.3.1:
                // "Each Gap indicates a range of packets that are not being
                //   acknowledged.  The number of packets in the gap is one higher than
                //   the encoded value of the Gap Field."
                val gapSize = parseInt(buffer) + 1
                // https://tools.ietf.org/html/draft-ietf-quic-transport-17#section-19.3.1:
                // "Each ACK Block acknowledges a contiguous range of packets by
                //   indicating the number of acknowledged packets that precede the
                //   largest packet number in that block.  A value of zero indicates that
                //   only the largest packet number is acknowledged."
                val contiguousPacketsPreceding = parseInt(buffer) + 1
                // The largest of the next range is the current smallest - (gap size + 1), because the gap size counts the
                // ones not being present, and we need the first (below) being present.
                // The new current smallest is largest of the next range - (range size - 1)
                //                             == current smallest - (gap size + 1) - (range size - 1)
                //                             == current smallest - gap size - range size
                val largestOfRange = currentSmallest - gapSize - 1

                acknowledgedRangesIndex++
                acknowledgedRanges[acknowledgedRangesIndex] = largestOfRange

                acknowledgedRangesIndex++
                acknowledgedRanges[acknowledgedRangesIndex] =
                    largestOfRange - contiguousPacketsPreceding + 1

                currentSmallest -= (gapSize + contiguousPacketsPreceding).toLong()
            }

            // ECN Counts {
            //     ECT0 Count (i),
            //     ECT1 Count (i),
            //     ECN-CE Count (i),
            //   }
            //
            //                        Figure 27: ECN Count Format
            //
            //   The three ECN Counts are:
            //
            //   ECT0 Count:  A variable-length integer representing the total number
            //      of packets received with the ECT(0) codepoint in the packet number
            //      space of the ACK frame.
            //
            //   ECT1 Count:  A variable-length integer representing the total number
            //      of packets received with the ECT(1) codepoint in the packet number
            //      space of the ACK frame.
            //
            //   CE Count:  A variable-length integer representing the total number of
            //      packets received with the CE codepoint in the packet number space
            //      of the ACK frame.
            //
            //   ECN counts are maintained separately for each packet number space.
            return AckFrame(acknowledgedRanges, largestAcknowledged, ackDelay)
        }


        fun parseDataBlockedFrame(buffer: Buffer): DataBlockedFrame {
            return DataBlockedFrame(parseLong(buffer))
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

            val streamId = parseInt(buffer)

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

            return DataFrame(streamId, isFinal, offset, length, streamData)
        }

    }
}

