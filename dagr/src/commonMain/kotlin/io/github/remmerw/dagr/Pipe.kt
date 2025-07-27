/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.remmerw.dagr

import kotlinx.io.Buffer
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toTimeUnit

/**
 * A source and a sink that are attached. The sink's output is the source's input. Typically each
 * is accessed by its own thread: a producer thread writes data to the sink and a consumer thread
 * reads data from the source.
 *
 * This class uses a buffer to decouple source and sink. This buffer has a user-specified maximum
 * size. When a producer thread outruns its consumer the buffer fills up and eventually writes to
 * the sink will block until the consumer has caught up. Symmetrically, if a consumer outruns its
 * producer reads block until there is data to be read. Limits on the amount of time spent waiting
 * for the other party can be configured with [timeouts][Timeout] on the source and the
 * sink.
 *
 * When the sink is closed, source reads will continue to complete normally until the buffer has
 * been exhausted. At that point reads will return -1, indicating the end of the stream. But if the
 * source is closed first, writes to the sink will immediately fail with an [Exception].
 *
 * A pipe may be canceled to immediately fail writes to the sink and reads from the source.
 */
internal class Pipe(private val maxBufferSize: Long) {
    private val buffer = Buffer()
    private var canceled = false
    private var sinkClosed = false
    private var sourceClosed = false

    private val lock: ReentrantLock = ReentrantLock()
    private val condition: Condition = lock.newCondition()

    init {
        require(maxBufferSize >= 1L) { "maxBufferSize < 1: $maxBufferSize" }
    }

    fun readBuffer(sink: Buffer, count: Int, timeout: Int? = null) {
        if (timeout != null) {
            source.timeout().timeout(timeout.toLong(), TimeUnit.SECONDS)
        } else {
            source.timeout().clearTimeout()
        }
        var bytes = count.toLong()
        do {
            bytes -= source.read(sink, bytes)
        } while (bytes > 0)
    }

    val sink = object : Sink {
        private val timeout = Timeout()

        override fun write(bytes: ByteArray) {
            var byteCount = bytes.size.toLong()
            lock.withLock {
                check(!sinkClosed) { "closed" }
                if (canceled) throw InterruptedException("canceled")

                while (byteCount > 0) {


                    if (sourceClosed) throw Exception("source is closed")

                    val bufferSpaceAvailable = maxBufferSize - buffer.size
                    if (bufferSpaceAvailable == 0L) {
                        timeout.awaitSignal(condition) // Wait until the source drains the buffer.
                        if (canceled) throw InterruptedException("canceled")
                        continue
                    }

                    val bytesToWrite = minOf(bufferSpaceAvailable, byteCount)
                    buffer.write(bytes, 0, bytesToWrite.toInt())
                    byteCount -= bytesToWrite
                    condition.signalAll() // Notify the source that it can resume reading.
                }
            }
        }

        override fun flush() {
            lock.withLock {
                check(!sinkClosed) { "closed" }
                if (canceled) throw InterruptedException("canceled")


                if (sourceClosed && buffer.size > 0L) {
                    throw Exception("source is closed")
                }
            }
        }

        override fun close() {
            lock.withLock {
                if (sinkClosed) return


                if (sourceClosed && buffer.size > 0L) throw Exception("source is closed")
                sinkClosed = true
                condition.signalAll() // Notify the source that no more bytes are coming.
            }
        }

        override fun timeout(): Timeout = timeout
    }

    val source = object : Source {
        private val timeout = Timeout()

        override fun read(sink: Buffer, byteCount: Long): Long {
            lock.withLock {
                check(!sourceClosed) { "closed" }
                if (canceled) throw InterruptedException("canceled")

                while (buffer.size == 0L) {
                    if (sinkClosed) return -1L
                    timeout.awaitSignal(condition) // Wait until the sink fills the buffer.
                    if (canceled) throw InterruptedException("canceled")
                }

                val result = buffer.readAtMostTo(sink, byteCount)
                condition.signalAll() // Notify the sink that it can resume writing.
                return result
            }
        }

        override fun close() {
            lock.withLock {
                sourceClosed = true
                condition.signalAll() // Notify the sink that no more bytes are desired.
            }
        }

        override fun timeout(): Timeout = timeout
    }


    /**
     * Fail any in-flight and future operations. After canceling:
     *
     *  * Any attempt to write or flush [sink] will fail immediately with an [Exception].
     *  * Any attempt to read [source] will fail immediately with an [Exception].
     *
     * Closing the source and the sink will complete normally even after a pipe has been canceled. If
     * this sink has been folded, closing it will close the folded sink. This operation may block.
     *
     * This operation may be called by any thread at any time. It is safe to call concurrently while
     * operating on the source or the sink.
     */
    fun cancel() {
        lock.withLock {
            canceled = true
            buffer.clear()
            condition.signalAll() // Notify the source and sink that they're canceled.
        }
    }
}

