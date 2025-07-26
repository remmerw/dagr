package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
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


        val serverData = Random.nextBytes(1000000)

        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {

                try {
                    val cid = connection.readLong() // nothing to do
                    assertEquals(cid, 0L)

                    val buffer = Buffer()
                    buffer.write(serverData)
                    connection.writeBuffer(buffer)
                    connection.flush()
                } catch (_: Throwable) {
                } finally {
                    connection.close() // directly close after writing
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


        connection.writeLong(0)


        val data = connection.readByteArray(serverData.size)
        assertContentEquals(data, serverData)

        delay(50)

        assertTrue(!connection.isConnected)
        assertEquals(server.numIncomingConnections(), 0)

        connection.close()
        server.shutdown()
    }

}