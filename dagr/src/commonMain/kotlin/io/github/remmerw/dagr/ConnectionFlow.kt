package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlin.concurrent.atomics.ExperimentalAtomicApi


open class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, PacketStatus> = ConcurrentMap()

    @Volatile
    private var largestAcked = -1L

    @Volatile
    private var isStopped = false


    internal fun processAckFrameReceived(packetNumber: Long) {
        if (isStopped) {
            return
        }
        if (packetNumber > largestAcked) {
            largestAcked = packetNumber
        }
        packetSentLog.remove(packetNumber)
    }

    internal fun terminateLossDetector() {
        isStopped = true
        packetSentLog.clear()
    }

    internal fun detectLostPackets(): List<Packet> {
        if (isStopped) {
            return emptyList()
        }

        val result: MutableList<Packet> = mutableListOf()

        /* TODO
        packetSentLog.values.forEach { packetStatus ->
            if (pnTooOld(packetStatus)) {
                val packetStatus = packetSentLog.remove(
                    packetStatus.packet.packetNumber())
                if (packetStatus != null) {
                    result.add(packetStatus.packet)
                }
            }
        }*/
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pnTooOld(packetStatus: PacketStatus): Boolean {
        if (packetStatus.timeSent.elapsedNow().inWholeMilliseconds
            > (Settings.INITIAL_RTT.toLong())
        ) {
            println("Loss too old packet $packetStatus")
            return true
        }
        return false
    }


    internal fun packetSent(packetStatus: PacketStatus) {
        if (isStopped) {
            return
        }

        if (packetStatus.packet.isAckEliciting) {
            // During a reset operation, no new packets must be logged as sent.
            packetSentLog[packetStatus.packet.packetNumber] = packetStatus
        }
    }

    internal fun lossDetection(): List<Packet> {
        return detectLostPackets()
    }

}
