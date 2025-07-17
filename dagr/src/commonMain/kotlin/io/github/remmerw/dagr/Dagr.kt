package io.github.remmerw.dagr

import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.sign
import io.github.remmerw.borr.verify
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.random.Random

class Dagr(val keys: Keys, val responder: Responder) {
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connections: MutableMap<InetSocketAddress, Connection> = ConcurrentMap()
    private val token = Random.nextBytes(Settings.TOKEN_SIZE)
    private var socket: BoundDatagramSocket? = null

    suspend fun startup(port: Int) {
        socket = aSocket(selectorManager).udp().bind(
            InetSocketAddress("::", port)
        )

        scope.launch {
            runReceiver()
        }

    }

    fun address(): InetSocketAddress {
        return socket!!.localAddress as InetSocketAddress
    }

    private suspend fun runReceiver() {
        try {
            while (selectorManager.isActive) {
                val receivedPacket = socket!!.receive()
                try {
                    println("Data received !!!")
                    process(
                        receivedPacket.packet,
                        receivedPacket.address as InetSocketAddress
                    )
                } catch (throwable: Throwable) {
                    debug(throwable)
                }
            }
        } catch (_: CancellationException) {
            // ignore exception
        } catch (throwable: Throwable) {
            socket?.isClosed?.let {
                if (!it) {
                    debug(throwable)
                }
            }
        } finally {
            try {
                socket?.isClosed?.let {
                    if (!it) {
                        socket!!.close()
                    }
                }
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
        println("Packer Number $packetNumber")
        println("PeerId " + id.toHexString())
        if (!connections.contains(remoteAddress)) {
            val connection = object : Connection(socket!!, remotePeerId, remoteAddress, responder) {


                override suspend fun process(verifyFrame: FrameReceived.VerifyFrame) {
                    println("verifyFrame")

                    val remoteToken = verifyFrame.token
                    val remoteSignature = verifyFrame.signature
                    println("Remote token " + remoteToken.toHexString())
                    println("Remote signature " + remoteSignature.toHexString())
                    try {
                        verify(remotePeerId, remoteToken, remoteSignature)

                        state(State.Connected)
                        discard(Level.INIT)

                        val signature = sign(keys, token)
                        sendVerifyFrame(Level.APP, token, signature)
                    } catch (throwable: Throwable) {
                        debug("Verification failed " + throwable.message)

                        // todo remove from connections
                        immediateCloseWithError(
                            Level.APP,
                            TransportError(TransportError.Code.PROTOCOL_VIOLATION)
                        )
                    }


                }

                override fun scheduleTerminate(pto: Int) {
                    TODO("Not yet implemented")
                }

                override fun activeToken(): ByteArray {
                    return token
                }

                override fun activePeerId(): PeerId {
                    return keys.peerId
                }

            }

            connections.put(remoteAddress, connection)

            scope.launch {
                connection.runRequester()
            }

            connection.processPacket(
                PacketHeader(Level.INIT, source.readByteArray(), packetNumber)
            )
        }
    }

    private suspend fun processAppPackage(source: Source, remoteAddress: InetSocketAddress) {

        val connection = connections[remoteAddress]
        if (connection != null) {

            val packetNumber = source.readLong()
            println("Packer Number $packetNumber")
            connection.processPacket(
                PacketHeader(Level.APP, source.readByteArray(), packetNumber)
            )
        }
    }

    fun shutdown() {

        try {
            connections.forEach { dcid, connection ->
                // todo connection.close()
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            socket?.isClosed?.let {
                if (!it) {
                    socket!!.close()
                }
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            selectorManager.close()
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