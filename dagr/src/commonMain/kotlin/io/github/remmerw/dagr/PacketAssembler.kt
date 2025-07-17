package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId


/**
 * Assembles QUIC packets for a given encryption level, based on "send requests" that are previously queued.
 * These send requests either contain a frame, or can produce a frame to be sent.
 */
internal class PacketAssembler internal constructor(
    private val level: Level,
    private val sendRequestQueue: SendRequestQueue,
    private val ackGenerator: AckGenerator
) {
    private var packetNumberGenerator = 0L // no concurrency


    suspend fun assemble(
        remainingCwndSize: Int,
        availablePacketSize: Int,
        peerId: PeerId
    ): Packet? {

        // Packet can be 3 bytes larger than estimated size because of unknown packet number length.
        val available = kotlin.math.min(remainingCwndSize, availablePacketSize - 3)
        val frames: MutableList<Frame> = arrayListOf()

        val packetNumber = packetNumberGenerator++


        // Check for an explicit ack, i.e. an ack on ack-eliciting packet that cannot be delayed
        if (ackGenerator.mustSendAck(level)) {
            val ackFrame = ackGenerator.generateAck(
                packetNumber
            ) { length: Int ->
                (estimateLength(
                    level, frames
                ) + length) <= availablePacketSize
            }

            // https://tools.ietf.org/html/draft-ietf-quic-transport-29#section-13.2
            // "... packets containing only ACK frames are not congestion controlled ..."
            // So: only check if it fits within available packet space
            if (ackFrame != null) {
                frames.add(ackFrame)
            } else {
                // If not even a mandatory ack can be added, don't bother about other frames:
                // theoretically there might be frames
                // that can be fit, but this is very unlikely to happen (because limit packet size
                // is caused by coalescing packets
                // in one datagram, which will only happen during handshake, when acks are still
                // small) and even then: there
                // will be a next packet in due time.
                // AckFrame does not fit in availablePacketSize, so just return;
                return null
            }
        }

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


        val packet: Packet?
        if (frames.isEmpty()) {
            // Nothing could be added, discard packet and mark packet number as not used
            packetNumberGenerator--
            packet = null
        } else {
            packet = createPacket(
                level, peerId, packetNumber, frames
            )
        }
        return packet
    }


    private fun createPacket(
        level: Level,
        peerId: PeerId,
        packetNumber: Long,
        frames: List<Frame>
    ): Packet {
        return when (level) {
            Level.APP -> PacketService.createAppPackage(packetNumber, frames)
            Level.INIT -> PacketService.createInitPackage(peerId, packetNumber, frames)
        }
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

