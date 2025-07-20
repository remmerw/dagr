package io.github.remmerw.dagr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
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
            runReceiver()
        }

        scope.launch {
            runRequester()
        }

        val packet = createPingPacket()

        sendPacket(packet)
    }

    override suspend fun terminate() {
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

    private suspend fun runReceiver(): Unit = coroutineScope {
        val data = ByteArray(1500)
        while (isActive) {

            val receivedPacket = DatagramPacket(data, 1500)
            socket.receive(receivedPacket)
            if (state().isClosed) {
                break
            }
            try {
                val source = Buffer()
                source.write(
                    receivedPacket.data,
                    0, receivedPacket.length
                )

                // val source = receivedPacket.packet

                val type = source.readByte()

                if (state() == State.Created) {
                    state(State.Connected)
                    initializeDone.release()
                }

                when (type) {
                    0x01.toByte() -> { // ping frame
                        val packetNumber = source.readLong()
                        if (packetProtector(packetNumber, true)) {
                            packetProcessed()
                        }
                    }

                    0x02.toByte() -> { // ack frame
                        val packetNumber = source.readLong() // packet number
                        if (packetProtector(packetNumber, false)) {
                            val pn = source.readLong() // packet
                            processAckFrameReceived(pn)
                            packetProcessed()
                        }
                    }

                    0x03.toByte() -> { // data frame
                        val packetNumber = source.readLong()
                        if (packetProtector(packetNumber, true)) {
                            processData(packetNumber, source)
                            packetProcessed()
                        }
                    }

                    0x04.toByte() -> { // close frame
                        val packetNumber = source.readLong() // ignore
                        if (packetProtector(packetNumber, false)) {
                            processData(parseCloseFrame(source))
                            packetProcessed()
                        }
                    }


                    else -> {
                        debug("Probably hole punch detected $type")
                    }
                }
            } catch (throwable: Throwable) {
                debug(throwable)
            }
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


