package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal object PacketService {

    fun createAppPackage(
        packetNumber: Long,
        frames: List<Frame>,
    ): Packet.AppPacket {
        return Packet.AppPacket(packetNumber, frames)
    }

    fun createInitPackage(
        peerId: PeerId,
        packetNumber: Long,
        frames: List<Frame>
    ): Packet.InitPacket {
        return Packet.InitPacket(peerId, packetNumber, frames)
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
