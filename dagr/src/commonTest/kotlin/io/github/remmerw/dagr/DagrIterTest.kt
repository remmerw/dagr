package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DagrIterTest {


    @Test
    fun testDagrIter() {

        val dataSize = UShort.MAX_VALUE.toInt()

        var serverData: ByteArray? = null

        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {
                thread {
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
                        println("Thread closed")
                    }
                }
            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


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