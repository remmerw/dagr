package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloseStreamTest {

    @Test
    fun testClose(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId


        val server = newDagr(serverKeys, 0, object : Responder {
            override suspend fun handleConnection(
                connection: Connection
            ) {
                connection.close()
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        assertNull(
            connectDagr(
                clientPeerId, serverPeerId,
                remoteAddress, connector, 1
            )
        )

        assertEquals(server.connections().size, 0)
        assertEquals(connector.connections().size, 0)

        connector.shutdown()
        server.shutdown()
    }


}