package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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


                while (true) {
                    val cid = connection.readLong() // nothing to do
                    assertEquals(cid, 0L)

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


        repeat(1000) {
            val buffer = Buffer()
            buffer.writeLong(0)
            connection.writeBuffer(buffer)


            val data = connection.readByteArray(dataSize)
            assertContentEquals(data, serverData)
        }

        connection.close()
        server.shutdown()
    }

}