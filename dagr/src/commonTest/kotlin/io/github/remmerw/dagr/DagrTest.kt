package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class DagrTest {

    @Test
    fun testDagr() : Unit = runBlocking(Dispatchers.IO) {
        val serverPeerId = PeerId(Random.nextBytes(32))


        val server = newDagr( serverPeerId, 4444, Responder( object : Handler {
            override suspend fun data(
                stream: Stream,
                data: ByteArray
            ) {
                // todo
            }
        })

        )
        val remoteAddress = server.address()

        val clientPeerId = PeerId(Random.nextBytes(32))

        val client = newDagrClient( clientPeerId,
            serverPeerId, remoteAddress, Responder( object : Handler {
                override suspend fun data(
                    stream: Stream,
                    data: ByteArray
                ) {
                   // todo
                }
            }) )
        client.connect(5)

        client.close()
        server.shutdown()
    }

}