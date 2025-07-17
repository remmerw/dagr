package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertTrue

class PunchingTest {

    @Test
    fun testPunching(): Unit = runBlocking(Dispatchers.IO) {

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

        val connection = newDagrClient(clientPeerId, serverPeerId, remoteAddress, connector)
        connection.connect(1)

        val clientAddress = connection.address()
        assertTrue(server.punching(clientAddress))
        delay(1000) // Note: punch try is visible via debug output


        connection.close()
        server.shutdown()
    }

}