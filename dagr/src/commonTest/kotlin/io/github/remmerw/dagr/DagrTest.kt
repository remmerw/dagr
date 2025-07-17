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
    fun testDagr() : Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverText = "Moin"
        val clientText = "Hello World"
        val server = newDagr( serverKeys, 4444, Responder( object : Handler {
            override suspend fun data(
                stream: Stream,
                data: ByteArray
            ) {

                println("Server responding")
                assertEquals(data.decodeToString(), clientText)

                val buffer = Buffer()
                buffer.write(serverText.encodeToByteArray())
                stream.writeOutput(true, buffer)
            }
        })

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()

        val connection = newDagrClient(clientKeys, serverPeerId, remoteAddress,
            connector, Responder( object : Handler {
                override suspend fun data(
                    stream: Stream,
                    data: ByteArray
                ) {
                   // todo
                    println("Client responding")
                }
            }) )
        connection.connect(5)


        val stream = createStream(connection)
        val buffer = Buffer();
        buffer.write(clientText.encodeToByteArray())
        val response = stream.request(300, buffer) // todo time

        val text = response.readByteArray().decodeToString()
        assertEquals(text, serverText)

        println()


        connection.close()
        server.shutdown()
    }

}