interface Sink : Closeable {
    /** Removes `byteCount` bytes from `source` and appends them to this.  */
    fun write(bytes: ByteArray)

    /** Pushes all buffered bytes to their final destination.  */
    fun flush()

    /** Returns the timeout for this sink.  */
    fun timeout(): Timeout

    /**
     * Pushes all buffered bytes to their final destination and releases the resources held by this
     * sink. It is an error to write a closed sink. It is safe to close a sink more than once.
     */
    override fun close()
}


/**
 * Supplies a stream of bytes. Use this interface to read data from wherever it's located: from the
 * network, storage, or a buffer in memory. Sources may be layered to transform supplied data, such
 * as to decompress, decrypt, or remove protocol framing.
 *
 * Most applications shouldn't operate on a source directly, but rather on a BufferedSource which
 * is both more efficient and more convenient. Use buffer to wrap any source with a buffer.
 *
 * Sources are easy to test: just use a Buffer in your tests, and fill it with the data your
 * application is to read.
 *
 * ### Comparison with InputStream

 * This interface is functionally equivalent to java.io.InputStream.
 *
 * `InputStream` requires multiple layers when consumed data is heterogeneous: a `DataInputStream`
 * for primitive values, a `BufferedInputStream` for buffering, and `InputStreamReader` for strings.
 * This library uses `BufferedSource` for all of the above.
 *
 * Source avoids the impossible-to-implement [available()] java.io.InputStream.available method.
 * Instead callers specify how many bytes they [require] BufferedSource.require.
 *
 * Source omits the unsafe-to-compose [mark and reset][java.io.InputStream.mark] state that's
 * tracked by `InputStream`; instead, callers just buffer what they need.
 *
 * When implementing a source, you don't need to worry about the [read()][java.io.InputStream.read]
 * method that is awkward to implement efficiently and returns one of 257 possible values.
 *
 * And source has a stronger `skip` method: BufferedSource.skip won't return prematurely.
 *
 * ### Interop with InputStream
 *
 * Use source to adapt an `InputStream` to a source. Use BufferedSource.inputStream to adapt a
 * source to an `InputStream`.
 */
interface Source : Closeable {
    /**
     * Removes at least 1, and up to `byteCount` bytes from this and appends them to `sink`. Returns
     * the number of bytes read, or -1 if this source is exhausted.
     */
    fun read(sink: Buffer, byteCount: Long): Long

    /** Returns the timeout for this source.  */
    fun timeout(): Timeout

    /**
     * Closes this source and releases the resources held by this source. It is an error to read a
     * closed source. It is safe to close a source more than once.
     */
    override fun close()
}


open class Timeout {
    /**
     * True if `deadlineNanoTime` is defined. There is no equivalent to null or 0 for
     * [System.nanoTime].
     */
    private var hasDeadline = false
    private var deadlineNanoTime = 0L
    private var timeoutNanos = 0L

    /**
     * A sentinel that is updated to a new object on each call to [cancel]. Sample this property
     * before and after an operation to test if the timeout was canceled during the operation.
     */
    @Volatile
    private var cancelMark: Any? = null

    /**
     * Wait at most `timeout` time before aborting an operation. Using a per-operation timeout means
     * that as long as forward progress is being made, no sequence of operations will fail.
     *
     * If `timeout == 0`, operations will run indefinitely. (Operating system timeouts may still
     * apply.)
     */
    open fun timeout(timeout: Long, unit: TimeUnit): Timeout {
        require(timeout >= 0) { "timeout < 0: $timeout" }
        timeoutNanos = unit.toNanos(timeout)
        return this
    }

    /** Returns the timeout in nanoseconds, or `0` for no timeout. */
    open fun timeoutNanos(): Long = timeoutNanos

    /** Returns true if a deadline is enabled. */
    open fun hasDeadline(): Boolean = hasDeadline

    /**
     * Returns the [nano time][System.nanoTime] when the deadline will be reached.
     *
     * @throws IllegalStateException if no deadline is set.
     */
    open fun deadlineNanoTime(): Long {
        check(hasDeadline) { "No deadline" }
        return deadlineNanoTime
    }

