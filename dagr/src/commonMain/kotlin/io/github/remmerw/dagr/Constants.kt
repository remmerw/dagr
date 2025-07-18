package io.github.remmerw.dagr


internal enum class BlockReason { // todo remove
    DATA_BLOCKED,
    STREAM_DATA_BLOCKED,
    NOT_BLOCKED
}

internal enum class FrameType {
    AckFrame, DataFrame, ConnectionCloseFrame,
    DataBlockedFrame,
    MaxDataFrame, PingFrame,
    VerifyRequestFrame, VerifyResponseFrame
}

