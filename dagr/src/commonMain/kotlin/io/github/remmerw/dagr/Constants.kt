package io.github.remmerw.dagr


internal enum class FrameType {
    AckFrame, DataFrame, ConnectionCloseFrame, PingFrame, VerifyRequestFrame, VerifyResponseFrame
}

