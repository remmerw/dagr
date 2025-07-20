package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {

    @Test
    fun timeout(): Unit = runBlocking(Dispatchers.IO) {



        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()
      0
        val connection = checkNotNull(
            connectDagr(remoteAddress, connector, 1)
        )


        assertEquals(connector.connections().size, 1)
        assertEquals(server.connections().size, 1)

        delay((Settings.MAX_IDLE_TIMEOUT + 2000).toLong())
        // now it should be no connections

        assertEquals(server.connections().size, 0)
        assertEquals(connector.connections().size, 0)

        delay(5000)

        connection.close()
        server.shutdown()
    }
}