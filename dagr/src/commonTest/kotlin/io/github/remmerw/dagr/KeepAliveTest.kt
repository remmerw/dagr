package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeepAliveTest {

    @Test
    fun keepAlive() {


        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection = assertNotNull(
            connectDagr(
                remoteAddress, 1
            )
        )

        connection.enableKeepAlive()

        assertEquals(server.incoming().size, 1)


        Thread.sleep((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.incoming().size, 1)


        connection.close()
        server.shutdown()
    }
}