package io.github.remmerw.dagr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RawSinkTest {


    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun testRawSink(): Unit = runBlocking(Dispatchers.IO) {

        val dataSize = UShort.MAX_VALUE.toInt()

        val serverData = Random.nextBytes(dataSize)

        val server = newDagr(acceptor = object : Acceptor {
            override suspend fun request(writer: Writer, request: Long) {

                assertEquals(request, 0L)

                val buffer = Buffer()
                buffer.write(serverData)
                writer.writeBuffer(buffer, dataSize)

            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection =
            assertNotNull(
                connectDagr(
                    remoteAddress
                )
            )


        val path = Path(SystemTemporaryDirectory, Uuid.random().toHexString())
        SystemFileSystem.sink(path, false).use { sink ->
            connection.request(0, sink)
        }

        val metadata = SystemFileSystem.metadataOrNull(path)
        checkNotNull(metadata) { "Path has no metadata" }
        assertEquals(metadata.size.toInt(), dataSize)

        connection.close()
        server.shutdown()
    }

}