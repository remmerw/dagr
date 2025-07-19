package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlin.concurrent.atomics.ExperimentalAtomicApi


open class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, Packet> = ConcurrentMap()

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


        packetSentLog.keys.forEach { pn ->
            if (pnTooOld(pn)) {
                val packet = packetSentLog.remove(pn)
                if (packet != null) {
                    result.add(packet)
                }
            }
        }
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun pnTooOld(pn: Long): Boolean {
        if (pn < largestAcked - Settings.LACKED_PACKETS) {
            debug("Loss too old packet $pn")
            return true
        }
        return false
    }


    internal fun packetSent(packet: Packet) {
        if (isStopped) {
            return
        }

        if (packet.shouldBeAcked) {
            // During a reset operation, no new packets must be logged as sent.
            packetSentLog[packet.packetNumber] = packet
        }
    }

    internal fun lossDetection(): List<Packet> {
        return detectLostPackets()
    }

}
