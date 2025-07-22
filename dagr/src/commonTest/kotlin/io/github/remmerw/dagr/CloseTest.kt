package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseTest {

    @Test
    fun testClose(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                connection.close() // close connection
            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort())

        val connection = checkNotNull(
            connectDagr(
                remoteAddress, 1
            )
        )

        delay(10) // just for settling

        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)

        connection.close()
        server.shutdown()
    }


}