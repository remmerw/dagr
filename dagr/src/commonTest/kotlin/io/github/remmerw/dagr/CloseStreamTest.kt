package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseStreamTest {

    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId


        val server = newDagr(serverKeys, 4444, object : Responder {
            override suspend fun data(
                stream: Stream,
                buffer: Buffer
            ) {
                stream.close()
            }
        }

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()

        val connection = newDagrClient(clientKeys, serverPeerId, remoteAddress, connector)
        connection.connect(1)


        val stream = createStream(connection)

        val buffer = Buffer()
        buffer.write("Solar".encodeToByteArray())
        val response = stream.request(1, buffer)

        assertEquals(response.size, 0)
        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)

        connection.close()
        server.shutdown()
    }

}