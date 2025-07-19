package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.InternalAPI
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class LossDetector() { // todo remove to Connectionflow
    private val packetSentLog: MutableMap<Long, PacketStatus> = ConcurrentMap()

    @Volatile
    private var largestAcked = -1L

    @Volatile
    private var isStopped = false

    @OptIn(InternalAPI::class)
    fun packetSent(packet: PacketStatus) {
        if (isStopped) {
            return
        }

        // During a reset operation, no new packets must be logged as sent.
        packetSentLog[packet.packet.packetNumber] = packet

    }

    fun processAckFrameReceived(packetNumber: Long) {
        if (isStopped) {
            return
        }
        if (packetNumber > largestAcked) {
            largestAcked = packetNumber
        }
        packetSentLog.remove(packetNumber)
    }

    fun terminate() {
        isStopped = true
        packetSentLog.clear()
    }

    fun detectLostPackets(): List<Packet> {
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
}
