package io.github.remmerw.dagr

enum class State {
    Created,
    Connected,
    Closing,
    Closed;

    val isClosing: Boolean
        get() = this == Closing

    val isConnected: Boolean
        get() = this == Connected
}