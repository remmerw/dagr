package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
                connection: Connection,
                buffer: Buffer
            ) {
                assertEquals(buffer.readByteArray().decodeToString(), clientText)

                val buffer = Buffer()
                buffer.write(serverText.encodeToByteArray())
                connection.write(buffer)
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
        buffer.write(clientText.encodeToByteArray())
        val response = connection.request(1, buffer)

        val text = response.readByteArray().decodeToString()
        assertEquals(text, serverText)


        connection.close()
        server.shutdown()
    }



    @Test
    fun testDagrMoreReply(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverData = Random.nextBytes(UShort.MAX_VALUE.toInt())
        val clientText = "Hello World"
        val server = newDagr(serverKeys, 4444, object : Responder {
            override suspend fun data(
                connection: Connection,
                buffer: Buffer
            ) {
                assertEquals(buffer.readByteArray().decodeToString(), clientText)

                val buffer = Buffer()
                buffer.write(serverData)
                connection.write(buffer)
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
        buffer.write(clientText.encodeToByteArray())
        val response = connection.request(1, buffer)

        val data = response.readByteArray()
        assertContentEquals(data, serverData)


        connection.close()
        server.shutdown()
    }

}