package io.github.remmerw.dagr

import io.ktor.utils.io.core.remaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DagrOffsetTest {

    @Test
    fun testOffsetDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(port = 0, timeout = 5, acceptor = object : Acceptor {
            override suspend fun request(request: Long, offset: Long): Data {

                assertEquals(request, 1)

                val buffer = Buffer()
                buffer.write(serverData)
                buffer.skip(offset)
                return Data(buffer, buffer.remaining)
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!


        val buffer = Buffer()
        val offset = 2L
        connection.request(1, offset, buffer)
        assertEquals(buffer.remaining, serverData.size - offset)
        assertContentEquals("in".encodeToByteArray(), buffer.readByteArray())

        connection.close()
        server.shutdown()
    }


}