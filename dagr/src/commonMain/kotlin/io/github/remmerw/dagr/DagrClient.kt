package io.github.remmerw.dagr

import io.github.remmerw.borr.Keys
import io.github.remmerw.borr.PeerId
import io.github.remmerw.borr.sign
import io.github.remmerw.borr.verify
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.random.Random


class DagrClient internal constructor(
    private val socket: BoundDatagramSocket,
    private val selectorManager: SelectorManager,
    private val keys: Keys,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    responder: Responder,
    private val connector: Connector
) : Connection(socket, remotePeerId, remoteAddress, responder, connector) {

    private val initializeDone = Semaphore(1, 1)
    private val token = Random.nextBytes(Settings.TOKEN_SIZE)


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

        selectorManager.launch {
            runReceiver()
        }
        selectorManager.launch {
            runRequester()
        }

        val signature = sign(keys, token)
        sendVerifyFrame(Level.INIT, token, signature)
    }


    private suspend fun abortInitialize() {
        state(State.Failed)
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

            // todo remove from connector
            immediateCloseWithError(
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

    private suspend fun runReceiver() {
        try {
            while (true) {
                val receivedPacket = socket.receive()
                try {
                    println("DagrClient runReceiver")
                    val source = receivedPacket.packet

                    val type = source.readByte()
                    if (type == 1.toByte()) {
                        // only APP packages allowed
                        val packetNumber = source.readLong()

                        processPacket(
                            PacketHeader(Level.APP, source.readByteArray(), packetNumber)
                        )
                    } else {
                        println("Not supported level for clients $type")
                    }

                } catch (throwable: Throwable) {
                    debug(throwable)
                }
            }
        } finally {
            terminate()
        }
    }

    override fun activeToken(): ByteArray {
        return token
    }

    override fun activePeerId(): PeerId {
        return keys.peerId
    }

    override fun clientConnection(): Boolean {
        return true
    }

}

suspend fun newDagrClient(
    keys: Keys,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    connector: Connector,
    responder: Responder
): DagrClient {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val socket = aSocket(selectorManager).udp().bind(
        InetSocketAddress("::", 0)
    )
    return DagrClient(
        socket, selectorManager, keys, remotePeerId, remoteAddress, responder, connector
    )
}


/*
suspend fun createStream(connection: Connection, requester: Requester): Stream {
    return connection.createStream({ stream: Stream ->
        AlpnRequester(stream, requester, AlpnState(requester))
    }, true)
}*/


suspend fun createStream(connection: Connection): Stream {
    return connection.createStream({ stream: Stream -> RequestResponse(stream) }, true)
}
