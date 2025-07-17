package io.github.remmerw.dagr


// https://www.rfc-editor.org/rfc/rfc9000.html#name-transport-parameter-definit
// 0x00	original_destination_connection_id	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x01	max_idle_timeout	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x02	stateless_reset_token	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x03	max_udp_payload_size	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x04	initial_max_data	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x05	initial_max_stream_data_bidi_local	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x06	initial_max_stream_data_bidi_remote	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x07	initial_max_stream_data_uni	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x08	initial_max_streams_bidi	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x09	initial_max_streams_uni	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0a	ack_delay_exponent	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0b	max_ack_delay	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0c	disable_active_migration	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0d	preferred_address	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0e	active_connection_id_limit	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x0f	initial_source_connection_id	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x10	retry_source_connection_id	permanent	[RFC9000, Section 18.2]	2021-02-11	IETF	[QUIC_WG]
//0x11	version_information	permanent	[RFC9368]	2022-12-16	IETF	[QUIC_WG]
//0x20	max_datagram_frame_size	permanent	[RFC9221]	2021-10-20	IETF	[QUIC_WG]
//0x173e	discard	provisional	[https://github.com/quicwg/base-drafts/wiki/Quantum-Readiness-test]	2022-06-02	[David_Schinazi]	[David_Schinazi]	Receiver silently discards.
//0x26ab	google handshake message	provisional		2022-11-01	Google	[Google]	Used to carry Google internal handshake message
//0x2ab2	grease_quic_bit	permanent	[RFC9287]	2022-07-13	IETF	[QUIC_WG]
//0x3127	initial_rtt	provisional		2021-10-20	Google	[Google]	Initial RTT in microseconds
//0x3128	google_connection_options	provisional		2021-10-20	Google	[Google]	Google connection options for experimentation
//0x3129	user_agent	provisional		2021-10-20	Google	[Google]	User agent string (deprecated)
//0x4752	google_version	provisional		2021-10-20	Google	[Google]	Google QUIC version downgrade prevention
//0x0f739bbc1b666d05	enable_multipath	provisional	[draft-ietf-quic-multipath-05, Section 3]	2023-07-26	Yanmei Liu	[Yanmei_Liu]


internal enum class BlockReason {
    DATA_BLOCKED,
    STREAM_DATA_BLOCKED,
    NOT_BLOCKED
}

internal enum class FrameType {
    AckFrame, StreamFrame, ConnectionCloseFrame,
    DataBlockedFrame, StreamDataBlockedFrame,
    MaxDataFrame, PingFrame, PaddingFrame,
    ResetStreamFrame, MaxStreamDataFrame, MaxStreamsFrame,
    VerifyFrame, StopSendingFrame, StreamsBlockedFrame
}

