package io.github.remmerw.dagr

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.random.Random

class Dagr(port: Int, val acceptor: Acceptor) : Listener {

    private val connections: MutableMap<InetSocketAddress, Connection> = ConcurrentHashMap()

    @OptIn(ExperimentalAtomicApi::class)
    private val incoming = AtomicInt(0)

    @OptIn(ExperimentalAtomicApi::class)
    private val outgoing = AtomicInt(0)

    private var socket: DatagramSocket = DatagramSocket(port)
    private val initializeDone = Semaphore(0)
    private val lock = ReentrantLock()
    private val receiver = thread(
        start = true,
        isDaemon = true,
        name = "Dagr Receiver",
        priority = Thread.MAX_PRIORITY
    ) {
        runReceiver()
    }

    private val maintenance = thread(
        start = true,
        isDaemon = true,
        name = "Dagr Maintenance",
        priority = Thread.MAX_PRIORITY
    ) {
        runMaintenance()
    }


    @OptIn(ExperimentalAtomicApi::class)
    fun numIncomingConnections(): Int {
        return incoming.load()
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun numOutgoingConnections(): Int {
        return outgoing.load()
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

    private fun runMaintenance() {
        try {
            while (true) {
                var lost = 0
                connections.values.forEach { connection ->
                    try {
                        lost += connection.maintenance()
                    } catch (throwable: Throwable) {
                        debug(throwable)
                    }
                }

                if (lost > 0) {
                    Thread.sleep(Settings.MIN_DELAY.toLong())
                } else {
                    Thread.sleep(Settings.MAX_DELAY.toLong())
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: SocketException) {
        } catch (throwable: Throwable) {
            debug(throwable)
            shutdown()
        }
    }

    private fun runReceiver() {
        val data = ByteArray(Settings.MAX_PACKET_SIZE)
        try {
            while (true) {

                val receivedPacket = DatagramPacket(data, data.size)

                socket.receive(receivedPacket)

                val remoteAddress = receivedPacket.socketAddress as InetSocketAddress
                val connection = receiveConnection(remoteAddress)
                connection.processDatagram(receivedPacket)

            }
        } catch (_: InterruptedException) {
        } catch (_: SocketException) {
        } catch (throwable: Throwable) {
            debug(throwable)
            shutdown()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun register(connection: Connection) {
        connections.put(connection.remoteAddress(), connection)
        if (connection.incoming()) {
            incoming.incrementAndFetch()
        } else {
            outgoing.incrementAndFetch()
        }
    }

    private fun receiveConnection(remoteAddress: InetSocketAddress): Connection {
        val connection = connections[remoteAddress]
        if (connection != null) {
            return connection
        }

        val newConnection = Connection(socket, remoteAddress, true, acceptor, this)
        register(newConnection)


        return newConnection
    }

    fun shutdown() {

        try {
            connections.values.forEach { connection ->
                connection.close()
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            receiver.interrupt()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            maintenance.interrupt()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            socket.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    fun incoming(): List<Connection> {
        return connections.values.filter { connection -> connection.incoming() }
    }

    fun outgoing(): List<Connection> {
        return connections.values.filter { connection -> !connection.incoming() }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override fun close(connection: Connection) {
        val removed = connections.remove(connection.remoteAddress())
        if (removed != null) {
            if (connection.incoming()) {
                incoming.decrementAndFetch()
            } else {
                outgoing.decrementAndFetch()
            }
        }


    }

    override fun connected(connection: Connection) {

        if (!connection.incoming()) {
            try {
                initializeDone.release()
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
    }


    fun connect(remoteAddress: InetSocketAddress, timeout: Int): Connection? {
        lock.withLock { // maybe this should be improved and multiple connects are possible
            val previous = connections[remoteAddress]
            if (previous != null) {
                return previous
            }

            val connection = Connection(socket, remoteAddress, false, acceptor, this)

            register(connection)

            connection.sendPacket(1, createPingPacket(), true)

            try {
                if (initializeDone.tryAcquire(timeout.toLong(), TimeUnit.SECONDS)) {
                    return connection
                }
            } catch (_: InterruptedException) {
            } catch (throwable: Throwable) {
                debug(throwable)
            }
            return null
        }

    }
}


fun connectDagr(
    remoteAddress: InetSocketAddress,
    timeout: Int
): Connection? {
    val dagr = newDagr(0, object : Acceptor {
        override fun request(writer: Writer, request: Long) {
        }
    })
    return dagr.connect(remoteAddress, timeout)
}


fun newDagr(port: Int, acceptor: Acceptor): Dagr {
    return Dagr(port, acceptor)
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