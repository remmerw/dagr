package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeepAliveTest {

    @Test
    fun keepAlive(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val server = newDagr(serverKeys, 0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = assertNotNull(
            connectDagr(
                clientPeerId,
                serverPeerId, remoteAddress, connector, 1
            )
        )

        connection.enableKeepAlive()

        assertEquals(connector.connections().size, 1)
        assertEquals(server.connections().size, 1)
        assertEquals(connector.connections().first().remotePeerId(), serverPeerId)
        assertEquals(server.connections().first().remotePeerId(), clientPeerId)
        assertEquals(connector.connections(serverPeerId).size, 1)
        assertEquals(server.connections(clientPeerId).size, 1)


        delay((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.connections().size, 1)
        assertEquals(connector.connections().size, 1)


        connection.close()
        server.shutdown()
    }
}