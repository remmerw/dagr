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

class DagrTest {

    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(0, object : Acceptor {
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


        val connection = assertNotNull(
            connectDagr(
                remoteAddress, connector, 1
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


        val serverData = Random.nextBytes(UShort.MAX_VALUE.toInt())

        val server = newDagr( 0, object : Acceptor {
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

        val connection =
            assertNotNull(
                connectDagr(remoteAddress, connector, 1)
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