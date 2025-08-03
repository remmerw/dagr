package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test

class PunchingTest {

    @Test
    fun testPunching(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(acceptor = object : Acceptor {
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
        server.punching(clientAddress)

        connection.close()
        server.shutdown()
    }

}