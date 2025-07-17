package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

class DagrTest {

    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverText = "Moin"
        val clientText = "Hello World"
        val server = newDagr(serverKeys, 4444, object : Responder {
            override suspend fun data(
                stream: Stream,
                buffer: Buffer
            ) {
                assertEquals(buffer.readByteArray().decodeToString(), clientText)

                val buffer = Buffer()
                buffer.write(serverText.encodeToByteArray())
                stream.write(buffer)
            }
        }

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = newDagrClient(clientPeerId, serverPeerId, remoteAddress, connector)
        connection.connect(1)


        val stream = createStream(connection)

        val buffer = Buffer()
        buffer.write(clientText.encodeToByteArray())
        val response = stream.request(1, buffer)

        val text = response.readByteArray().decodeToString()
        assertEquals(text, serverText)


        connection.close()
        server.shutdown()
    }

}