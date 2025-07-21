package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.atomics.ExperimentalAtomicApi


open class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, Packet> = ConcurrentMap()

    @Volatile
    private var largestAcked = -1L

    @Volatile
    private var isStopped = false
    private val semaphore = Semaphore(Settings.LACKED_PACKETS,0)


    private suspend fun acquireBlocking()  {
        semaphore.acquire()
    }

    private fun releaseBlocking(){
        semaphore.release()
    }

    internal fun processAckFrameReceived(packetNumber: Long) {
        if (isStopped) {
            return
        }

        if (packetNumber > largestAcked) {
            largestAcked = packetNumber
        }
        packetSentLog.remove(packetNumber)

        if(packetNumber > Settings.PAKET_OFFSET) {
            releaseBlocking()
        }

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
        if (pn < largestAcked) {
            debug("Loss too old packet $pn")
            return true
        }
        return false
    }


    internal suspend fun packetSent(packet: Packet) {
        if (isStopped) {
            return
        }
        packetSentLog[packet.packetNumber] = packet

        if(packet.packetNumber > Settings.PAKET_OFFSET){
            acquireBlocking()
        }
    }

    internal fun lossDetection(): List<Packet> {
        return detectLostPackets()
    }


}
