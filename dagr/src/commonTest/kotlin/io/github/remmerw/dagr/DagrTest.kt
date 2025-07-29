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

class DagrTest {

    @Test
    fun testDagr() {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
                thread {

                    assertEquals(request, 1)

                    val buffer = Buffer()
                    buffer.write(serverData)
                    writer.writeBuffer(buffer)

                }
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress, 1)!!


        val buffer = Buffer()
        connection.request(1, buffer, serverData.size)
        assertContentEquals(buffer.readByteArray(), serverData)

        connection.close()
        server.shutdown()
    }


    @Test
    fun testDagrMoreReply() {


        val serverData = Random.nextBytes(Short.MAX_VALUE.toInt())

        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
                thread {

                    assertEquals(request, 0L)
                    val buffer = Buffer()
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


        val data = connection.request(0, serverData.size)
        assertContentEquals(data, serverData)


        connection.close()
        server.shutdown()
    }

}