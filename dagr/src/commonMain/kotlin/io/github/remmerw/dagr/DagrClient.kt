package io.github.remmerw.dagr

import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.sign
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


class DagrClient internal constructor(
    private val selectorManager: SelectorManager,
    private val socket: BoundDatagramSocket,
    private val keys: Keys,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    private val connector: Connector
) : Connection(socket, keys.peerId, remotePeerId, remoteAddress, connector) {

    private val initializeDone = Semaphore(1, 1)
    private val token = Random.nextBytes(Settings.TOKEN_SIZE)
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun connect(timeout: Int) {

        try {
            startInitialize()
        } catch (throwable: Throwable) {
            abortInitialize()
            throw Exception("Error : " + throwable.message)
        }

        try {
            withTimeout(timeout * 1000L) {

                initializeDone.acquire()

                if (state() != State.Connected) {
                    abortInitialize()
                    throw Exception("Handshake error state is " + state())
                }
                connector.addConnection(this@DagrClient)
            }
        } catch (throwable: Throwable) {
            abortInitialize()
            throw throwable
        }
    }


    private suspend fun startInitialize() {

        scope.launch {
            runReceiver()
        }

        scope.launch {
            runRequester()
        }

        val signature = sign(keys, token)
        sendVerifyFrame(Level.INIT, token, signature)
    }


    private suspend fun abortInitialize() {
        state(State.Closing)
        clearRequests()
        terminate()
    }


    override suspend fun process(verifyFrame: FrameReceived.VerifyFrame) {


        val remoteToken = verifyFrame.token
        val remoteSignature = verifyFrame.signature

        try {
            verify(remotePeerId(), remoteToken, remoteSignature)

            state(State.Connected)
            discard(Level.INIT)

            initializeDone.release()
        } catch (throwable: Throwable) {
            debug("Verification failed " + throwable.message)

            scheduledClose(
                Level.APP,
                TransportError(TransportError.Code.PROTOCOL_VIOLATION)
            )
        }
    }


    override suspend fun terminate() {
        super.terminate()

        try {
            initializeDone.release()
        } catch (_: Throwable) {
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
            socket.close()
        } catch (throwable: Throwable) {
            debug(throwable)
        }
    }

    private suspend fun runReceiver(): Unit = coroutineScope {
        while (isActive) {
            val receivedPacket = socket.receive()
            try {
                val source = receivedPacket.packet

                val type = source.readByte()
                if (type == 1.toByte()) {
                    // only APP packages allowed
                    val packetNumber = source.readLong()

                    processPacket(
                        PacketHeader(Level.APP, source.readByteArray(), packetNumber)
                    )
                } else {
                    debug("Probably hole punch detected $type")
                }
            } catch (throwable: Throwable) {
                debug(throwable)
            }
        }

    }


    override fun responder(): Responder? {
        return null // not yet supported (only uni directional connections)
    }

    override fun clientConnection(): Boolean {
        return true
    }

}

suspend fun newDagrClient(
    keys: Keys,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    connector: Connector
): DagrClient {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val socket = aSocket(selectorManager).udp().bind(
        InetSocketAddress("::", 0)
    )
    return DagrClient(
        selectorManager,
        socket, keys, remotePeerId, remoteAddress, connector
    )
}


suspend fun createStream(connection: Connection): Stream {
    return connection.createStream()
}
