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

        val server = newDagr(serverKeys, 4444, object : Responder {
            override suspend fun data(
                stream: Stream,
                buffer: Buffer
            ) {
            }
        }

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = newDagrClient(clientKeys, serverPeerId, remoteAddress, connector)
        connection.connect(1)
        connection.enableKeepAlive()

        assertEquals(connector.connections().size, 1)
        assertEquals(server.connections().size, 1)
        assertEquals(connector.connections().first().remotePeerId(), serverPeerId)
        assertEquals(server.connections().first().remotePeerId(), clientPeerId)

        delay(30000)
        // now it should be no connections

        assertEquals(server.connections().size, 1)
        assertEquals(connector.connections().size, 1)


        connection.close()
        server.shutdown()
    }
}