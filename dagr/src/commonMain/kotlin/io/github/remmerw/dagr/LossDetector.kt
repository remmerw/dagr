package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max

internal class LossDetector(private val connectionFlow: ConnectionFlow) {
    private val packetSentLog: MutableMap<Long, PacketStatus> = ConcurrentMap()

    @Volatile
    private var largestAcked = -1L

    @Volatile
    private var isStopped = false

    fun packetSent(packetStatus: PacketStatus) {
        if (isStopped) {
            return
        }

        // During a reset operation, no new packets must be logged as sent.
        packetSentLog[packetStatus.packet.packetNumber()] = packetStatus
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun processAckFrameReceived(ackFrame: FrameReceived.AckFrame) {
        if (isStopped) {
            return
        }
        val pn = ackFrame.packetNumber


        val packetStatus = packetSentLog.remove(pn)
        if (packetStatus != null) {
            if (isAckEliciting(packetStatus.packet)) {
                connectionFlow.processAckedPacket(packetStatus)
            }
        }

    }

    fun stop() {
        isStopped = true


        val packets = packetSentLog.values
        packets.forEach { packetStatus ->
            connectionFlow.discardBytesInFlight(packetStatus)
        }

        packetSentLog.clear()

    }

    suspend fun detectLostPackets() {
        if (isStopped) {
            return
        }

        val lossDelay = (Settings.TIME_THRESHOLD * max(
            connectionFlow.getSmoothedRtt(), connectionFlow.getLatestRtt()
        )).toLong()


        // https://tools.ietf.org/html/draft-ietf-quic-recovery-20#section-6.1
        // "A packet is declared lost if it meets all the following conditions:
        //   o  The packet is unacknowledged, in-flight, and was sent prior to an
        //      acknowledged packet.
        //   o  Either its packet number is kPacketThreshold smaller than an
        //      acknowledged packet (Section 6.1.1), or it was sent long enough in
        //      the past (Section 6.1.2)."
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-20#section-2
        // "In-flight:  Packets are considered in-flight when they have been sent
        //      and neither acknowledged nor declared lost, and they are not ACK-
        //      only."
        val packets = packetSentLog.values

        packets.forEach { packetStatus ->
            if (pnTooOld(packetStatus) || sentTimeTooLongAgo(packetStatus, lossDelay)) {
                if (!packetStatus.packet.isAckOnly) {
                    declareLost(packetStatus)
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pnTooOld(p: PacketStatus): Boolean {
        val result = p.packet.packetNumber() <= largestAcked - 3
        if (result) {
            println("Loss too Old $largestAcked " + p.packet.level() + " " + p.packet.packetNumber())
        }
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun sentTimeTooLongAgo(p: PacketStatus, lossDelay: Long): Boolean {

        val result = p.timeSent.elapsedNow().inWholeMilliseconds > lossDelay
        if (result) {
            println("Loss delay $lossDelay " + p.packet.level() + " " + p.packet.packetNumber())
        }
        return result
    }

    private suspend fun declareLost(packetStatus: PacketStatus) {
        if (isAckEliciting(packetStatus.packet)) {
            connectionFlow.registerLost(packetStatus)
        }

        println("Declare Lost")

        // Retransmitting the frames in the lost packet is delegated to the lost frame callback,
        // because whether retransmitting the frame is necessary (and in which manner) depends
        // on frame payloadType,
        // see https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-13.3
        val frames = packetStatus.packet.frames()
        for (frame in frames) {
            when (frame.frameType) {
                FrameType.DataFrame -> connectionFlow.insertRequest(
                    packetStatus.packet.level(),
                    frame
                )

                FrameType.VerifyResponseFrame,
                FrameType.VerifyRequestFrame -> connectionFlow.addRequest(
                    packetStatus.packet.level(),
                    frame
                )

                else -> {}
            }
        }

        packetSentLog.remove(packetStatus.packet.packetNumber())

    }
}
