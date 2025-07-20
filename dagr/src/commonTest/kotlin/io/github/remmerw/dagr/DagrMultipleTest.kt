package io.github.remmerw.dagr

import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLong
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

        val dataSize = UShort.MAX_VALUE.toInt()

        var serverData: ByteArray? = null

        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                val reader = connection.openReadChannel()

                while (true) {
                    reader.readLong() // nothing to do

                    val buffer = Buffer()
                    serverData = Random.nextBytes(dataSize)
                    buffer.write(serverData)
                    connection.writeBuffer(buffer)
                }
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()


        val connection =
            assertNotNull(
                connectDagr(
                    remoteAddress, connector, 1
                )
            )


        val readChannel = connection.openReadChannel()
        repeat(1000) {
            val buffer = Buffer()
            buffer.writeLong(0)
            connection.writeBuffer(buffer)


            val data = readChannel.readByteArray(dataSize)
            assertContentEquals(data, serverData)
        }

        connection.close()
        server.shutdown()
    }

}