package io.github.remmerw.dagr

import java.lang.Thread.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong


abstract class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, ByteArray> = ConcurrentHashMap()

    private val largestAcked: AtomicLong = AtomicLong(-1L)

    @Volatile
    private var isStopped = false
    private val semaphore = Semaphore(Settings.LACKED_PACKETS)


    private fun acquireBlocking() {
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

    internal abstract fun sendPacket(
        packetNumber: Long,
        packet: ByteArray,
        shouldBeAcked: Boolean
    )

    internal fun detectLostPackets(): Int {
        if (isStopped) {
            return 0
        }
        var result = 0
        packetSentLog.keys.forEach { pn ->
            if (pnTooOld(pn)) {
                val packet = packetSentLog.remove(pn)
                if (packet != null) {
                    result++
                    sendPacket(pn, packet, true)
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


    internal fun packetSent(packetNumber: Long, packet: ByteArray) {
        if (isStopped) {
            return
        }

        packetSentLog[packetNumber] = packet

        if (packetNumber > Settings.PAKET_OFFSET) {
            acquireBlocking()
        }
    }

    internal fun sync() {
        while (packetSentLog.isNotEmpty()) {
            yield()
        }
    }

}
