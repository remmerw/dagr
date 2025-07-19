package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseStreamTest {

    @Test
    fun testClose(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId


        val server = newDagr(serverKeys, 0, object : Responder {
            override suspend fun handleConnection(
                connection: Connection
            ) {
                println("Connection close")
                connection.close()
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId


        val connection = checkNotNull(
            connectDagr(
                clientPeerId, serverPeerId,
                remoteAddress, connector, 1
            )
        )
        val buffer = Buffer()
        buffer.writeInt(5)
        connection.write(buffer)

        delay(20)
        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)
        assertEquals(connector.connections().size, 0)

        connector.shutdown()
        server.shutdown()
    }


}