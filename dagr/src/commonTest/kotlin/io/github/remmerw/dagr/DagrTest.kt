package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DagrTest {

    @Test
    fun testDagr() {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {
                thread {
                    try {
                        while (true) {
                            val cid = connection.readInt()
                            assertEquals(cid, 1)

                            val buffer = Buffer()
                            buffer.write(serverData)
                            connection.writeBuffer(buffer)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        println("Thread closed")
                    }
                }
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress, 1)!!

        connection.writeInt(1)

        val buffer = Buffer()
        connection.readBuffer(buffer, serverData.size)
        assertContentEquals(buffer.readByteArray(), serverData)

        connection.close()
        server.shutdown()
    }


    @Test
    fun testDagrMoreReply() {


        val serverData = Random.nextBytes(UShort.MAX_VALUE.toInt())

        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {
                thread {
                    try {
                        while (true) {
                            val cid = connection.readLong() // nothing to do
                            assertEquals(cid, 0L)

                            val buffer = Buffer()
                            buffer.write(serverData)
                            connection.writeBuffer(buffer)
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