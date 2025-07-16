package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal object PacketService {

    /**
     * Constructs a short header packet for sending (client role).
     */
    fun createAppPackage(
        frames: List<Frame>,
        packetNumber: Long, dcid: Number
    ): Packet.AppPacket {
        return Packet.AppPacket(dcid, frames, packetNumber)
    }

    fun createInitPackage(
        peerId: PeerId,
        frames: List<Frame>,
        packetNumber: Long
    ): Packet.InitPacket {
        return Packet.InitPacket(peerId, frames, packetNumber)
    }


    fun protectPacketNumberAndPayload(
        additionalData: ByteArray,
        packetNumberSize: Int,
        payload: ByteArray,
        packetNumber: Long
    ): Buffer {
        val packetNumberPosition = additionalData.size - packetNumberSize


        val encodedPacketNumber = encodePacketNumber(packetNumber)
        val mask = createHeaderProtectionMask(
            payload,
            encodedPacketNumber.size
        )

        for (i in encodedPacketNumber.indices) {
            additionalData[i + packetNumberPosition] =
                (encodedPacketNumber[i].toInt() xor mask[1 + i].toInt()).toByte()
        }

        var flags = additionalData[0]
        flags = if ((flags.toInt() and 0x80) == 0x80) {
            // Long header: 4 bits masked
            (flags.toInt() xor (mask[0].toInt() and 0x0f).toByte().toInt()).toByte()
        } else {
            // Short header: 5 bits masked
            (flags.toInt() xor (mask[0].toInt() and 0x1f).toByte().toInt()).toByte()
        }
        additionalData[0] = flags

        val buffer = Buffer()
        buffer.write(additionalData)
        buffer.write(payload)

        return buffer
    }

    fun encodePacketNumber(packetNumber: Long): ByteArray {
        return if (packetNumber <= 0xff) {
            byteArrayOf(packetNumber.toByte())
        } else if (packetNumber <= 0xffff) {
            byteArrayOf(
                (packetNumber shr 8).toByte(),
                (packetNumber and 0x00ffL).toByte()
            )
        } else if (packetNumber <= 0xffffff) {
            byteArrayOf(
                (packetNumber shr 16).toByte(), (packetNumber shr 8).toByte(),
                (packetNumber and 0x00ffL).toByte()
            )
        } else if (packetNumber <= 0xffffffffL) {
            byteArrayOf(
                (packetNumber shr 24).toByte(), (packetNumber shr 16).toByte(),
                (packetNumber shr 8).toByte(), (packetNumber and 0x00ffL).toByte()
            )
        } else {
            throw IllegalStateException(" not yet implemented cannot encode pn > 4 bytes")
        }
    }


    fun createHeaderProtectionMask(
        ciphertext: ByteArray,
        encodedPacketNumberLength: Int,
    ): ByteArray {
        // https://tools.ietf.org/html/draft-ietf-quic-tls-17#section-5.4
        // "The same number of bytes are always sampled, but an allowance needs
        //   to be made for the endpoint removing protection, which will not know
        //   the length of the Packet Number field.  In sampling the packet
        //   ciphertext, the Packet Number field is assumed to be 4 bytes long
        //   (its maximum possible encoded length)."
        val sampleOffset = 4 - encodedPacketNumberLength
        val sample = ciphertext.copyOfRange(sampleOffset, sampleOffset + 16)

        return sample
    }


    /**
     * Updates the given flags byte to encode the packet number length that is used for
     * encoding the given packet number.
     */
    fun encodePacketNumberLength(flags: Byte, packetNumber: Long): Byte {
        return if (packetNumber <= 0xff) {
            flags
        } else if (packetNumber <= 0xffff) {
            (flags.toInt() or 0x01).toByte()
        } else if (packetNumber <= 0xffffff) {
            (flags.toInt() or 0x02).toByte()
        } else if (packetNumber <= 0xffffffffL) {
            (flags.toInt() or 0x03).toByte()
        } else {
            throw IllegalStateException("not yet implemented cannot encode pn > 4 bytes")
        }
    }

    fun generatePayloadBytes(frames: List<Frame>): ByteArray {

        Buffer().use { buffer ->
            for ((_, frameBytes) in frames) {
                buffer.write(frameBytes)
            }

            val data = buffer.readByteArray()
            require(data.size < Settings.MAX_PACKET_SIZE) { "Invalid packet size" }
            return data
        }

    }

}
