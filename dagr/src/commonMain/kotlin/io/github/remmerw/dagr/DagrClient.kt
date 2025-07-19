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
import kotlin.random.Random


internal class DagrClient internal constructor(
    private val selectorManager: SelectorManager,
    private val socket: BoundDatagramSocket,
    val peerId: PeerId,
    remotePeerId: PeerId,
    remoteAddress: InetSocketAddress,
    private val connector: Connector
) : Connection(socket, peerId, remotePeerId, remoteAddress, connector) {

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
                connector.addConnection(this@DagrClient)
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

        val packet = InitPacket(
            peerId, fetchPackageNumber(),
            true, createVerifyRequestFrame(token)
        )

        sendPacket(packet)
    }


    private suspend fun abortInitialize() {
        state(State.Closing)
        clearRequests()
        terminate()
    }

    override suspend fun process(verifyFrame: VerifyRequestFrame) {
        // not yet supported (maybe in the future)
    }


    override suspend fun process(verifyFrame: VerifyResponseFrame) {


        val remoteSignature = verifyFrame.signature

        try {
            verify(remotePeerId(), token, remoteSignature)

            state(State.Connected)
            discard(Level.INIT)

            initializeDone.release()
        } catch (throwable: Throwable) {
            debug("Verification failed " + throwable.message)

            sendCloseFrame(
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
            try {
                val source = receivedPacket.packet

                val type = source.readByte()
                if (type == 1.toByte()) {
                    // only APP packages allowed
                    val packetNumber = source.readLong()

                    processPacket(Level.APP, source, packetNumber)
                } else {
                    debug("Probably hole punch detected $type")
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
        return dagr.connect(timeout)
    } catch (_: Throwable) {
        selectorManager.close()
    }
    return null
}


