package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KeepAliveTest {

    @Test
    fun keepAlive(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection = assertNotNull(
            connectDagr(
                remoteAddress, 1
            )
        )

        connection.enableKeepAlive()

        assertEquals(server.incoming().size, 1)


        delay((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.incoming().size, 1)


        connection.close()
        server.shutdown()
    }
}