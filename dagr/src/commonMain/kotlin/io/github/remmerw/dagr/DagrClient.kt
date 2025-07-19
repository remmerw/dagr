package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.verify
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.random.Random


internal class DagrClient internal constructor(
    private val selectorManager: SelectorManager,
    private val socket: BoundDatagramSocket,
    val peerId: PeerId,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    listener: Listener
) : Connection(socket, remotePeerId, remoteAddress, listener) {

    private val initializeDone = Semaphore(1, 1)
    private val token = Random.nextBytes(Settings.TOKEN_SIZE)
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun connect(timeout: Int): Connection? {

        try {
            startInitialize()
        } catch (_: Throwable) {
            abortInitialize()
            return null
        }

        try {
            return withTimeout(timeout * 1000L) {
                initializeDone.acquire()

                if (state() != State.Connected) {
                    abortInitialize()
                    return@withTimeout null
                }
                return@withTimeout this@DagrClient
            }
        } catch (_: Throwable) {
            abortInitialize()
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

        val packet = createConnectPacket(
            peerId, fetchPacketNumber(), token
        )

        sendPacket(packet)
    }


    private suspend fun abortInitialize() {
        state(State.Closing)
        terminate()
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

        try {
            selectorManager.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    private suspend fun runReceiver(): Unit = coroutineScope {
        while (isActive) {
            val receivedPacket = socket.receive()
            if (state().isClosing) {
                break
            }
            try {
                val source = receivedPacket.packet

                val type = source.readByte()

                when (type) {
                    0x03.toByte() -> { // data frame
                        val packetNumber = source.readLong()
                        if (packetProtector(packetNumber, true)) {
                            process(parseDataFrame(source))
                            packetProcessed()
                        }
                    }

                    0x04.toByte() -> { // verify frame
                        val packetNumber = source.readLong()
                        if (packetProtector(packetNumber, true)) {
                            val signature = source.readByteArray(Settings.SIGNATURE_SIZE)


                            try {
                                verify(remotePeerId(), token, signature)

                                state(State.Connected)

                                initializeDone.release()
                            } catch (throwable: Throwable) {
                                debug("Verification failed " + throwable.message)

                                sendCloseFrame(
                                    TransportError(TransportError.Code.PROTOCOL_VIOLATION)
                                )
                            }
                            packetProcessed()
                        }
                    }

                    0x05.toByte() -> { // close frame
                        val packetNumber = source.readLong() // ignore
                        if (packetProtector(packetNumber, false)) {
                            process(parseCloseFrame(source))
                            packetProcessed()
                        }
                    }

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
    peerId: PeerId,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    connector: Connector,
    timeout: Int
): Connection? {
    val selectorManager = SelectorManager(Dispatchers.IO)
    try {
        val socket = aSocket(selectorManager).udp().bind(
            localAddress = InetSocketAddress("::", 0)
        )
        val dagr = DagrClient(
            selectorManager, socket, peerId, remotePeerId, remoteAddress, connector
        )
        val connection = dagr.connect(timeout)
        if (connection != null) {
            connector.addConnection(connection)
        }
        return connection
    } catch (_: Throwable) {
        selectorManager.close()
    }
    return null
}


