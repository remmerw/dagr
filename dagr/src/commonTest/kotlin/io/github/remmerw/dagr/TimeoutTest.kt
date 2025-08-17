package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {

    @Test
    fun timeout(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(acceptor = object : Acceptor {
            override suspend fun request(request: Long, offset: Long): Data {
                return Data(Buffer(), 0)
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!
        delay(20)

        assertEquals(server.numIncomingConnections(), 1)

        Thread.sleep((5000 + 1000).toLong())
        // now there should be no connection

        assertEquals(server.numIncomingConnections(), 0)


        connection.close()
        server.shutdown()
    }
}