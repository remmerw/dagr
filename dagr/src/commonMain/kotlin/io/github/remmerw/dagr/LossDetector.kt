package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.InternalAPI
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class LossDetector() {
    private val packetSentLog: MutableMap<Long, Packet> = ConcurrentMap()

    @Volatile
    private var largestAcked = -1L

    @Volatile
    private var isStopped = false

    @OptIn(InternalAPI::class)
    fun packetSent(packet: Packet) {
        if (isStopped) {
            return
        }

        // During a reset operation, no new packets must be logged as sent.
        packetSentLog[packet.packetNumber()] = packet

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

    fun stop() {
        isStopped = true
        packetSentLog.clear()

    }

    fun isStopped(): Boolean {
        return isStopped
    }

    fun packetsInFlight(): Int {
        return packetSentLog.size
    }

    fun detectLostPackets(): List<Packet> {
        if (isStopped) {
            return emptyList()
        }

        val result: MutableList<Packet> = mutableListOf()

        /* TODO
        packetSentLog.keys.forEach { pn ->
            if (pnTooOld(pn)) {
                val packet = packetSentLog.remove(pn)
                if (packet != null) {
                    println("Declare Lost")
                    result.add(packet)
                }
            }
        }*/
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pnTooOld(pn: Long): Boolean {
        val result = pn <= largestAcked - (2*Settings.LACKED_ACKS)
        if (result) {
            println("Loss too Old $largestAcked packet $pn")
        }
        return result
    }
}
