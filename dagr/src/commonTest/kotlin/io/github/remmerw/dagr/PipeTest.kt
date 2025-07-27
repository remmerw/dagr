package io.github.remmerw.dagr

import kotlinx.io.Buffer
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PipeTest {

    @Test
    fun pipeTest() {

        val length = 100000
        val pipe = Pipe()

        thread {
            var counter = 0
            while (counter < length) {

                var size = Random.nextInt(10, 90)
                counter += size

                if (counter > length) {
                    size -= (counter - length)
                }

                val data = Random.nextBytes(size)

                pipe.sink.write(data)
            }
        }
        val buffer = Buffer()
        pipe.readBuffer(buffer, length)

        assertEquals(buffer.size, length.toLong())

    }


    @Test
    fun pipeTestTimeout() {

        val length = 10
        val pipe = Pipe()

        thread {
            val data = Random.nextBytes(9)
            pipe.sink.write(data)
        }
        val buffer = Buffer()
        try {
            pipe.readBuffer(buffer, length, 1)
            fail()
        } catch (_: TimeoutException){ }

    }
}