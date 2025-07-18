package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentSet
import kotlin.concurrent.atomics.ExperimentalAtomicApi


/**
 * Listens for received packets and generates ack frames for them.
 */
internal class AckGenerator {
    private val rangesToAcknowledge: MutableSet<Long> = ConcurrentSet()


    // only invoked from the SENDER THREAD
    @OptIn(ExperimentalAtomicApi::class)
    fun cleanup() {
        rangesToAcknowledge.clear()
    }


    // only invoked from the RECEIVER THREAD
    @OptIn(ExperimentalAtomicApi::class)
    fun packetReceived(
        isAckEliciting: Boolean,
        packetNumber: Long
    ) {
        if (isAckEliciting) {
            rangesToAcknowledge.add(packetNumber)
        }
    }


    // only invoked from the SENDER THREAD

    fun generateAcks(): List<Frame> {
        val acks: MutableList<Frame> = mutableListOf()
        if (!rangesToAcknowledge.isEmpty()) {

            val list = rangesToAcknowledge.toList()
            list.forEach { packet ->
                acks.add(createAckFrame(packet))
            }
            rangesToAcknowledge.removeAll(list)
        }
        return acks
    }

}

