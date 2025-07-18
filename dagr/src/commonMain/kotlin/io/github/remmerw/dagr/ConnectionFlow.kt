package io.github.remmerw.dagr

import kotlin.time.TimeSource


open class ConnectionFlow() {
    private val sendRequestQueues = arrayOfNulls<SendRequestQueue>(Level.LENGTH)
    private val packetAssemblers = arrayOfNulls<PacketAssembler>(Level.LENGTH)
    private val ackGenerators = arrayOfNulls<AckGenerator>(Level.LENGTH)
    private val discardedLevels = arrayOfNulls<Boolean>(Level.LENGTH)
    private val lossDetectors = arrayOfNulls<LossDetector>(Level.LENGTH)


    init {
        for (level in Level.levels()) {
            sendRequestQueues[level.ordinal] = SendRequestQueue()
        }

        for (level in Level.levels()) {
            ackGenerators[level.ordinal] = AckGenerator()
        }

        discardedLevels[Level.INIT.ordinal] = false
        discardedLevels[Level.APP.ordinal] = false


        for (level in Level.levels()) {
            val levelIndex = level.ordinal
            packetAssemblers[levelIndex] = PacketAssembler(
                level,
                sendRequestQueues[levelIndex]!!, ackGenerators[levelIndex]!!
            )
        }

        for (level in Level.levels()) {
            lossDetectors[level.ordinal] = LossDetector(this)
        }
    }


    internal fun lossDetector(level: Level): LossDetector {
        return lossDetectors[level.ordinal]!!
    }

    internal fun ackGenerator(level: Level): AckGenerator {
        return ackGenerators[level.ordinal]!!
    }

    internal fun sendRequestQueue(level: Level): SendRequestQueue {
        return sendRequestQueues[level.ordinal]!!
    }

    internal fun packetAssembler(level: Level): PacketAssembler {
        return packetAssemblers[level.ordinal]!!
    }

    internal fun packetSent(
        packet: Packet,
        timeSent: TimeSource.Monotonic.ValueTimeMark
    ) {
        if (isInflightPacket(packet)) {
            val packetStatus = PacketStatus(packet, timeSent)
            lossDetectors[packet.level().ordinal]!!.packetSent(packetStatus)
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

        // 5.5.  Discarding Keys and Packet State
        //
        //   When packet protection keys are discarded (see Section 4.9 of
        //   [QUIC-TLS]), all packets that were sent with those keys can no longer
        //   be acknowledged because their acknowledgements cannot be processed
        //   anymore.  The sender MUST discard all recovery state associated with
        //   those packets and MUST remove them from the count of bytes in flight.
        lossDetectors[level.ordinal]!!.stop()


        // deactivate ack generator for level
        ackGenerator(level).cleanup()
    }

    internal fun lossDetection(): List<Packet> {
        val result: MutableList<Packet> = mutableListOf()
        for (level in Level.levels()) {
            result.addAll(lossDetectors[level.ordinal]!!.detectLostPackets())
        }
        return result
    }

    private fun stopRecovery() {
        for (lossDetector in lossDetectors) {
            lossDetector!!.stop()
        }
    }
}
