package io.github.remmerw.dagr

import kotlinx.io.Buffer


internal data class AlpnRequester(
    val stream: Stream,
    val requester: Requester,
    val streamState: StreamState
) :
    StreamHandler {
    override suspend fun data(data: Buffer) {
        try {
            StreamState.iteration(streamState, stream, data)
        } catch (exception: Exception) {
            stream.resetStream(Settings.PROTOCOL_NEGOTIATION_FAILED.toLong())
            throwable(exception)
        } catch (throwable: Throwable) {
            stream.resetStream(Settings.INTERNAL_ERROR.toLong())
            throwable(throwable)
        }
    }

    override fun terminated() {
        streamState.reset()
        requester.done()
    }

    override fun fin() {
        streamState.reset()
        requester.done()
    }

    fun throwable(throwable: Throwable) {
        debug(throwable)
        streamState.reset()
        requester.done()
    }

    override fun readFully(): Boolean {
        return false
    }

}
