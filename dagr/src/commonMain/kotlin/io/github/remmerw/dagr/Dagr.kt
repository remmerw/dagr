package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.random.Random

class Dagr(port: Int, val acceptor: Acceptor) : Listener {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connections: MutableMap<InetSocketAddress, Connection> = ConcurrentMap()
    private val jobs: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private val handler: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private var socket: DatagramSocket = DatagramSocket(port)
    private val initializeDone = Semaphore(1, 1)

    fun startup() {
        scope.launch {
            runReceiver()
        }
    }

    fun localPort(): Int {
        return socket.localPort
    }

    fun punching(remoteAddress: InetSocketAddress): Boolean {
        try {
            val buffer = Buffer()
            buffer.writeByte(Random.nextInt(10, 75).toByte())
            buffer.write(Random.nextBytes(Random.nextInt(25, 75)))

            val data = buffer.readByteArray()
            val datagram = DatagramPacket(
                data,
                data.size, remoteAddress
            )
            socket.send(datagram)
            return true
        } catch (throwable: Throwable) {
            debug("Error Punching " + throwable.message)
            return false
        }
    }

    private suspend fun runReceiver(): Unit = coroutineScope {
        val data = ByteArray(Settings.MAX_PACKET_SIZE)
        try {
            while (isActive) {

                val receivedPacket = DatagramPacket(data, data.size)

                socket.receive(receivedPacket)

                val remoteAddress = receivedPacket.socketAddress as InetSocketAddress
                val connection = receiveConnection(remoteAddress)
                connection.processDatagram(receivedPacket)

            }
        } catch (throwable: Throwable) {
            if (socket.isConnected) {
                debug(throwable)
            }
        } finally {
            shutdown()
        }
    }


    private fun receiveConnection(remoteAddress: InetSocketAddress): Connection {
        val connection = connections[remoteAddress]
        if (connection != null) {
            return connection
        }

        val newConnection = Connection(socket, remoteAddress, true, this)
        connections.put(remoteAddress, newConnection)

        return newConnection
    }

    suspend fun shutdown() {

        try {
            connections.values.forEach { connection ->
                connection.close()
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            socket.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    fun connections(): Set<Connection> {
        return connections.values.toSet()
    }

    override fun close(connection: Connection) {
        connections.remove(connection.remoteAddress())
        val job = jobs.remove(connection.remoteAddress())
        try {
            job?.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
        val handle = handler.remove(connection.remoteAddress())
        try {
            handle?.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    override fun connected(connection: Connection) {

        if (connection.incoming()) {
            val remoteAddress = connection.remoteAddress()
            jobs.put(remoteAddress, scope.launch {
                connection.runRequester()
            })
            handler.put(remoteAddress, scope.launch {
                try {
                    acceptor.accept(connection)
                } catch (_: Throwable) {
                }
            })
        } else {
            try {
                initializeDone.release()
            } catch (_: Throwable) {
            }
        }
    }


    suspend fun connect(remoteAddress: InetSocketAddress, timeout: Int): Connection? {

        val connection = Connection(socket, remoteAddress, false, this)

        connections.put(remoteAddress, connection)
        jobs.put(remoteAddress, scope.launch {
            connection.runRequester()
        })

        connection.sendPacket(1, createPingPacket(), true)

        return withTimeoutOrNull(timeout * 1000L) {
            initializeDone.acquire()
            connection
        }
    }
}


suspend fun connectDagr(
    remoteAddress: InetSocketAddress,
    timeout: Int
): Connection? {
    val dagr = newDagr(0, object : Acceptor {
        override suspend fun accept(connection: Connection) {
        }
    })
    return dagr.connect(remoteAddress, timeout)
}


fun newDagr(port: Int, acceptor: Acceptor): Dagr {
    val dagr = Dagr(port, acceptor)
    dagr.startup()
    return dagr
}


fun debug(message: String) {
    if (ERROR) {
        println(message)
    }
}


fun debug(throwable: Throwable) {
    if (ERROR) {
        throwable.printStackTrace()
    }
}

private const val ERROR: Boolean = true