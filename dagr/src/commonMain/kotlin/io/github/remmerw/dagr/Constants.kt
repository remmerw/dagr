package io.github.remmerw.dagr


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

