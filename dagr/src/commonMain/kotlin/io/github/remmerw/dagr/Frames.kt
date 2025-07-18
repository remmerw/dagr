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

internal fun createAckFrame(packet: Long): Frame {
    val buffer = Buffer()
    buffer.writeByte(0x02.toByte()) // only AckFrame of payloadType 0x02 is supported
    buffer.writeLong(packet)
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

    val buffer = Buffer()
    buffer.writeByte(frameType.toByte())
    buffer.writeLong(errorCode)

    return Frame(FrameType.ConnectionCloseFrame, buffer.readByteArray())
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