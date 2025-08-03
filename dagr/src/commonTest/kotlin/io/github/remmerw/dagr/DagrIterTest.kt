package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DagrIterTest {


    @Test
    fun testDagrIter(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = Short.MAX_VALUE.toInt()

        var serverData: ByteArray? = null

        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {

                assertEquals(request, 0L)
                serverData = Random.nextBytes(dataSize)
                val buffer = Buffer()
                buffer.writeInt(serverData.size)
                buffer.write(serverData)
                writer.writeBuffer(buffer)

            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection =
            assertNotNull(
                connectDagr(
                    remoteAddress
                )
            )


        repeat(2000) {
            val sink = Buffer()
            val size = connection.request(0, sink)
            assertEquals(size, dataSize)
            assertContentEquals(sink.readByteArray(), serverData)
        }

        connection.close()
        server.shutdown()
    }

}