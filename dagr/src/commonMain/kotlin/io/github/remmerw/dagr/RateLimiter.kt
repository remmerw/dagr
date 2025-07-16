package io.github.remmerw.dagr

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

internal interface Limiter {
    suspend fun run()
}

internal class RateLimiter {
    @OptIn(ExperimentalAtomicApi::class)
    private val nextOccasion = AtomicInt(1)

    @OptIn(ExperimentalAtomicApi::class)
    private val attempts = AtomicInt(0)


    @OptIn(ExperimentalAtomicApi::class)
    suspend fun execute(runnable: Limiter) {
        if (attempts.incrementAndFetch() == nextOccasion.load()) {
            runnable.run()
            nextOccasion.store(nextOccasion.load() * Settings.FACTOR)
        }
    }

}
