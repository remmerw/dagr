package io.github.remmerw.dagr

import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.sign
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
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
import kotlin.random.Random

class Dagr(val keys: Keys, val responder: Responder) : Terminate {
    private val selectorManager = SelectorManager(Dispatchers.IO)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val connections: MutableMap<InetSocketAddress, Connection> = ConcurrentMap()
    private val jobs: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private val handler: MutableMap<InetSocketAddress, Job> = ConcurrentMap()
    private var socket: BoundDatagramSocket? = null

    suspend fun startup(port: Int) {
        socket = aSocket(selectorManager).udp().bind(
            localAddress = InetSocketAddress("::", port)
        )

        scope.launch {
            runReceiver()
        }

    }

    fun localAddress(): InetSocketAddress {
        require(socket != null) { "Server is not yet started" }
        return socket?.localAddress as InetSocketAddress
    }

    suspend fun punching(isa: InetSocketAddress): Boolean {
        try {
            val buffer = Buffer()
            buffer.writeByte(Random.nextInt(2, 75).toByte())
            buffer.write(Random.nextBytes(Random.nextInt(25, 75)))
            val datagram = Datagram(buffer, isa)
            socket!!.send(datagram)
            return true
        } catch (throwable: Throwable) {
            debug("Error Punching " + throwable.message)
            return false
        }
    }

    private suspend fun runReceiver(): Unit = coroutineScope {
        while (isActive) {
            val receivedPacket = socket!!.receive()
            try {
                process(
                    receivedPacket.packet,
                    receivedPacket.address as InetSocketAddress
                )
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }
    }

    private suspend fun process(source: Source, remoteAddress: InetSocketAddress) {
        val type = source.readByte()
        when (type) {
            0x00.toByte() -> { // 0 Verify Request
                processInitPackage(source, remoteAddress)
            }

            else -> {
                processAppPackage(type, source, remoteAddress)
            }
        }
    }

    private suspend fun processInitPackage(source: Source, remoteAddress: InetSocketAddress) {

        if (!connections.contains(remoteAddress)) {
            source.readLong()
            val id = source.readByteArray(32) // 32 hash Size of PeerId
            val remotePeerId = PeerId(id)
            val remoteToken = source.readByteArray(Settings.TOKEN_SIZE)

            val connection = object : Connection(
                socket!!, remotePeerId,
                remoteAddress, this
            ) {}

            connections.put(remoteAddress, connection)


            jobs.put(remoteAddress, scope.launch {
                connection.runRequester()
            })


            handler.put(remoteAddress, scope.launch {
                responder.handleConnection(connection)
            })


            connection.state(State.Connected)

            val signature = sign(keys, remoteToken)

            val packet = createVerifyResponsePacket(
                connection.fetchPackageNumber(),
                true, signature
            )

            connection.sendPacket(packet)
        }
    }

    private suspend fun processAppPackage(
        type: Byte,
        source: Source,
        remoteAddress: InetSocketAddress
    ) {

        val connection = connections[remoteAddress]
        if (connection != null) {
            val packetNumber = source.readLong()

            when (type) {
                0x03.toByte() -> { // data frame
                    connection.sendAck(packetNumber)
                    connection.process(parseDataFrame(source))
                    connection.packetIdleProcessed()
                }

                0x05.toByte() -> {
                    connection.process(parseConnectionCloseFrame(source))
                    connection.packetIdleProcessed()
                }

                0x01.toByte() -> {
                    connection.sendAck(packetNumber)
                    connection.packetIdleProcessed()
                }

                0x02.toByte() -> {
                    connection.lossDetector().processAckFrameReceived(packetNumber)
                    connection.packetIdleProcessed()
                }

                else -> {
                    debug("Not supported package")
                }
            }
        }
    }

    override suspend fun shutdown() {

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

        try {
            selectorManager.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    override fun connections(peerId: PeerId): Set<Connection> {
        return connections().filter { connection -> connection.remotePeerId() == peerId }.toSet()
    }

    override fun connections(): Set<Connection> {
        return connections.values.toSet()
    }

    override fun terminate(connection: Connection) {
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

suspend fun newDagr(keys: Keys, port: Int, responder: Responder): Dagr {
    val dagr = Dagr(keys, responder)

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