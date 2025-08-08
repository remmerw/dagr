package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ParallelTest {

    @OptIn(ExperimentalAtomicApi::class)
    @Test
    fun testParallelDagr(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = UShort.MAX_VALUE.toInt()
        val serverData = Random.nextBytes(dataSize)

        val server = newDagr(acceptor = object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
                assertEquals(request, 1)
                val buffer = Buffer()
                buffer.write(serverData)
                writer.writeBuffer(buffer, dataSize)
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!
        val failed = AtomicInt(0)
        val a = launch {
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

        val b = launch {
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