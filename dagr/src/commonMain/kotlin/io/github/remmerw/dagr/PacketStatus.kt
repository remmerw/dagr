package io.github.remmerw.dagr

import kotlin.time.TimeSource


internal data class PacketStatus(
    val packet: Packet,
    val timeSent: TimeSource.Monotonic.ValueTimeMark
)

