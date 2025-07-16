package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

val UNDEFINED_TOKEN: ByteArray = byteArrayOf(0x00.toByte())

internal interface Packet {
    fun packetNumber(): Long

    fun level(): Level

    fun frames(): List<Frame>

    /**
     * Estimates what the length of this packet will be after it has been encrypted.
     * The returned length must be less then or equal the actual length after encryption.
     * Length estimates are used when preparing packets for sending, where certain limits must
     * be met (e.g. congestion control, max datagram size, ...).
     */
    fun estimateLength(): Int

    fun framesLength(): Int {
        var sum = 0
        for (frame in frames()) {
            sum += frame.frameLength()
        }
        return sum
    }

    fun generatePacketBytes(): Buffer

    val isAckOnly: Boolean
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-33#section-2
        get() {
            for ((frameType) in frames()) {
                if (frameType != FrameType.AckFrame) {
                    return false
                }
            }
            return true
        }


    data class InitPacket(
        val dcid: Number, val scid: Number,
        val frames: List<Frame>, val packetNumber: Long
    ) : Packet {


        override fun generatePacketBytes(): Buffer {
            val frameHeader = generateFrameHeaderInvariant()
            val frameBytes = PacketService.generatePayloadBytes(frames)

            val buffer = Buffer()
            buffer.write(frameHeader)
            buffer.writeLong(packetNumber)
            buffer.write(frameBytes)
            return buffer
        }

        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun level(): Level {
            return Level.INIT
        }

        override fun frames(): List<Frame> {
            return frames
        }

        override fun estimateLength(): Int {
            val payloadLength = framesLength()
            return (1
                    + 4
                    + 1 + lengthNumber(dcid)
                    + 1 + Int.SIZE_BYTES
                    + (if (payloadLength + 1 > 63) 2 else 1)
                    + 1 // packet number length: will usually be just 1, actual value cannot be
                    // computed until packet number is known
                    + payloadLength // https://tools.ietf.org/html/draft-ietf-quic-tls-27#section-5.4.2
                    // "The ciphersuites defined in [TLS13] - (...) - have 16-byte expansions..."
                    + 16)
        }

        private fun generateFrameHeaderInvariant(): ByteArray {
            // https://www.rfc-editor.org/rfc/rfc9000.html#name-long-header-packets
            // "Long Header Packet {
            //    Header Form (1) = 1,
            //    Fixed Bit (1) = 1,
            //    Long Packet Type (2),
            //    Type-Specific Bits (4),"
            //    Version (32),
            //    Destination Connection ID Length (8),
            //    Destination Connection ID (0..160),
            //    Source Connection ID Length (8),
            //    Source Connection ID (0..160),
            //    Type-Specific Payload (..),
            //  }

            val dcidLength = lengthNumber(dcid)

            // Packet payloadType and packet number length

            val capacity = 1 + +1 + dcidLength +
                    1 + Int.SIZE_BYTES
            val buffer = Buffer()

            // DCID Len
            buffer.writeByte(dcidLength.toByte())
            // Destination connection id
            if (dcid is Long) {
                buffer.writeLong(dcid)
            } else {
                buffer.writeInt(dcid.toInt())
            }
            // SCID Len
            buffer.writeByte(Int.SIZE_BYTES.toByte())
            // Source connection id
            buffer.writeInt(scid.toInt())
            require(buffer.size.toInt() == capacity)
            return buffer.readByteArray()
        }
    }


    data class AppPacket(val dcid: Number, val frames: List<Frame>, val packetNumber: Long) :
        Packet {
        override fun packetNumber(): Long {
            return packetNumber
        }

        override fun level(): Level {
            return Level.App
        }

        override fun frames(): List<Frame> {
            return frames
        }

        override fun estimateLength(): Int {
            val payloadLength = framesLength()
            return (1
                    + lengthNumber(dcid)
                    + 1 // packet number length: will usually be just 1, actual value cannot be
                    // computed until packet number is known
                    + payloadLength // https://tools.ietf.org/html/draft-ietf-quic-tls-27#section-5.4.2
                    // "The ciphersuites defined in [TLS13] - (...) - have 16-byte expansions..."
                    + 16)
        }

        override fun generatePacketBytes(): Buffer {

            val dcidLength = lengthNumber(dcid)

            val capacity = dcidLength + Long.SIZE_BYTES
            val additionalData = Buffer()

            if (dcid is Long) {
                additionalData.writeLong(dcid)
            } else {
                additionalData.writeInt(dcid.toInt())
            }
            additionalData.writeLong(packetNumber)

            require(additionalData.size.toInt() == capacity)

            val frameBytes = PacketService.generatePayloadBytes(frames)
            additionalData.write(frameBytes)
            return additionalData

        }

        private fun getFlags(): Byte {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-17#section-17.3
            // "|0|1|S|R|R|K|P P|"
            // "Spin Bit (S):  The sixth bit (0x20) of byte 0 is the Latency Spin
            //      Bit, set as described in [SPIN]."
            // "Reserved Bits (R):  The next two bits (those with a mask of 0x18) of
            //      byte 0 are reserved. (...) The value included prior to protection MUST be set to 0. "
            var flags: Byte = 0x40 // 0100 0000
            flags = PacketService.encodePacketNumberLength(flags, packetNumber)
            return flags
        }
    }
}

// https://tools.ietf.org/html/draft-ietf-quic-recovery-33#section-2
// "Packets that contain ack-eliciting frames elicit an ACK from the receiver (...)
// and are called ack-eliciting packets."
internal fun isAckEliciting(packet: Packet): Boolean {
    for (frame in packet.frames()) {
        if (isAckEliciting(frame)) {
            return true
        }
    }
    return false
}

internal fun isInflightPacket(packet: Packet): Boolean {
    for (frame in packet.frames()) {
        if (isAckEliciting(frame) || frame.frameType == FrameType.PaddingFrame) {
            return true
        }
    }
    return false
}