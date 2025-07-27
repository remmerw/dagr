package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {

    @Test
    fun timeout() {


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

        val connection = checkNotNull(
            connectDagr(remoteAddress, 1)
        )

        assertEquals(server.incoming().size, 1)
        assertEquals(server.outgoing().size, 0)

        Thread.sleep((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.incoming().size, 0)
        assertEquals(server.outgoing().size, 0)


        connection.close()
        server.shutdown()
    }
}