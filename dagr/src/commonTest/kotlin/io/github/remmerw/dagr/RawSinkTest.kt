package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RawSinkTest {


    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun testRawSink() {

        val dataSize = 1000000 // 1 MB

        val serverData = Random.nextBytes(dataSize)

        val server = newDagr(0, object : Acceptor {
            override fun request(
                writer: Writer, request: Long
            ) {
                thread {
                    assertEquals(request, 0L)

                    val buffer = Buffer()
                    buffer.writeInt(serverData.size)
                    buffer.write(serverData)
                    writer.writeBuffer(buffer)
                }
            }
        }

        )
        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )


        val connection =
            assertNotNull(
                connectDagr(
                    remoteAddress, 1
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