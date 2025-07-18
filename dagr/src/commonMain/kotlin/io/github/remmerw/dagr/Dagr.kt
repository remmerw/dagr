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
    private var socket: BoundDatagramSocket? = null

    suspend fun startup(port: Int) {
        socket = aSocket(selectorManager).udp().bind(
            InetSocketAddress("::", port)
        )

        scope.launch {
            runReceiver()
        }

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

    fun address(): InetSocketAddress {
        return socket!!.localAddress as InetSocketAddress
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
            0.toByte() -> { // 0 INIT
                processInitPackage(source, remoteAddress)
            }

            1.toByte() -> { // 1 APP
                processAppPackage(source, remoteAddress)
            }
        }
    }

    private suspend fun processInitPackage(source: Source, remoteAddress: InetSocketAddress) {
        val id = source.readByteArray(32) // 32 hash Size of PeerId
        val remotePeerId = PeerId(id)
        val packetNumber = source.readLong()

        if (!connections.contains(remoteAddress)) {
            val connection =
                object : Connection(
                    socket!!, keys.peerId,
                    remotePeerId, remoteAddress, this
                ) {


                    override suspend fun process(verifyFrame: FrameReceived.VerifyRequestFrame) {
                        val remoteToken = verifyFrame.token
                        try {

                            state(State.Connected)
                            discard(Level.INIT)

                            val signature = sign(keys, remoteToken)

                            insertRequest(Level.APP, createVerifyResponseFrame(signature))
                        } catch (throwable: Throwable) {
                            debug("Verification failed " + throwable.message)

                            scheduledClose(
                                Level.APP,
                                TransportError(TransportError.Code.PROTOCOL_VIOLATION)
                            )
                        }
                    }

                    override suspend fun process(verifyFrame: FrameReceived.VerifyResponseFrame) {
                        // not yet supported (maybe in the future)
                    }


                    override fun responder(): Responder? {
                        return responder
                    }
                }

            connections.put(remoteAddress, connection)

            val job = scope.launch {
                connection.runRequester()
            }
            jobs.put(remoteAddress, job)


            connection.processPacket(
                PacketHeader(Level.INIT, source.readByteArray(), packetNumber)
            )
        }
    }

    private suspend fun processAppPackage(source: Source, remoteAddress: InetSocketAddress) {


        val connection = connections[remoteAddress]
        if (connection != null) {

            val packetNumber = source.readLong()
            connection.processPacket(
                PacketHeader(Level.APP, source.readByteArray(), packetNumber)
            )
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
            selectorManager.close()
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