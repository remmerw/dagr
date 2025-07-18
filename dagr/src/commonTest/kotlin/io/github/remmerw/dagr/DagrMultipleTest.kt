package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DagrMultipleTest {


    @Test
    fun testDagrMultiple(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverData = Random.nextBytes(UShort.MAX_VALUE.toInt())
        val clientText = "Hello World"
        val server = newDagr(serverKeys, 7777, object : Responder {
            override suspend fun data(
                connection: Connection,
                data: ByteArray
            ) {
                assertEquals(data.decodeToString(), clientText)

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


        repeat(1000) {
            val buffer = Buffer()
            buffer.write(clientText.encodeToByteArray())
            val response = connection.request(1, buffer)

            assertContentEquals(response,serverData)
        }

        connection.close()
        server.shutdown()
    }

}