package io.github.remmerw.dagr

enum class State {
    Created,
    Connected,
    Closed;

    val isClosed: Boolean
        get() = this == Closed

    val isConnected: Boolean
        get() = this == Connected
}