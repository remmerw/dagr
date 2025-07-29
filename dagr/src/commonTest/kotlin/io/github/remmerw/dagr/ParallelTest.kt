package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ParallelTest {

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun testParallelDagr() {

        val serverData = Random.nextBytes(100000)

        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
                thread {

                    assertEquals(request, 1)
                    val buffer = Buffer()
                    buffer.writeInt(serverData.size)
                    buffer.write(serverData)
                    writer.writeBuffer(buffer)

                }
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress, 1)!!
        val failed = AtomicInt(0)
        val a = thread {
            repeat(100) {
                try {
                    val buffer = Buffer()
                    connection.request(1, buffer)
                    assertContentEquals(buffer.readByteArray(), serverData)
                } catch (throwable: Throwable) {
                    throwable.printStackTrace()
                    failed.incrementAndFetch()
                }
            }
        }

        val b = thread {
            repeat(100) {
                try {
                    val buffer = Buffer()
                    connection.request(1, buffer)
                    assertContentEquals(buffer.readByteArray(), serverData)
                } catch (throwable: Throwable) {
                    throwable.printStackTrace()
                    failed.incrementAndFetch()
                }
            }
        }

        a.join()
        b.join()

        assertEquals(0, failed.load())

        connection.close()
        server.shutdown()
    }

}