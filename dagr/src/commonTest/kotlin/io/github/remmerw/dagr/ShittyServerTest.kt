package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.fail

class ShittyServerTest {

    @Test
    fun shittyServer(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(acceptor = object : Acceptor {
            override suspend fun request(request: Long): Data {
                // server not responding
                return Data(Buffer(), 0)
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = checkNotNull(
            connectDagr(remoteAddress)
        )

        try {
            val sink = Buffer()
            connection.request(0, sink)
            fail("Exception expected")
        } catch (_: Throwable) {
            // ignore
        }

        connection.close()
        server.shutdown()
    }
}