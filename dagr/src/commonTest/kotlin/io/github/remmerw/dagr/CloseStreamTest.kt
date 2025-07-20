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


        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
                println("Connection close")
                connection.close()
            }
        }

        )
        val remoteAddress = server.localAddress()

        val connection = checkNotNull(
            connectDagr(
                remoteAddress, 1
            )
        )

        delay(10) // just for settling

        assertTrue(!connection.isConnected)

        assertEquals(server.connections().size, 0)

        connection.close()
        server.shutdown()
    }


}