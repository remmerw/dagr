package io.github.remmerw.dagr

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Source

class Dagr() {
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val connections: MutableMap<Long, Connection> = ConcurrentMap()

    private var socket: BoundDatagramSocket? = null
    suspend fun startup() {


        socket = aSocket(selectorManager).udp().bind(
            InetSocketAddress("::", 0)
        )

        selectorManager.launch {
            runReceiver()
        }

    }

    private suspend fun runReceiver() {
        try {
            while (selectorManager.isActive) {
                val receivedPacket = socket!!.receive()
                try {
                    process(receivedPacket.packet)
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

    private fun process(source: Source) {
        val type = source.readByte()
        when (type) {
            0.toByte() -> { // 0 INIT
                processInitPackage(source)
            }

            1.toByte() -> { // 1 APP
                processAppPackage(source)
            }
        }
    }

    private fun processInitPackage(source: Source) {

    }

    private fun processAppPackage(source: Source) {
        val dcid = source.readLong()
        val connection = connections[dcid]
        if (connection != null) {
            // todo connection.process()
        }
    }

    fun shutdown() {

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

suspend fun newDagr(port: Int): Dagr {
    val dagr = Dagr()

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