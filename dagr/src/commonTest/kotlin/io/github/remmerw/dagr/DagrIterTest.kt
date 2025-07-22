package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DagrIterTest {


    @Test
    fun testDagrIter(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = UShort.MAX_VALUE.toInt()

        var serverData: ByteArray? = null

        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                try {
                    while (true) {
                        val cid = connection.readLong() // nothing to do
                        assertEquals(cid, 0L)

                        serverData = Random.nextBytes(dataSize)
                        connection.writeByteArray(serverData)
                        connection.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    connection.close()
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