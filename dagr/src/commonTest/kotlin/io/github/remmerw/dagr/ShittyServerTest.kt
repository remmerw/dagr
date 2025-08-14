package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

class ShittyServerTest {

    @Test
    fun shittyServer(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(acceptor = object : Acceptor {
            override suspend fun request(request: Long): Data {
                // server not responding
                Thread.sleep(SOCKET_TIMEOUT.toLong() * 1000)
                return Data(Buffer(), 0)
            }
        })

        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = checkNotNull(
            connectDagr(remoteAddress)
        )

        var failed = false
        try {
            val sink = Buffer()
            connection.request(0, sink)
        } catch (_: Throwable) {
            // ignore
            failed = true
        }
        assertTrue(failed)
        connection.close()
        server.shutdown()
    }
}