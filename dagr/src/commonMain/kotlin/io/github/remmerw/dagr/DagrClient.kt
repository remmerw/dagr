package io.github.remmerw.dagr

import io.github.remmerw.borr.PeerId
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random


class DagrClient internal constructor(
    private val socket: BoundDatagramSocket,
    private val selectorManager: SelectorManager,
    private val peerId: PeerId,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    responder: Responder,
    private val connector: Connector
) : Connection(socket, remotePeerId, remoteAddress, responder) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val initializeDone = Semaphore(1, 1)
    private val token = Random.nextBytes(Settings.TOKEN_SIZE)

    /**
     * The maximum numbers of connection IDs this endpoint can use; determined by the TP
     * supplied by the peer
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val remoteCidLimit = AtomicInt(Settings.ACTIVE_CONNECTION_ID_LIMIT)


    suspend fun connect(timeout: Int) {

        try {
            startInitialize()
        } catch (throwable: Throwable) {
            abortInitialize()
            throwable.printStackTrace()
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

    override fun scheduleTerminate(pto: Int) {
        scope.launch {
            delay(pto.toLong())
            terminate()
        }
    }

    private suspend fun startInitialize() {

        scope.launch {
            runReceiver()
        }
        scope.launch {
            runRequester()
        }


        val signature = byteArrayOf() // todo signature
        sendVerifyFrame(Level.INIT, token, signature)
    }


    private suspend fun abortInitialize() {
        state(State.Failed)
        clearRequests()
        terminate()
    }


    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun handshakeDone() {

        state(State.Connected)
        discard(Level.INIT)

        initializeDone.release()

    }

    override suspend fun process(verifyFrame: FrameReceived.VerifyFrame) {


        println("Client process verify frame ")

        handshakeDone()

        /** TODO evaluate verify frame
        immediateCloseWithError(
        Level.APP,
        TransportError(TransportError.Code.PROTOCOL_VIOLATION)
        )*/


    }


    /**
     * Generate, register and send a new connection ID (that identifies this endpoint).
     */


    override suspend fun terminate() {
        super.terminate()
        connector.removeConnection(this)

        try {
            initializeDone.release()
        } catch (_: Throwable) {
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
            scope.cancel()
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
        } catch (_: CancellationException) {
            // ignore exception
        } catch (throwable: Throwable) {
            socket.isClosed.let {
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


    private suspend fun process(data: ByteArray) {
        nextPacket(Reader(data, data.size))
    }


    /**
     * Register the active connection ID limit of the peer (as received by this endpoint as TP active_connection_id_limit)
     * and determine the maximum number of peer connection ID's this endpoint is willing to maintain.
     * "This is an integer value specifying the maximum number of connection IDs from the peer that an endpoint is
     * willing to store.", so it puts an upper bound to the number of connection IDs this endpoint can generate.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun remoteCidLimit(remoteCidLimit: Int) {
        // https://www.rfc-editor.org/rfc/rfc9000.html#name-issuing-connection-ids
        // "An endpoint MUST NOT provide more connection IDs than the peer's limit."
        this.remoteCidLimit.store(remoteCidLimit)
    }


    override fun activeToken(): ByteArray {
        return token
    }

    override fun activePeerId(): PeerId {
        return peerId
    }


}

suspend fun newDagrClient(
    localPeerId: PeerId,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    responder: Responder
): DagrClient {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val connector = Connector()
    val socket = aSocket(selectorManager).udp().bind(
        InetSocketAddress("::", 3333) // todo port
    )
    return DagrClient(
        socket, selectorManager, localPeerId, remotePeerId, remoteAddress, responder, connector
    )
}
