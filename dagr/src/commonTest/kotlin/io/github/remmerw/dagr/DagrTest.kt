package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class DagrTest {

    @Test
    fun testDagr() : Unit = runBlocking(Dispatchers.IO) {
        val dagr = newDagr(4444)


        dagr.shutdown()
    }
}