package io.github.remmerw.dagr

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import io.ktor.util.network.hostname
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class Dagr() : Listener {

    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val incoming: MutableSet<Connection> = ConcurrentHashMap.newKeySet()
    private val outgoing: MutableSet<Connection> = ConcurrentHashMap.newKeySet()

    private var socket: ServerSocket? = null

    fun numIncomingConnections(): Int {
        return incoming.size
    }

    fun numOutgoingConnections(): Int {
        return outgoing.size
    }

    // only after startup valid
    fun localPort(): Int {
        return socket!!.localAddress.port()
    }

    fun punching(remoteAddress: java.net.InetSocketAddress): Boolean {
        val socket = DatagramSocket()
        try {
            val datagram = DatagramPacket(
                Random.nextBytes(1200),
                1200, remoteAddress
            )
            socket.send(datagram)
            return true
        } catch (throwable: Throwable) {
            debug("Error Punching " + throwable.message)
            return false
        } finally {
            socket.close()
        }
    }


    suspend fun startup(port: Int = 0, acceptor: Acceptor) {

        socket = aSocket(selectorManager).tcp().bind("::", port)

        scope.launch {
            try {
                while (true) {

                    val clientSocket = socket!!.accept()

                    val connection = ServerConnection(clientSocket, acceptor, this@Dagr)

                    incoming.add(connection)

                    scope.launch {
                        connection.reading() // blocks till connection is closed
                    }

                }
            } catch (_: InterruptedException) {
            } catch (_: SocketException) {
            } catch (throwable: Throwable) {
                debug(throwable)
                shutdown()
            }
        }
    }


    fun shutdown() {

        try {
            incoming.forEach { connection ->
                connection.close()
            }
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            outgoing.forEach { connection ->
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
            socket?.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }

        try {
            scope.cancel()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }


    @OptIn(ExperimentalAtomicApi::class)
    override fun close(connection: Connection) {
        if (connection.incoming()) {
            incoming.remove(connection)
        } else {
            outgoing.remove(connection)
        }
    }


    suspend fun connect(remoteAddress: java.net.InetSocketAddress): ClientConnection? {

        var socket: Socket? = null
        try {
            val isa = InetSocketAddress(
                remoteAddress.hostname, remoteAddress.port
            )

            socket = aSocket(selectorManager)
                .tcp().connect(isa) {
                    socketTimeout =
                        5.toDuration(DurationUnit.SECONDS).inWholeMilliseconds
                }

            val connection = ClientConnection(socket, this)

            outgoing.add(connection)
            return connection

        } catch (throwable: Throwable) {
            debug("Connection failed " + remoteAddress + " " + throwable.message)
            socket?.close()
        }
        return null
    }
}


suspend fun connectDagr(
    remoteAddress: java.net.InetSocketAddress
): ClientConnection? {
    val dagr = Dagr()
    return dagr.connect(remoteAddress)
}


suspend fun newDagr(port: Int = 0, acceptor: Acceptor): Dagr {
    val dagr = Dagr()
    dagr.startup(port, acceptor)
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