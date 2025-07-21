<div>
    <div>
        <img src="https://img.shields.io/maven-central/v/io.github.remmerw/asen" alt="Kotlin Maven Version" />
        <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android" alt="Badge Android" />
        <img src="https://img.shields.io/badge/Platform-JVM-8A2BE2.svg?logo=openjdk" alt="Badge JVM" />
    </div>
</div>

## Dagr
UDP Client-Server API (Simple TCP based on UDP)



## Integration

```
    
kotlin {
    sourceSets {
        commonMain.dependencies {
            ...
            implementation("io.github.remmerw:dagr:0.0.5")
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

        val server = newDagr(0, object : Acceptor {
            override suspend fun accept(
                connection: Connection
            ) {

                try {
                    while (true) {
                        val cid = connection.readInt()
                        assertEquals(cid, 1)

                        val buffer = Buffer()
                        buffer.write(serverData)
                        connection.writeBuffer(buffer)
                    }
                } catch (_: Throwable) {
                } finally {
                    connection.close()
                }
            }
        })
        
        
        val remoteAddress = server.localAddress()

        val connection = connectDagr(remoteAddress, 1)!!

        connection.writeInt(1)

        val data = connection.readByteArray(serverData.size)
        assertContentEquals(data, serverData)

        connection.close()
        server.shutdown()
    }
```




