package io.github.remmerw.dagr

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource


/**
 * This class implements the flow concepts of a QUIC connection
 * -> RttEstimator
 *
 *
 * -> CongestionController
 * [...](https://datatracker.ietf.org/doc/html/rfc9002#name-congestion-control)
 * -> RecoveryManager
 * QUIC Loss Detection is specified in [...](https://www.rfc-editor.org/rfc/rfc9002.html).
 *
 *
 * "QUIC senders use acknowledgments to detect lost packets and a PTO to ensure acknowledgments are received"
 * It uses a single timer, because either there are lost packets to detect, or a probe must be scheduled, never both.
 *
 *
 * **Ack based loss detection**
 * When an Ack is received, packets that are sent "long enough" before the largest acked, are deemed lost; for the
 * packets not send "long enough", a timer is set to mark them as lost when "long enough" time has been passed.
 *
 *
 * An example:
 * -----------------------time------------------->>
 * sent:   1           2      3        4
 * acked:                                    4
 * \--- long enough before 4 --/                       => 1 is marked lost immediately
 * \--not long enough before 4 --/
 * |
 * Set timer at this point in time, as that will be "long enough".
 * At that time, a new timer will be set for 3, unless acked meanwhile.
 *
 *
 * **Detecting tail loss with probe timeout**
 * When no Acks arrive, no packets will be marked as lost. To trigger the peer to send an ack (so loss detection can do
 * its job again), a probe (ack-eliciting packet) will be sent after the probe timeout. If the situation does not change
 * (i.e. no Acks received), additional probes will be sent, but with an exponentially growing delay.
 *
 *
 * An example:
 * -----------------------time------------------->>
 * sent:   1           2      3        4
 * acked:                                    4
 * \-- timer set at loss time  --/
 * |
 * When the timer fires, there is no new ack received, so
 * nothing can be marked as lost. A probe is scheduled for
 * "probe timeout" time after the time 3 was sent:
 * \-- timer set at "probe timeout" time after 3 was sent --\
 * |
 * Send probe!
 *
 *
 * Note that packet 3 will not be marked as lost as long no ack is received!
 *
 *
 * **Exceptions**
 * Because a server might be blocked by the anti-amplification limit, a client must also send probes when it has no
 * ack eliciting packets in flight, but is not sure whether the peer has validated the client address.
 */

open class ConnectionFlow() {
    private val sendRequestQueues = arrayOfNulls<SendRequestQueue>(Level.LENGTH)
    private val packetAssemblers = arrayOfNulls<PacketAssembler>(Level.LENGTH)
    private val ackGenerators = arrayOfNulls<AckGenerator>(Level.LENGTH)
    private val discardedLevels = arrayOfNulls<Boolean>(Level.LENGTH)

    @OptIn(ExperimentalAtomicApi::class)
    private val rttVar = AtomicInt(Settings.NOT_DEFINED)

    @OptIn(ExperimentalAtomicApi::class)
    private val smoothedRtt = AtomicInt(Settings.NOT_DEFINED)

    @OptIn(ExperimentalAtomicApi::class)
    private val latestRtt = AtomicInt(0)

    private val lossDetectors = arrayOfNulls<LossDetector>(Level.LENGTH)

    // https://tools.ietf.org/html/draft-ietf-quic-transport-30#section-8.2
    // "If this value is absent, a default of 25 milliseconds is assumed."
    @Volatile
    protected var remoteMaxAckDelay: Int = Settings.MAX_ACK_DELAY


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
        size: Int,
        timeSent: TimeSource.Monotonic.ValueTimeMark
    ) {
        if (isInflightPacket(packet)) {
            val packetStatus = PacketStatus(packet, size, timeSent)
            lossDetectors[packet.level().ordinal]!!.packetSent(packetStatus)
        }
    }

    internal suspend fun insertRequest(level: Level, frame: Frame) {
        sendRequestQueue(level).insertRequest(frame)
    }

    internal suspend fun addRequest(level: Level, frame: Frame) {
        sendRequestQueue(level).appendRequest(frame)
    }

    internal fun process(ackFrame: FrameReceived.AckFrame, level: Level) {
        lossDetectors[level.ordinal]!!.processAckFrameReceived(ackFrame)
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

    val pto: Int
        get() = getSmoothedRtt() + 4 * getRttVar() + remoteMaxAckDelay


    @OptIn(ExperimentalAtomicApi::class)
    fun getSmoothedRtt(): Int {
        val value = smoothedRtt.load()
        return if (value == Settings.NOT_DEFINED) {
            Settings.INITIAL_RTT
        } else {
            value
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun getRttVar(): Int {
        // Rtt-var is only used for computing PTO.
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-5.3
        // "The initial probe timeout for a new connection or new path SHOULD be set to twice the initial RTT"
        // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-5.2.1
        // "PTO = smoothed_rtt + max(4*rttvar, kGranularity) + max_ack_delay"
        // Hence, using an initial rtt-var of initial-rtt / 4, will result in an initial PTO of twice the initial RTT.
        // After the first packet is received, the rttVar will be computed from the real RTT sample.
        val value = rttVar.load()
        return if (value == Settings.NOT_DEFINED) {
            Settings.INITIAL_RTT / 4
        } else {
            value
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun getLatestRtt(): Int {
        return latestRtt.load()
    }


    suspend fun lossDetection() {
        for (level in Level.levels()) {
            lossDetectors[level.ordinal]!!.detectLostPackets()
        }
    }

    private fun stopRecovery() {
        for (lossDetector in lossDetectors) {
            lossDetector!!.stop()
        }
    }
}
