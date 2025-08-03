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

class DagrTest {

    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {


                assertEquals(request, 1)

                val buffer = Buffer()
                buffer.writeInt(serverData.size)
                buffer.write(serverData)
                writer.writeBuffer(buffer)


            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!


        val buffer = Buffer()
        connection.request(1, buffer)
        assertContentEquals(buffer.readByteArray(), serverData)

        connection.close()
        server.shutdown()
    }


    @Test
    fun testDagrMoreReply(): Unit = runBlocking(Dispatchers.IO) {


        val serverData = Random.nextBytes(Short.MAX_VALUE.toInt())

        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
                assertEquals(request, 0L)
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
                connectDagr(remoteAddress)
            )

        val sink = Buffer()
        connection.request(0, sink)
        assertContentEquals(sink.readByteArray(), serverData)


        connection.close()
        server.shutdown()
    }

}