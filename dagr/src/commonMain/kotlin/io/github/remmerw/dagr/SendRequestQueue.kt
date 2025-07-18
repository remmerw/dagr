package io.github.remmerw.dagr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


// todo remove
internal class SendRequestQueue {
    private val requestQueue: ArrayDeque<SendRequest> = ArrayDeque()
    private val mutex = Mutex()

    suspend fun appendRequest(fixedFrame: Frame) {
        mutex.withLock {
            requestQueue.addLast(
                SendRequest(
                    fixedFrame.frameLength(),
                    object : FrameSupplier {
                        override suspend fun nextFrame(maxSize: Int): Frame? {
                            return fixedFrame
                        }
                    }
                ))
        }
    }

    suspend fun insertRequest(fixedFrame: Frame) {
        mutex.withLock {
            requestQueue.addFirst(
                SendRequest(
                    fixedFrame.frameLength(), object : FrameSupplier {
                        override suspend fun nextFrame(maxSize: Int): Frame? {
                            return fixedFrame
                        }
                    }
                )
            )
        }
    }

    suspend fun hasRequests(): Boolean {
        mutex.withLock {
            return !requestQueue.isEmpty()
        }
    }


    suspend fun next(maxFrameLength: Int): SendRequest? {
        if (maxFrameLength < 1) {  // Minimum frame size is 1: some frames (e.g. ping) are just a payloadType field.
            // Forget it
            return null
        }

        mutex.withLock {
            val iterator = requestQueue.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next.estimatedSize <= maxFrameLength) {
                    iterator.remove()
                    return next
                }
            }
        }
        // Couldn't find one.
        return null
    }

    suspend fun clear() {
        mutex.withLock {
            requestQueue.clear()
        }
    }
}

