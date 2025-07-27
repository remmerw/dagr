package io.github.remmerw.dagr

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ShittyServerTest {

    @Test
    fun shittyServer() {


        val server = newDagr(0, object : Acceptor {
            override fun accept(
                connection: Connection
            ) {

                // server not responding
            }
        }

        )

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = checkNotNull(
            connectDagr(remoteAddress, 1)
        )


        connection.writeLong(0)

        try {
            connection.readByteArray(1000, 2) // expect data of size 1000
            fail("Exception expected")
        } catch (timout: TimeoutException) {
            assertEquals(timout.message, "timeout")
        } catch (throwable: Throwable) {
            fail("" + throwable.message)
        }


        connection.close()
        server.shutdown()
    }
}