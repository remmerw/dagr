package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId


internal class PacketAssembler internal constructor(
    private val level: Level,
    private val sendRequestQueue: SendRequestQueue
) {


    suspend fun assemble(
        packetNumber: Long,
        peerId: PeerId
    ): Packet? {

        val available = Settings.MAX_PACKAGE_SIZE
        val frames: MutableList<Frame> = arrayListOf()

        if (sendRequestQueue.hasRequests()) {
            // Must create packet here, to have an initial estimate of packet header overhead
            var estimatedSize = estimateLength(level, frames)

            // frame would have been added; this will give upper limit of packet overhead.
            while (estimatedSize < available) {
                // First try to find a frame that will leave space for optional frame (if any)
                val proposedSize = available - estimatedSize
                val next = sendRequestQueue.next(proposedSize)
                    ?: // Nothing fits within available space
                    break

                val nextFrame = next.frameSupplier.nextFrame(proposedSize)
                if (nextFrame != null) {
                    check(nextFrame.frameLength() <= proposedSize) {
                        ("supplier does not produce frame of right (max) size: " +
                                nextFrame.frameLength() + " > " + (proposedSize)
                                + " frame: " + nextFrame)
                    }
                    estimatedSize += nextFrame.frameLength()
                    frames.add(nextFrame)
                }
            }
        }
        val packet: Packet? = if (frames.isEmpty()) {
            // Nothing could be added, discard packet and mark packet number as not used
            null
        } else {
            when (level) {
                Level.APP -> Packet.AppPacket(packetNumber, frames)
                Level.INIT -> Packet.InitPacket(peerId, packetNumber, frames)
            }
        }
        return packet
    }

    private fun framesLength(frames: List<Frame>): Int {
        var sum = 0
        for (frame in frames) {
            sum += frame.frameLength()
        }
        return sum
    }


    private fun estimateLength(
        level: Level, frames: List<Frame>
    ): Int {
        return when (level) {
            Level.APP -> estimateAppPacketLength(frames)

            Level.INIT -> estimateInitPacketLength(frames)
        }
    }

    private fun estimateAppPacketLength(
        frames: List<Frame>
    ): Int {
        val payloadLength = framesLength(frames)
        return (1 + Long.SIZE_BYTES + payloadLength)
    }

    private fun estimateInitPacketLength(
        frames: List<Frame>
    ): Int {
        val payloadLength = framesLength(frames)
        return (1 + 32 + Long.SIZE_BYTES + payloadLength) // 32 is peerId
    }

}

