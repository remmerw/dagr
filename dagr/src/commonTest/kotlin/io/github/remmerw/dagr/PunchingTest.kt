package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PunchingTest {

    @Test
    fun testPunching(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {

            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection = connectDagr(remoteAddress)!!

        val clientAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), connection.localPort()
        )

        assertTrue(server.punching(clientAddress))

        connection.close()
        server.shutdown()
    }

}