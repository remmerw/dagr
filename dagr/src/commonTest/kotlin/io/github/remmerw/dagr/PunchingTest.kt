package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PunchingTest {

    @Test
    fun testPunching(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val server = newDagr(serverKeys, 2222, object : Acceptor {
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
                clientPeerId, serverPeerId,
                remoteAddress, connector, 1
            )
        )

        val clientAddress = connection.localAddress()
        assertTrue(server.punching(clientAddress))
        delay(1000) // Note: punch try is visible via debug output


        connection.close()
        server.shutdown()
    }

}