package io.github.remmerw.dagr

import java.lang.Thread.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi


abstract class ConnectionFlow() {

    private val packetSentLog: MutableMap<Long, ByteArray> = ConcurrentHashMap()

    private val largestAcked: AtomicLong = AtomicLong(-1L)


    @OptIn(ExperimentalAtomicApi::class)
    private val isStopped = AtomicBoolean(false)

    private val semaphore = Semaphore(Settings.LACKED_PACKETS)

    @OptIn(ExperimentalAtomicApi::class)
    protected fun flush() {
        while (packetSentLog.isNotEmpty() && !isStopped.load()) {
            yield()
        }
    }

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

    @OptIn(ExperimentalAtomicApi::class)
    internal fun terminateLossDetector() {
        isStopped.store(true)
        packetSentLog.clear()
    }

    internal abstract fun sendPacket(
        packetNumber: Long,
        packet: ByteArray,
        shouldBeAcked: Boolean
    )

    @OptIn(ExperimentalAtomicApi::class)
    internal fun detectLostPackets(): Int {
        if (isStopped.load()) {
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


    @OptIn(ExperimentalAtomicApi::class)
    internal fun packetSent(packetNumber: Long, packet: ByteArray) {
        if (isStopped.load()) {
            return
        }

        packetSentLog[packetNumber] = packet

        if (packetNumber > Settings.PAKET_OFFSET) {
            acquireBlocking()
        }
    }

}
