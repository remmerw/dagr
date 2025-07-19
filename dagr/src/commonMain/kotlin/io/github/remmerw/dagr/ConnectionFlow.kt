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
        if (packetStatus.packet.isAckEliciting()) {
            lostDetector.packetSent(packetStatus)
        }
    }


    internal fun terminateLossDetector() {
        lossDetector().terminate()
    }

    open suspend fun terminate() {
        terminateLossDetector()
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

}
