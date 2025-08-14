<div>
    <div>
        <img src="https://img.shields.io/maven-central/v/io.github.remmerw/asen" alt="Kotlin Maven Version" />
        <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android" alt="Badge Android" />
        <img src="https://img.shields.io/badge/Platform-JVM-8A2BE2.svg?logo=openjdk" alt="Badge JVM" />
    </div>
</div>

## Dagr
TCP Client-Server API 



## Integration

```
    
kotlin {
    sourceSets {
        commonMain.dependencies {
            ...
            implementation("io.github.remmerw:dagr:0.3.6")
        }
        ...
    }
}
    
```

## API

```
    @Test
    fun testDagr(): Unit = runBlocking(Dispatchers.IO) {

        val serverData = "Moin".encodeToByteArray()

        val server = newDagr(port = 0, timeout = 5, acceptor = object : Acceptor {
            override suspend fun request(request: Long): Data {

                assertEquals(request, 1)

                val buffer = Buffer()
                buffer.write(serverData)
                return Data(buffer, serverData.size)
            }
        })


        val remoteAddress = InetSocketAddress(
            InetAddress.getLoopbackAddress(), server.localPort()
        )

        val connection = connectDagr(remoteAddress)!!


        val buffer = Buffer()
        connection.request(1, buffer)
        assertContentEquals(buffer.readByteArray(), serverData)

        connection.close()
        server.shutdown()
    }
```




