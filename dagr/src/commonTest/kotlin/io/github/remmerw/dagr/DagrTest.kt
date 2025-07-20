package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class DagrTest {

    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(serverKeys, 0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                val reader = connection.openReadChannel()


                while (true) {
                    reader.readLong() // nothing to do

                    val buffer = Buffer()
                    buffer.write(serverData)
                    connection.writeBuffer(buffer)
                }
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection = assertNotNull(
            connectDagr(
                clientPeerId,
                serverPeerId, remoteAddress, connector, 1
            )
        )


        val readChannel = connection.openReadChannel()


        val buffer = Buffer()
        buffer.writeLong(0)
        connection.writeBuffer(buffer)


        val data = readChannel.readByteArray(serverData.size)
        assertContentEquals(data, serverData)

        connection.close()
        server.shutdown()
    }


    @Test
    fun testDagrMoreReply(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId

        val serverData = Random.nextBytes(UShort.MAX_VALUE.toInt())

        val server = newDagr(serverKeys, 0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                val reader = connection.openReadChannel()

                while (true) {
                    reader.readLong() // nothing to do

                    val buffer = Buffer()
                    buffer.write(serverData)
                    connection.writeBuffer(buffer)
                }
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
        val clientKeys = generateKeys()
        val clientPeerId = clientKeys.peerId

        val connection =
            assertNotNull(
                connectDagr(
                    clientPeerId,
                    serverPeerId, remoteAddress, connector, 1
                )
            )


        val readChannel = connection.openReadChannel()

        val buffer = Buffer()
        buffer.writeLong(0)
        connection.writeBuffer(buffer)


        val data = readChannel.readByteArray(serverData.size)
        assertContentEquals(data, serverData)


        connection.close()
        server.shutdown()
    }

}