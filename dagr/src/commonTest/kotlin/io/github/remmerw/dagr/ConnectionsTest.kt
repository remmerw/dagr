package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionsTest {

    @Test
    fun connections(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = Short.MAX_VALUE.toInt()
        val serverData = Random.nextBytes(dataSize)

        val server = newDagr(0, 5, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
                assertEquals(request, 0L)
                val buffer = Buffer()
                buffer.writeInt(serverData.size)
                buffer.write(serverData)
                writer.writeBuffer(buffer)
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val first = connectDagr(remoteAddress)!!
        delay(20)
        assertEquals(server.numIncomingConnections(), 1)


        val second = connectDagr(remoteAddress)!!
        delay(20)
        assertEquals(server.numIncomingConnections(), 2)

        first.close()
        assertTrue(first.isClosed)

        val sink = Buffer()
        val size = second.request(0, sink)
        assertEquals(size, dataSize)
        assertContentEquals(sink.readByteArray(), serverData)

        second.close()

        assertTrue(second.isClosed)


        server.shutdown()
    }
}