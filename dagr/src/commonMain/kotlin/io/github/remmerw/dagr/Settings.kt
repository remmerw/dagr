package io.github.remmerw.dagr


object Settings {
    internal const val PAKET_OFFSET = 10L

    internal const val DATAGRAM_MIN_SIZE: Int = 9  // (Type(1) + PaketNumber(8))

    // "In the absence of these mechanisms, QUIC endpoints SHOULD NOT send IP
    //   packets larger than 1280 bytes.  Assuming the minimum IP header size,
    //   this results in a QUIC maximum packet size of 1232 bytes for IPv6 and
    //   1252 bytes for IPv4."
    // As it is not know (yet) whether running over IP4 or IP6, take the smallest of the two:
    internal const val MAX_PACKET_SIZE: Int = 1232

    // https://datatracker.ietf.org/doc/html/rfc9002#name-variables-of-interest-2
    // The sender's current maximum payload size. This does not include UDP or IP overhead.
    // The max datagram size is used for congestion window computations. An endpoint sets the
    // value of this variable based on its Path Maximum Transmission Unit (PMTU; see Section
    // 14.2 of [QUIC-TRANSPORT]), with a minimum value of 1200 bytes.
    internal const val MAX_DATAGRAM_SIZE: Int = MAX_PACKET_SIZE - DATAGRAM_MIN_SIZE

    internal const val MIN_DELAY: Int = 25
    internal const val MAX_DELAY: Int = 100
    internal const val MAX_IDLE_TIMEOUT: Int = 15000
    internal const val PING_INTERVAL: Int = 5000

    internal const val MISSED_PACKETS: Int = 25

    const val MAX_SIZE: Int = 65536

}