    /**
     * Sets the [nano time][System.nanoTime] when the deadline will be reached. All operations must
     * complete before this time. Use a deadline to set a maximum bound on the time spent on a
     * sequence of operations.
     */
    open fun deadlineNanoTime(deadlineNanoTime: Long): Timeout {
        this.hasDeadline = true
        this.deadlineNanoTime = deadlineNanoTime
        return this
    }

    /** Set a deadline of now plus `duration` time.  */
    fun deadline(duration: Long, unit: TimeUnit): Timeout {
        require(duration > 0) { "duration <= 0: $duration" }
        return deadlineNanoTime(System.nanoTime() + unit.toNanos(duration))
    }

    /** Clears the timeout. Operating system timeouts may still apply. */
    open fun clearTimeout(): Timeout {
        timeoutNanos = 0
        return this
    }

    /** Clears the deadline. */
    open fun clearDeadline(): Timeout {
        hasDeadline = false
        return this
    }

    /**
     * Throws an [Exception] if the deadline has been reached or if the current thread
     * has been interrupted. This method doesn't detect timeouts; that should be implemented to
     * asynchronously abort an in-progress operation.
     */

    open fun throwIfReached() {
        if (Thread.currentThread().isInterrupted) {
            // If the current thread has been interrupted.
            throw Exception("interrupted")
        }

        if (hasDeadline && deadlineNanoTime - System.nanoTime() <= 0) {
            throw Exception("deadline reached")
        }
    }

    /**
     * Prevent all current applications of this timeout from firing. Use this when a time-limited
     * operation should no longer be time-limited because the nature of the operation has changed.
     *
     * This function does not mutate the [deadlineNanoTime] or [timeoutNanos] properties of this
     * timeout. It only applies to active operations that are limited by this timeout, and applies by
     * allowing those operations to run indefinitely.
     *
     * Subclasses that override this method must call `super.cancel()`.
     */
    open fun cancel() {
        cancelMark = Any()
    }

    /**
     * Waits on `monitor` until it is signaled. Throws [Exception] if either the thread
     * is interrupted or if this timeout elapses before `monitor` is signaled.
     * The caller must hold the lock that monitor is bound to.
     *
     * Here's a sample class that uses `awaitSignal()` to await a specific state. Note that the
     * call is made within a loop to avoid unnecessary waiting and to mitigate spurious notifications.
     *
     * ```java
     * class Dice {
     *   Random random = new Random();
     *   int latestTotal;
     *
     *   ReentrantLock lock = new ReentrantLock();
     *   Condition condition = lock.newCondition();
     *
     *   public void roll() {
     *     lock.withLock {
     *       latestTotal = 2 + random.nextInt(6) + random.nextInt(6);
     *       System.out.println("Rolled " + latestTotal);
     *       condition.signalAll();
     *     }
     *   }
     *
     *   public void rollAtFixedRate(int period, TimeUnit timeUnit) {
     *     Executors.newScheduledThreadPool(0).scheduleAtFixedRate(new Runnable() {
     *       public void run() {
     *         roll();
     *       }
     *     }, 0, period, timeUnit);
     *   }
     *
     *   public void awaitTotal(Timeout timeout, int total)
     *       throws InterruptedIOException {
     *     lock.withLock {
     *       while (latestTotal != total) {
     *         timeout.awaitSignal(this);
     *       }
     *     }
     *   }
     * }
     * ```
     */

    open fun awaitSignal(condition: Condition) {
        try {
            val hasDeadline = hasDeadline()
            val timeoutNanos = timeoutNanos()

            if (!hasDeadline && timeoutNanos == 0L) {
                condition.await() // There is no timeout: wait forever.
                return
            }

            // Compute how long we'll wait.
            val waitNanos = if (hasDeadline && timeoutNanos != 0L) {
                val deadlineNanos = deadlineNanoTime() - System.nanoTime()
                minOf(timeoutNanos, deadlineNanos)
            } else if (hasDeadline) {
                deadlineNanoTime() - System.nanoTime()
            } else {
                timeoutNanos
            }

            if (waitNanos <= 0) throw TimeoutException("timeout")

            val cancelMarkBefore = cancelMark

            // Attempt to wait that long. This will return early if the monitor is notified.
            val nanosRemaining = condition.awaitNanos(waitNanos)

            // If there's time remaining, we probably got the call we were waiting for.
            if (nanosRemaining > 0) return

            // Return without throwing if this timeout was canceled while we were waiting. Note that this
            // return is a 'spurious wakeup' because Condition.signal() was not called.
            if (cancelMark !== cancelMarkBefore) return

            throw TimeoutException("timeout")
        } catch (timeoutException: TimeoutException) {
            Thread.currentThread().interrupt() // Retain interrupted status.
            throw timeoutException
        }
    }

