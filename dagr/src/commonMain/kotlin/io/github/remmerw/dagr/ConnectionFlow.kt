package io.github.remmerw.dagr


open class ConnectionFlow() {
    private val sendRequestQueues = arrayOfNulls<SendRequestQueue>(Level.LENGTH)
    private val packetAssemblers = arrayOfNulls<PacketAssembler>(Level.LENGTH)
    private val discardedLevels = arrayOfNulls<Boolean>(Level.LENGTH)
    private val lostDetector = LossDetector()

    init {
        for (level in Level.levels()) {
            sendRequestQueues[level.ordinal] = SendRequestQueue()
        }

        discardedLevels[Level.INIT.ordinal] = false
        discardedLevels[Level.APP.ordinal] = false


        for (level in Level.levels()) {
            val levelIndex = level.ordinal
            packetAssemblers[levelIndex] = PacketAssembler(
                level,
                sendRequestQueues[levelIndex]!!
            )
        }
    }


    internal fun lossDetector(): LossDetector {
        return lostDetector
    }


    internal fun sendRequestQueue(level: Level): SendRequestQueue {
        return sendRequestQueues[level.ordinal]!!
    }

    internal fun packetAssembler(level: Level): PacketAssembler {
        return packetAssemblers[level.ordinal]!!
    }

    internal fun packetSent(packetStatus: PacketStatus) {
        if (isAckEliciting(packetStatus.packet)) {
            lostDetector.packetSent(packetStatus)
        }
    }

    internal suspend fun insertRequest(level: Level, frame: Frame) {
        sendRequestQueue(level).insertRequest(frame)
    }

    internal suspend fun addRequest(level: Level, frame: Frame) {
        sendRequestQueue(level).appendRequest(frame)
    }


    /**
     * Stop sending packets, but don't shutdown yet, so connection close can be sent.
     */
    suspend fun clearRequests() {
        // Stop sending packets, so discard any packet waiting to be send.
        for (queue in sendRequestQueues) {
            queue!!.clear()
        }

        // No more retransmissions either.
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

    internal suspend fun discard(level: Level) {
        discardedLevels[level.ordinal] = true

        // clear all send requests and probes on that level
        sendRequestQueues[level.ordinal]!!.clear()

    }

    internal fun lossDetection(): List<Packet> {
        return lostDetector.detectLostPackets()
    }

    private fun stopRecovery() {
        lossDetector().stop()

    }
}
