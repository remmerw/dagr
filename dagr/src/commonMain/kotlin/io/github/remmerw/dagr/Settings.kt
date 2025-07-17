package io.github.remmerw.dagr


internal object Settings {
    const val SIGNATURE_SIZE = 64
    const val TOKEN_SIZE = 32

    const val UNREGISTER: Long = -1

    const val RECEIVER_MAX_DATA_INCREMENT_FACTOR = 0.10f

    // Minimum stream frame size: frame payloadType (1), stream id (1..8), offset (1..8), length (1..2), data (1...)
    // Note that in practice stream id and offset will seldom / never occupy 8 bytes, so the minimum leaves more room for data.
    const val MIN_FRAME_SIZE = 1 + 8 + 8 + 2 + 1

    const val FACTOR = 2

    const val TIME_THRESHOLD = 9f / 8f

    const val ACK_FREQUENCY_TWO = 2

    const val NOT_DEFINED: Int = -1

    // https://datatracker.ietf.org/doc/html/rfc9002#name-variables-of-interest-2
    // The sender's current maximum payload size. This does not include UDP or IP overhead.
    // The max datagram size is used for congestion window computations. An endpoint sets the
    // value of this variable based on its Path Maximum Transmission Unit (PMTU; see Section
    // 14.2 of [QUIC-TRANSPORT]), with a minimum value of 1200 bytes.
    const val MAX_DATAGRAM_SIZE: Int = 1200


    // https://datatracker.ietf.org/doc/html/rfc9002#initial-cwnd
    // QUIC begins every connection in slow start with the congestion window set to an
    // initial value. Endpoints SHOULD use an initial congestion window of ten times the
    // maximum datagram size (max_datagram_size), while limiting the window to the larger
    // of 14,720 bytes or twice the maximum datagram size. This follows the analysis
    // and recommendations in [RFC6928], increasing the byte limit to account for the smaller
    // 8-byte overhead of UDP compared to the 20-byte overhead for TCP.
    //
    // If the maximum datagram size changes during the connection, the initial congestion
    // window SHOULD be recalculated with the new size. If the maximum datagram size is
    // decreased in order to complete the handshake, the congestion window SHOULD be set
    // to the new initial congestion window.
    //
    // Prior to validating the client's address, the server can be further limited by
    // the anti-amplification limit as specified in Section 8.1 of [QUIC-TRANSPORT]. T
    // hough the anti-amplification limit can prevent the congestion window from being fully
    // utilized and therefore slow down the increase in congestion window, it does not
    // directly affect the congestion window.
    //
    // The minimum congestion window is the smallest value the congestion window can attain
    // in response to loss, an increase in the peer-reported ECN-CE count, or persistent
    // congestion. The RECOMMENDED value is 2 * max_datagram_size.
    // Endpoints SHOULD use an initial congestion window of ten times the maximum datagram
    // size (max_datagram_size), while limiting the window to the larger of 14,720
    // bytes or twice the maximum datagram size.
    const val INITIAL_CONGESTION_WINDOW: Int = 10 * MAX_DATAGRAM_SIZE

    const val MINIMUM_CONGESTION_WINDOW: Int = 2 * MAX_DATAGRAM_SIZE


    // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#appendix-B.1
    // "Reduction in congestion window when a new loss event is detected.  The RECOMMENDED value is 0.5."
    const val CONGESTION_LOSS_REDUCTION_FACTOR: Int = 2 // note how it is used

    // https://tools.ietf.org/html/draft-ietf-quic-transport-17#section-14.1:
    // "An endpoint SHOULD use Datagram Packetization Layer PMTU Discovery
    //   ([DPLPMTUD]) or implement Path MTU Discovery (PMTUD) [RFC1191]
    //   [RFC8201] ..."
    // "In the absence of these mechanisms, QUIC endpoints SHOULD NOT send IP
    //   packets larger than 1280 bytes.  Assuming the minimum IP header size,
    //   this results in a QUIC maximum packet size of 1232 bytes for IPv6 and
    //   1252 bytes for IPv4."
    // As it is not know (yet) whether running over IP4 or IP6, take the smallest of the two:
    const val MAX_PACKAGE_SIZE: Int = 1232

    // https://tools.ietf.org/html/draft-ietf-quic-recovery-20#section-6.2
    // "If no previous RTT is available, or if the network
    // changes, the initial RTT SHOULD be set to 500ms"
    const val INITIAL_RTT: Int = 500

    const val DEFAULT_ACTIVE_CONNECTION_ID_LIMIT: Int = 2 // default init value
    const val DEFAULT_MAX_ACK_DELAY: Int = 25 // default init value
    const val DEFAULT_ACK_DELAY_EXPONENT: Int = 3 // default init value
    const val DEFAULT_MAX_UDP_PAYLOAD_SIZE: Int = 65527 // default init value

    const val ACTIVE_CONNECTION_ID_LIMIT: Int =
        4 // all other remote libp2p clients have same value
    const val MAX_ACK_DELAY: Int = 26 // all other remote libp2p clients have same value
    const val ACK_DELAY_EXPONENT: Int = 3 // all other remote libp2p clients have same value
    const val MAX_UDP_PAYLOAD_SIZE: Int =
        1452 // all other remote libp2p clients have same value
    const val MAX_STREAMS_BIDI: Int = 256 // all other remote libp2p clients have same value
    const val MAX_STREAMS_UNI: Int = 5 // all other remote libp2p clients have same value
    const val INITIAL_MAX_DATA: Long = 786432 // all other remote libp2p clients have same value
    const val INITIAL_MAX_STREAM_DATA: Long =
        524288 // all other remote libp2p clients have same value
    const val MAX_DATAGRAM_FRAME_SIZE: Int =
        1200 // all other remote libp2p clients have same value // TODO make sure datagram size
    const val MAX_IDLE_TIMEOUT: Int = 30000 // all remote libp2p clients have value 30000
    const val PING_INTERVAL: Int = 5000 // MAX_IDLE_TIMEOUT / 2

    // https://www.rfc-editor.org/rfc/rfc9000.html#name-handshake-packet
    // "A Handshake packet uses long headers with a payloadType value of 0x02, ..."
    const val HANDSHAKE_V1_TYPE: Int = 2

    // https://www.ietf.org/archive/id/draft-ietf-quic-v2-01.html#name-long-header-packet-types
    // "Handshake packets use a packet payloadType field of 0b11."
    const val HANDSHAKE_V2_TYPE: Int = 3

    // https://www.rfc-editor.org/rfc/rfc9000.html#name-initial-packet
    // "An Initial packet uses long headers with a payloadType value of 0x00."
    const val INITIAL_V1_TYPE: Int = 0

    // https://www.ietf.org/archive/id/draft-ietf-quic-v2-01.html#name-long-header-packet-types
    // Initial packets use a packet payloadType field of 0b01.
    const val INITIAL_V2_TYPE: Int = 1

    const val MAX_PACKET_SIZE: Int = 1500


    // NOTE: this is the default value for the ack scale
    // ((int) Math.pow(2, Settings.ACK_DELAY_EXPONENT)) and it is valid as long
    // Settings.ACK_DELAY_EXPONENT is used as default for client and server and
    // do not change by a user defined value
    const val ACK_DELAY_SCALE: Int = 8

    val BYTES_EMPTY: ByteArray = ByteArray(0)
}
