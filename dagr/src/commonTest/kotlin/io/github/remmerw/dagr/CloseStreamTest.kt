package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseStreamTest {

    @Test
    fun testClose(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr( 0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                println("Connection close")
                connection.close()
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()


        val connection = checkNotNull(
            connectDagr(
                remoteAddress, connector, 1
            )
        )

        delay(10) // just for settling

        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)
        assertEquals(connector.connections().size, 0)

        connector.shutdown()
        server.shutdown()
    }


}