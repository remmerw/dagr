package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionsTest {

    @Test
    fun connections(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val first = connectDagr(remoteAddress)!!

        assertEquals(server.numIncomingConnections(), 1)


        val second = connectDagr(remoteAddress)!!

        assertEquals(server.numIncomingConnections(), 2)

        first.close()
        second.close()
        server.shutdown()
    }
}