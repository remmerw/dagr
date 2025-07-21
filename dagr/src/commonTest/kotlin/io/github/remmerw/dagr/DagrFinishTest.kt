package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DagrFinishTest {



    @Test
    fun testFinishServer(): Unit = runBlocking(Dispatchers.IO) {


        val serverData = Random.nextBytes(1000000)

        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {

                try {
                    val cid = connection.readLong() // nothing to do
                    assertEquals(cid, 0L)

                    val buffer = Buffer()
                    buffer.write(serverData)
                    connection.writeBuffer(buffer)

                } catch (_: Throwable) {
                } finally {
                    connection.close() // directly close after writing
                }
            }
        }

        )
        val remoteAddress = server.localAddress()

        val connection =
            assertNotNull(
                connectDagr(remoteAddress, 1)
            )


        val buffer = Buffer()
        buffer.writeLong(0)
        connection.writeBuffer(buffer)


        val data = connection.readByteArray(serverData.size)
        assertContentEquals(data, serverData)


        connection.close()
        server.shutdown()
    }

}