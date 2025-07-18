package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

    fun processAckFrameReceived(packetNumber: Long) {
        if (isStopped) {
            return
        }
        packetSentLog.remove(packetNumber)
    }

    fun stop() {
        isStopped = true
        packetSentLog.clear()

    }

    fun detectLostPackets(): List<Packet> {
        if (isStopped) {
            return emptyList()
        }

        val result: MutableList<Packet> = mutableListOf()
        val packets = packetSentLog.values

        packets.forEach { packetStatus ->
            if (pnTooOld(packetStatus)) {
                if (!packetStatus.packet.isAckOnly) {
                    println("Declare Lost")
                    result.add(packetStatus.packet)
                    packetSentLog.remove(packetStatus.packet.packetNumber())
                }
            }
        }
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pnTooOld(p: PacketStatus): Boolean {
        val result = p.packet.packetNumber() <= largestAcked - 3
        if (result) {
            println("Loss too Old $largestAcked " + p.packet.level() + " " + p.packet.packetNumber())
        }
        return result
    }
}
