package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DagrFinishTest {


    @Test
    fun testFinishServer() {


        val serverData = Random.nextBytes(1000000)

        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
                thread {

                    assertEquals(request, 0L)

                    val buffer = Buffer()
                    buffer.writeInt(serverData.size)
                    buffer.write(serverData)
                    writer.writeBuffer(buffer)

                }
            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection =
            assertNotNull(
                connectDagr(remoteAddress, 1)
            )

        val sink = Buffer()
        connection.request(0, sink)
        assertContentEquals(sink.readByteArray(), serverData)
        connection.close()

        Thread.sleep(50)

        assertTrue(!connection.isConnected)
        assertEquals(server.numIncomingConnections(), 0)

        server.shutdown()
    }

}