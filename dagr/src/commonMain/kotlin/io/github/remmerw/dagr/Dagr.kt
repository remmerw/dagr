package io.github.remmerw.dagr

import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.random.Random

class Dagr(val responder: Acceptor) : Listener {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connections: MutableMap<InetSocketAddress, Connection> = ConcurrentMap()
    private val jobs: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private val handler: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private var socket: DatagramSocket? = null

    fun startup(port: Int) {
        socket = DatagramSocket(port)


        scope.launch {
            runReceiver()
        }

    }

    fun localAddress(): InetSocketAddress {
        require(socket != null) { "Server is not yet started" }
        return InetSocketAddress(socket!!.localAddress, socket!!.localPort)
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
            socket!!.send(datagram)
            return true
        } catch (throwable: Throwable) {
            debug("Error Punching " + throwable.message)
            return false
        }
    }

    private suspend fun runReceiver(): Unit = coroutineScope {
        val data = ByteArray(Settings.MAX_PACKET_SIZE)
        while (isActive) {

            val receivedPacket = DatagramPacket(data, Settings.MAX_PACKET_SIZE)

            socket!!.receive(receivedPacket)
            try {
                val buffer = Buffer()
                buffer.write(
                    receivedPacket.data,
                    0, receivedPacket.length
                )
                process(
                    buffer,
                    InetSocketAddress(
                        receivedPacket.address.hostName,
                        receivedPacket.port
                    )
                )
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
    }

    private suspend fun process(source: Source, remoteAddress: InetSocketAddress) {
        val type = source.readByte()
        val connection = receiveConnection(remoteAddress)
        when (type) {
            0x01.toByte() -> { // ping frame
                val packetNumber = source.readLong()
                if (connection.packetProtector(packetNumber, true)) {
                    connection.packetProcessed()
                }
            }

            0x02.toByte() -> { // ack frame
                val packetNumber = source.readLong()
                if (connection.packetProtector(packetNumber, false)) {
                    val packet = source.readLong()
                    connection.processAckFrameReceived(packet)
                    connection.packetProcessed()
                }
            }

            0x03.toByte() -> { // data frame
                val packetNumber = source.readLong()
                if (connection.packetProtector(packetNumber, true)) {
                    connection.processData(packetNumber, source)
                    connection.packetProcessed()
                }
            }

            0x04.toByte() -> { // close frame
                val packetNumber = source.readLong()
                if (connection.packetProtector(packetNumber, false)) {
                    connection.processData(parseCloseFrame(source))
                    connection.packetProcessed()
                }
            }


            else -> {
                debug("Not supported packet")
            }
        }
    }

    private fun receiveConnection(remoteAddress: InetSocketAddress): Connection {
        val connection = connections[remoteAddress]
        if (connection != null) {
            return connection
        }

        val newConnection = Connection(socket!!, remoteAddress, this)
        connections.put(remoteAddress, newConnection)

        jobs.put(remoteAddress, scope.launch {
            newConnection.runRequester()
        })


        handler.put(remoteAddress, scope.launch {
            responder.accept(newConnection)
        })
        newConnection.state(State.Connected)
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
            socket?.close()
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
}

fun newDagr(port: Int, acceptor: Acceptor): Dagr {
    val dagr = Dagr(acceptor)

    dagr.startup(port)

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