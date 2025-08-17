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
import kotlin.test.assertTrue

class DagrFinishTest {


    @Test
    fun testFinishServer(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = Short.MAX_VALUE.toInt()
        val serverData = Random.nextBytes(dataSize)

        val server = newDagr(0, acceptor = object : Acceptor {
            override fun request(request: Long, offset: Long): Data {
                assertEquals(request, 0L)

                val buffer = Buffer()
                buffer.write(serverData)
                return Data(buffer, dataSize.toLong())
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
        connection.request(0, 0, sink)
        assertContentEquals(sink.readByteArray(), serverData)
        connection.close()

        Thread.sleep(50)

        assertTrue(connection.isClosed)

        server.shutdown()
    }

}