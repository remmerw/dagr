package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicLong


abstract class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, Packet> = ConcurrentMap()

    private val largestAcked: AtomicLong = AtomicLong(-1L)

    @Volatile
    private var isStopped = false
    private val semaphore = Semaphore(Settings.LACKED_PACKETS, 0)


    private suspend fun acquireBlocking() {
        semaphore.acquire()
    }

    private fun releaseBlocking() {
        semaphore.release()
    }

    internal fun processAckFrameReceived(packetNumber: Long) {

        largestAcked.updateAndGet { oldValue ->
            if (packetNumber > oldValue) {
                packetNumber
            } else {
                oldValue
            }
        }

        packetSentLog.remove(packetNumber)

        if (packetNumber > Settings.PAKET_OFFSET) {
            releaseBlocking()
        }

    }

    internal fun terminateLossDetector() {
        isStopped = true
        packetSentLog.clear()
    }

    internal abstract suspend fun sendPacket(packet: Packet)

    internal suspend fun detectLostPackets(): Int {
        if (isStopped) {
            return 0
        }
        var result = 0
        packetSentLog.keys.forEach { pn ->
            if (pnTooOld(pn)) {
                val packet = packetSentLog.remove(pn)
                if (packet != null) {
                    result++
                    sendPacket(packet)
                }
            }
        }
        return result
    }


    private fun pnTooOld(pn: Long): Boolean {
        if (pn < largestAcked.get()) {
            debug("Loss too old packet $pn")
            return true
        }
        return false
    }


    internal suspend fun packetSent(packet: Packet) {
        if (isStopped) {
            return
        }
        val pn = packet.packetNumber
        packetSentLog[pn] = packet

        if (pn > Settings.PAKET_OFFSET) {
            acquireBlocking()
        }
    }

}
