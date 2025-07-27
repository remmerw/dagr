package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PunchingTest {

    @Test
    fun testPunching() {


        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {
            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection = assertNotNull(
            connectDagr(
                remoteAddress, 1
            )
        )

        val clientAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), connection.localPort()
        )
        assertTrue(server.punching(clientAddress))
        Thread.sleep(1000) // Note: punch try is visible via debug output


        connection.close()
        server.shutdown()
    }

}