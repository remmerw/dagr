package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {

    @Test
    fun timeout(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val server = newDagr(serverKeys, 1111, object : Responder {
            override suspend fun handleConnection(
                connection: Connection
            ) {
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = checkNotNull(
            connectDagr(
                clientPeerId,
                serverPeerId, remoteAddress, connector, 1
            )
        )


        assertEquals(connector.connections().size, 1)
        assertEquals(server.connections().size, 1)
        assertEquals(connector.connections().first().remotePeerId(), serverPeerId)
        assertEquals(server.connections().first().remotePeerId(), clientPeerId)

        delay((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.connections().size, 0)
        assertEquals(connector.connections().size, 0)

        delay(5000)

        connection.close()
        server.shutdown()
    }
}