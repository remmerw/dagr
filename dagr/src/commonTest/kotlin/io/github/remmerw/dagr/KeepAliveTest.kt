package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepAliveTest {

    @Test
    fun keepAlive(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val server = newDagr(serverKeys, 3333, object : Responder {
            override suspend fun data(
                connection: Connection,
                data: ByteArray
            ) {
            }
        }

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = newDagrClient(clientPeerId, serverPeerId, remoteAddress, connector)
        connection.connect(1)
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