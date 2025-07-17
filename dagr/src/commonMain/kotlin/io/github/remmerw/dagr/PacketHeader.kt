package io.github.remmerw.dagr


internal data class PacketHeader(
    val level: Level, val framesBytes: ByteArray, val packetNumber: Long,
) {
    override fun hashCode(): Int {
        var result = level.hashCode()

        result = 31 * result + framesBytes.contentHashCode()
        result = 31 * result + packetNumber.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PacketHeader

        if (packetNumber != other.packetNumber) return false
        if (level != other.level) return false

        if (!framesBytes.contentEquals(other.framesBytes)) return false

        return true
    }
}
