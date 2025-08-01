package io.github.remmerw.dagr

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
    fun testFinishServer() {


        val serverData = Random.nextBytes(Settings.MAX_SIZE)

        val server = newDagr(0, acceptor = object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
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
                connectDagr(remoteAddress, 1)
            )

        val sink = Buffer()
        connection.request(0, sink)
        assertContentEquals(sink.readByteArray(), serverData)
        connection.close()

        Thread.sleep(50)

        assertTrue(!connection.isConnected)

        server.shutdown()
    }

}