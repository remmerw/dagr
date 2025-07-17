package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.generateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class DagrTest {

    @Test
    fun testDagr() : Unit = runBlocking(Dispatchers.IO) {

        val serverKeys = generateKeys()

        val serverPeerId = serverKeys.peerId


        val server = newDagr( serverKeys, 4444, Responder( object : Handler {
            override suspend fun data(
                stream: Stream,
                data: ByteArray
            ) {
                // todo
            }
        })

        )
        val remoteAddress = server.address()
        val connector = Connector()
        val clientKeys = generateKeys()

        val client = newDagrClient(clientKeys, serverPeerId, remoteAddress,
            connector, Responder( object : Handler {
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