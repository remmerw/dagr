package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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


        val client = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {
            }
        })

        // first connection
        assertNotNull(client.connect(remoteAddress))



        assertEquals(server.numIncomingConnections(), 1)
        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(client.numIncomingConnections(), 0)
        assertEquals(client.numOutgoingConnections(), 1)


        // seconde connection (same address no effect)
        assertNotNull(client.connect(remoteAddress))

        assertEquals(server.numIncomingConnections(), 2)
        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(client.numIncomingConnections(), 0)
        assertEquals(client.numOutgoingConnections(), 2)



        client.shutdown()
        server.shutdown()
    }
}