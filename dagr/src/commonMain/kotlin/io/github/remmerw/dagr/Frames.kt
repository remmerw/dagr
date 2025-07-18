package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

// https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions
// https://tools.ietf.org/html/draft-ietf-quic-recovery-33#section-2
// "All frames other than ACK, PADDING, and CONNECTION_CLOSE are considered ack-eliciting."
/**
 * Represents a ping frame.
 * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-ping-frames)
 */
internal val PING: Frame = createPingFrame()

/**
 * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-ack-frames)
 * Creates an AckFrame given a (sorted, non-adjacent) list of ranges and an ack delay.
 * ackDelay is value in milliseconds
 */
internal fun createAckFrame(packets: List<Long>, ackDelay: Int): Frame {
    val ackRanges: MutableList<ByteArray> = arrayListOf()
    var ackRangesBytesSize = 0
    var to: Long
    var ackRangesSize = 0

    var largestAcknowledgedTo: Long = 0
    var largestAcknowledgedFrom: Long = 0
    var smallest: Long = 0
    var i = packets.size - 1
    while (i >= 0) {
        to = packets[i]
        // now get the to
        var from = to
        var check = true
        while (check) {
            val index = i - 1
            if (index >= 0) {
                val value = packets[index]
                if (value == from - 1) {
                    i--
                    from = value
                } else {
                    check = false
                }
            } else {
                check = false
            }
        }

        if (ackRangesSize == 0) {
            largestAcknowledgedTo = to
            largestAcknowledgedFrom = from
        } else {
            // https://www.rfc-editor.org/rfc/rfc9000.html#name-ack-frames
            // "Gap: A variable-length integer indicating the number of contiguous unacknowledged packets preceding the
            //  packet number one lower than the smallest in the preceding ACK Range."
            val gap =
                (smallest - to - 2).toInt() // e.g. 9..9, 5..4 => un-acked: 8, 7, 6; gap: 2
            // "ACK Range Length: A variable-length integer indicating the number of contiguous acknowledged packets
            //  preceding the largest packet number, as determined by the preceding Gap."
            //     ACK Range (..) ...,
            val distance = to - from

            val capacity =
                bytesNeeded(gap.toLong()) +
                        bytesNeeded(distance)
            val bufferAckRange = Buffer()
            encode(gap, bufferAckRange)
            encode(distance, bufferAckRange)
            require(bufferAckRange.size <= capacity)
            val arrayAckRange = bufferAckRange.readByteArray()
            ackRangesBytesSize += arrayAckRange.size
            ackRanges.add(arrayAckRange)
        }
        smallest = from
        ackRangesSize++

        i--
    }


    // ackDelay : A variable-length integer encoding the acknowledgment
    // delay in microseconds; see Section 13.2.5.  It is decoded by
    // multiplying the value in the field by 2 to the power of the
    // ack_delay_exponent transport parameter sent by the sender of the
    // ACK frame; see Section 18.2.  Compared to simply expressing the
    // delay as an integer, this encoding allows for a larger range of
    // values within the same number of bytes, at the cost of lower
    // resolution.
    val encodedAckDelay = ackDelay * 1000L / Settings.ACK_DELAY_SCALE


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
    val firstAckRange = largestAcknowledgedTo - largestAcknowledgedFrom
    val bufferSize = 1 +
            bytesNeeded(largestAcknowledgedTo) +
            bytesNeeded(encodedAckDelay) +
            bytesNeeded(ackRanges.size.toLong()) +
            bytesNeeded(firstAckRange) + ackRangesBytesSize

    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    encode(largestAcknowledgedTo, buffer) // Largest Acknowledged (i),
    encode(encodedAckDelay, buffer) //  ACK Delay (i),
    encode(ackRanges.size, buffer) // ACK Range Count (i)
    encode(firstAckRange, buffer) // First ACK Range (i)
    for (ackRange in ackRanges) {
        buffer.write(ackRange) //     ACK Range (..) ...,
    }

    require(buffer.size <= bufferSize)

    return Frame(FrameType.AckFrame, buffer.readByteArray())
}


internal fun frameLength(offset: Long, length: Int): Int {
    return (1 // frame payloadType
            + bytesNeeded(offset)
            + bytesNeeded(length.toLong())
            + length)
}

internal fun createDataFrame(
    offset: Long, applicationData: ByteArray, fin: Boolean
): Frame {
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

    return Frame(FrameType.DataFrame, buffer.readByteArray())
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
): Frame {
    val frameType = 0x1c
    val errorCode = transportError.errorCode()

    val length = 1 +
            bytesNeeded(errorCode) +
            bytesNeeded(0) +
            bytesNeeded(0)

    val buffer = Buffer()

    buffer.writeByte(frameType.toByte())
    encode(errorCode, buffer)
    encode(0, buffer)
    encode(0, buffer)
    require(buffer.size.toInt() == length)

    return Frame(FrameType.ConnectionCloseFrame, buffer.readByteArray())
}

/**
 * Represents a data blocked frame.
 * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-data_blocked-frames)
 */
internal fun createDataBlockedFrame(streamDataLimit: Long): Frame {
    val length = 1 + bytesNeeded(streamDataLimit)
    val buffer = Buffer()
    buffer.writeByte(0x14.toByte())
    encode(streamDataLimit, buffer)
    require(buffer.size.toInt() == length)
    return Frame(FrameType.DataBlockedFrame, buffer.readByteArray())
}


internal fun createMaxDataFrame(maxData: Long): Frame {
    val length = 1 + bytesNeeded(maxData)
    val buffer = Buffer()
    buffer.writeByte(0x10.toByte())
    encode(maxData, buffer)
    require(buffer.size.toInt() == length)
    return Frame(FrameType.MaxDataFrame, buffer.readByteArray())
}

private fun createPingFrame(): Frame {
    val buffer = Buffer()
    buffer.writeByte(0x01.toByte())
    return Frame(FrameType.PingFrame, buffer.readByteArray())
}

internal fun createVerifyRequestFrame(token: ByteArray): Frame {
    require(token.size == Settings.TOKEN_SIZE) { "Invalid token size" }
    val buffer = Buffer()
    buffer.writeByte(0x18.toByte())
    buffer.write(token)
    return Frame(FrameType.VerifyRequestFrame, buffer.readByteArray())
}

internal fun createVerifyResponseFrame(signature: ByteArray): Frame {
    require(signature.size == Settings.SIGNATURE_SIZE) { "Invalid size of signature" }
    val buffer = Buffer()
    buffer.writeByte(0x19.toByte())
    buffer.write(signature)
    return Frame(FrameType.VerifyResponseFrame, buffer.readByteArray())
}


/**
 * Returns whether the frame is ack eliciting
 * [...](https://www.rfc-editor.org/rfc/rfc9000.html#name-terms-and-definitions)
 * "Ack-eliciting packet: A QUIC packet that contains frames other than ACK, PADDING, and CONNECTION_CLOSE."
 *
 * @return true when the frame is ack-eliciting
 */

internal fun isAckEliciting(frame: Frame): Boolean {
    return when (frame.frameType) {
        FrameType.AckFrame, FrameType.ConnectionCloseFrame -> false
        else -> true
    }
}