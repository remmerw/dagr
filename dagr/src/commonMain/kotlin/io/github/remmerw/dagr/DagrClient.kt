package io.github.remmerw.dagr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress


internal class DagrClient internal constructor(
    private val socket: DatagramSocket,
    remoteAddress: InetSocketAddress,
    listener: Listener,
) : Connection(socket, remoteAddress, listener) {

    private val initializeDone = Semaphore(1, 1)
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun connect(timeout: Int): Connection? {

        try {
            startInitialize()
        } catch (_: Throwable) {
            terminate()
            return null
        }

        try {
            return withTimeout(timeout * 1000L) {
                initializeDone.acquire()

                if (state() != State.Connected) {
                    terminate()
                    return@withTimeout null
                }
                return@withTimeout this@DagrClient
            }
        } catch (_: Throwable) {
            terminate()
            return null
        }

    }


    private suspend fun startInitialize() {

        scope.launch {
            try {
                val data = ByteArray(Settings.MAX_PACKET_SIZE)
                while (isActive) {

                    val receivedPacket = DatagramPacket(data, Settings.MAX_PACKET_SIZE)
                    socket.receive(receivedPacket)

                    processDatagram(receivedPacket) {
                        initializeDone.release()
                    }

                }
            } catch (throwable: Throwable) {
                if (socket.isConnected) {
                    debug(throwable)
                }
            } finally {
                terminate()
            }
        }

        scope.launch {
            runRequester()
        }


        sendPacket(1, createPingPacket(), true)
    }

    override fun terminate() {
        super.terminate()

        try {
            initializeDone.release()
        } catch (_: Throwable) {
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

}

suspend fun connectDagr(
    remoteAddress: InetSocketAddress,
    timeout: Int,
    listener: Listener = object : Listener {
        override fun close(connection: Connection) {
        }
    }
): Connection? {
    val socket = DatagramSocket()
    val dagr = DagrClient(socket, remoteAddress, listener)
    return dagr.connect(timeout)
}


