package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

                    serverData = Random.nextBytes(dataSize)
                    connection.writeByteArray(serverData)
                }
            }
        }

        )
        val remoteAddress = server.localAddress()


        val connection =
            assertNotNull(
                connectDagr(
                    remoteAddress, 1
                )
            )


        repeat(1000) {
            connection.writeLong(0)

            val data = connection.readByteArray(dataSize)
            assertContentEquals(data, serverData)
        }

        connection.close()
        server.shutdown()
    }

}