package io.github.remmerw.dagr

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi


abstract class ConnectionFlow(private val incoming: Boolean) {

    private val sendLog: MutableMap<Long, ByteArray> = ConcurrentHashMap()

    private val largestAcked: AtomicLong = AtomicLong(-1L)


    @OptIn(ExperimentalAtomicApi::class)
    private val isStopped = AtomicBoolean(false)


    fun incoming(): Boolean {
        return incoming
    }

    internal fun ackFrameReceived(packetNumber: Long) {

        largestAcked.updateAndGet { oldValue ->
            if (packetNumber > oldValue) {
                packetNumber
            } else {
                oldValue
            }
        }

        sendLog.remove(packetNumber)

    }

    internal fun resetSendLog() {
        sendLog.clear()
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal open fun terminate() {
        isStopped.store(true)
        resetSendLog()
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
        sendLog.keys.forEach { pn ->
            if (packetTooOld(pn)) {
                val packet = sendLog.remove(pn)
                if (packet != null) {
                    result++
                    sendPacket(pn, packet, !incoming())
                }
            }
        }
        return result
    }


    private fun packetTooOld(pn: Long): Boolean {
        if (pn < largestAcked.get()) {
            debug("Loss too old packet $pn")
            return true
        }
        return false
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal fun packetSend(packetNumber: Long, packet: ByteArray) {
        if (isStopped.load()) {
            return
        }

        sendLog[packetNumber] = packet

    }

}
