package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class DagrTest {

    @Test
    fun testDagr() : Unit = runBlocking(Dispatchers.IO) {
        val server = newDagr(4444)

        val client = newDagr(0)
        //client.connect()

        client.shutdown()
        server.shutdown()
    }
}