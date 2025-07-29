package io.github.remmerw.dagr

import kotlinx.io.Buffer
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
            override fun request(
                writer: Writer, request: Long
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


        try {
            val sink = Buffer()
            connection.request(0, sink, 2) // expect data of size 1000
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