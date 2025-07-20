package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PunchingTest {

    @Test
    fun testPunching(): Unit = runBlocking(Dispatchers.IO) {


        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {
            }
        }

        )
        val remoteAddress = server.localAddress()
        val connector = Connector()


        val connection = assertNotNull(
            connectDagr(
                remoteAddress, connector, 1
            )
        )

        val clientAddress = connection.localAddress()
        assertTrue(server.punching(clientAddress))
        delay(1000) // Note: punch try is visible via debug output


        connection.close()
        server.shutdown()
    }

}