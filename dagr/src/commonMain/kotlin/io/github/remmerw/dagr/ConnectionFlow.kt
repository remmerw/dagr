package io.github.remmerw.dagr


open class ConnectionFlow() {

    private val discardedLevels = arrayOfNulls<Boolean>(Level.LENGTH)
    private val lostDetector = LossDetector()

    init {

        discardedLevels[Level.INIT.ordinal] = false
        discardedLevels[Level.APP.ordinal] = false
    }


    internal fun lossDetector(): LossDetector {
        return lostDetector
    }


    internal fun packetSent(packetStatus: PacketStatus) {
        if (isAckEliciting(packetStatus.packet)) {
            lostDetector.packetSent(packetStatus)
        }
    }


    /**
     * Stop sending packets, but don't shutdown yet, so connection close can be sent.
     */
    fun clearRequests() {

        // No more retransmissions either
        stopRecovery()
    }

    open suspend fun cleanup() {
        for (level in Level.levels()) {
            discard(level)
        }
    }

    internal fun isDiscarded(level: Level): Boolean {
        return discardedLevels[level.ordinal]!!
    }

    internal fun discard(level: Level) {
        discardedLevels[level.ordinal] = true
    }

    internal fun lossDetection(): List<Packet> {
        return lostDetector.detectLostPackets()
    }

    private fun stopRecovery() {
        lossDetector().stop()

    }
}
