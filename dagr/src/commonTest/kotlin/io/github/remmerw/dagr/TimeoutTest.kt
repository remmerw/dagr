package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {

    @Test
    fun timeout(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!


        assertEquals(server.numIncomingConnections(), 1)

        Thread.sleep((5000 + 1000).toLong())
        // now it should be no connections

        assertEquals(server.numIncomingConnections(), 0)


        connection.close()
        server.shutdown()
    }
}