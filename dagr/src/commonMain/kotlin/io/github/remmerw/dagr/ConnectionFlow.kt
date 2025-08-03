package io.github.remmerw.dagr

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


abstract class ConnectionFlow(private val incoming: Boolean) {

    private val sendLog: MutableMap<Long, ByteArray> = ConcurrentHashMap()

    private val largestAcked: AtomicLong = AtomicLong(-1L)

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

    internal open fun terminate() {
        resetSendLog()
    }

    internal abstract fun sendPacket(
        packetNumber: Long,
        packet: ByteArray,
        shouldBeAcked: Boolean
    )

    internal fun detectLostPackets(): Int {
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

    internal fun packetSend(packetNumber: Long, packet: ByteArray) {
        sendLog[packetNumber] = packet

    }

}
