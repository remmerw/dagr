package io.github.remmerw.dagr


open class ConnectionFlow() {
    private val lostDetector = LossDetector()

    internal fun lossDetector(): LossDetector {
        return lostDetector
    }

    internal fun packetSent(packetStatus: PacketStatus) {
        if (packetStatus.packet.isAckEliciting) {
            lostDetector.packetSent(packetStatus)
        }
    }


    internal fun terminateLossDetector() {
        lossDetector().terminate()
    }

    open suspend fun terminate() {
        terminateLossDetector()
    }

    internal fun lossDetection(): List<Packet> {
        return lostDetector.detectLostPackets()
    }

}
