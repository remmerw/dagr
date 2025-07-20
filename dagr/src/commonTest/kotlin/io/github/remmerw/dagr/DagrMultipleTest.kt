package io.github.remmerw.dagr

import io.github.remmerw.borr.generateKeys
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLong
import io.ktor.utils.io.writeBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class DagrMultipleTest {


    @Test
    fun testDagrMultiple(): Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId
        val dataSize = UShort.MAX_VALUE.toInt()

        var serverData: ByteArray? = null

        val server = newDagr(serverKeys, 0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                val reader = connection.openReadChannel()
                val writer = connection.openWriteChannel()
                while (true) {
                    reader.readLong() // nothing to do

                    val buffer = Buffer()
                    serverData = Random.nextBytes(dataSize)
                    buffer.write(serverData)
                    writer.writeBuffer(buffer)
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
                    clientPeerId, serverPeerId,
                    remoteAddress, connector, 1
                )
            )


        val readChannel = connection.openReadChannel()
        val writer = connection.openWriteChannel()
        repeat(1000) {
            val buffer = Buffer()
            buffer.writeLong(0)
            writer.writeBuffer(buffer)


            val data = readChannel.readByteArray(dataSize)
            assertContentEquals(data, serverData)
        }

        connection.close()
        server.shutdown()
    }

}