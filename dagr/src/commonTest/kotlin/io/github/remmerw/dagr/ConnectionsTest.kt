package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConnectionsTest {

    @Test
    fun connections() {


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


        val client = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {
            }
        })

        // first connection
        assertNotNull(client.connect(remoteAddress, 1))



        assertEquals(server.numIncomingConnections(), 1)
        assertEquals(server.numOutgoingConnections(), 0)
        assertEquals(client.incoming().size, 0)
        assertEquals(client.outgoing().size, 1)


        // seconde connection (same address no effect)
        assertNotNull(client.connect(remoteAddress, 1))

        assertEquals(server.incoming().size, 1)
        assertEquals(server.outgoing().size, 0)
        assertEquals(client.incoming().size, 0)
        assertEquals(client.outgoing().size, 1)



        client.shutdown()
        server.shutdown()
    }
}