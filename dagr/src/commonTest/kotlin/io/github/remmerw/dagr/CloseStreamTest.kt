package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
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


        val server = newDagr(serverKeys, 4444, object : Responder {
            override suspend fun data(
                connection: Connection,
                data: ByteArray
            ) {
                connection.close()
            }
        }

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = newDagrClient(clientPeerId, serverPeerId, remoteAddress, connector)
        connection.connect(1)

        val buffer = Buffer()
        buffer.write("Solar".encodeToByteArray())
        val response = connection.request(1, buffer)

        assertEquals(response.size, 0)
        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)

        connection.close()
        server.shutdown()
    }



}