    /**
     * Waits on `monitor` until it is notified. Throws [Exception] if either the thread
     * is interrupted or if this timeout elapses before `monitor` is notified. The caller must be
     * synchronized on `monitor`.
     *
     * Here's a sample class that uses `waitUntilNotified()` to await a specific state. Note that the
     * call is made within a loop to avoid unnecessary waiting and to mitigate spurious notifications.
     *
     * ```java
     * class Dice {
     *   Random random = new Random();
     *   int latestTotal;
     *
     *   public synchronized void roll() {
     *     latestTotal = 2 + random.nextInt(6) + random.nextInt(6);
     *     System.out.println("Rolled " + latestTotal);
     *     notifyAll();
     *   }
     *
     *   public void rollAtFixedRate(int period, TimeUnit timeUnit) {
     *     Executors.newScheduledThreadPool(0).scheduleAtFixedRate(new Runnable() {
     *       public void run() {
     *         roll();
     *       }
     *     }, 0, period, timeUnit);
     *   }
     *
     *   public synchronized void awaitTotal(Timeout timeout, int total)
     *       throws InterruptedIOException {
     *     while (latestTotal != total) {
     *       timeout.waitUntilNotified(this);
     *     }
     *   }
     * }
     * ```
     */
    open fun waitUntilNotified(monitor: Any) {
        try {
            val hasDeadline = hasDeadline()
            val timeoutNanos = timeoutNanos()

            if (!hasDeadline && timeoutNanos == 0L) {
                (monitor as Object).wait() // There is no timeout: wait forever.
                return
            }

            // Compute how long we'll wait.
            val start = System.nanoTime()
            val waitNanos = if (hasDeadline && timeoutNanos != 0L) {
                val deadlineNanos = deadlineNanoTime() - start
                minOf(timeoutNanos, deadlineNanos)
            } else if (hasDeadline) {
                deadlineNanoTime() - start
            } else {
                timeoutNanos
            }

            if (waitNanos <= 0) throw TimeoutException("timeout")

            val cancelMarkBefore = cancelMark

            // Attempt to wait that long. This will return early if the monitor is notified.
            val waitMillis = waitNanos / 1000000L
            (monitor as Object).wait(waitMillis, (waitNanos - waitMillis * 1000000L).toInt())
            val elapsedNanos = System.nanoTime() - start

            // If there's time remaining, we probably got the call we were waiting for.
            if (elapsedNanos < waitNanos) return

            // Return without throwing if this timeout was canceled while we were waiting. Note that this
            // return is a 'spurious wakeup' because Object.notify() was not called.
            if (cancelMark !== cancelMarkBefore) return

            throw TimeoutException("timeout")
        } catch (timeoutException: TimeoutException) {
            Thread.currentThread().interrupt() // Retain interrupted status.
            throw timeoutException
        }
    }

    /**
     * Applies the minimum intersection between this timeout and `other`, run `block`, then finally
     * rollback this timeout's values.
     */
    inline fun <T> intersectWith(other: Timeout, block: () -> T): T {
        val originalTimeout = this.timeoutNanos()
        this.timeout(
            minTimeout(
                other.timeoutNanos(),
                this.timeoutNanos()
            ), TimeUnit.NANOSECONDS
        )

        if (this.hasDeadline()) {
            val originalDeadline = this.deadlineNanoTime()
            if (other.hasDeadline()) {
                this.deadlineNanoTime(
                    kotlin.math.min(
                        this.deadlineNanoTime(),
                        other.deadlineNanoTime()
                    )
                )
            }
            try {
                return block()
            } finally {
                this.timeout(originalTimeout, TimeUnit.NANOSECONDS)
                if (other.hasDeadline()) {
                    this.deadlineNanoTime(originalDeadline)
                }
            }
        } else {
            if (other.hasDeadline()) {
                this.deadlineNanoTime(other.deadlineNanoTime())
            }
            try {
                return block()
            } finally {
                this.timeout(originalTimeout, TimeUnit.NANOSECONDS)
                if (other.hasDeadline()) {
                    this.clearDeadline()
                }
            }
        }
    }

    companion object {

        fun Timeout.timeout(timeout: Long, unit: DurationUnit): Timeout {
            return timeout(timeout, unit.toTimeUnit())
        }

        fun Timeout.timeout(duration: Duration): Timeout {
            return timeout(duration.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        }

        fun minTimeout(aNanos: Long, bNanos: Long) = when {
            aNanos == 0L -> bNanos
            bNanos == 0L -> aNanos
            aNanos < bNanos -> aNanos
            else -> bNanos
        }
    }
}
