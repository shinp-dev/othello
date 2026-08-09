package com.example.othello.transport.webrtc

import android.content.Context
import com.example.othello.network.MatchTransport
import com.example.othello.network.FinishCommand
import com.example.othello.network.FinishCommandJson
import com.example.othello.network.MoveCommand
import com.example.othello.network.MoveCommandJson
import com.example.othello.network.TransportState
import com.example.othello.network.TransportDiagnostics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IceServerConfig(val urls: List<String>, val username: String? = null, val credential: String? = null)

data class SessionDescriptionPayload(val type: String, val sdp: String)

interface WebRtcTransportFactory {
    fun create(matchId: String, iceServers: List<IceServerConfig>): MatchTransport
}

object DefaultIceServers {
    val publicStun = listOf(IceServerConfig(listOf("stun:stun.l.google.com:19302")))
}

class AndroidWebRtcTransportFactory(private val context: Context) : WebRtcTransportFactory {
    override fun create(matchId: String, iceServers: List<IceServerConfig>): AndroidWebRtcTransport =
        AndroidWebRtcTransport(context, matchId, iceServers)
}

/** Android WebRTC SDK wiring. Core modules only see MatchTransport and command ports. */
class AndroidWebRtcTransport(
    context: Context,
    private val matchId: String,
    iceServers: List<IceServerConfig> = DefaultIceServers.publicStun,
) : MatchTransport {
    private val stateListeners = CopyOnWriteArraySet<(TransportState) -> Unit>()
    private val commandListeners = CopyOnWriteArraySet<(MoveCommand) -> Unit>()
    private val finishListeners = CopyOnWriteArraySet<(FinishCommand) -> Unit>()
    private val factory: PeerConnectionFactory
    private val peerConnection: PeerConnection
    private var dataChannel: DataChannel? = null
    private var state: TransportState = TransportState.NEW
    private var iceState = "NEW"
    private var peerConnectionState = "NEW"
    private var dataChannelState = "NEW"
    private val dataChannelOpen = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val iceGatheringComplete = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    init {
        if (!factoryInitialized.get()) {
            synchronized(factoryInitialized) {
                if (!factoryInitialized.get()) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
                    )
                    factoryInitialized.set(true)
                }
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val configuration = PeerConnection.RTCConfiguration(iceServers.map { config ->
            PeerConnection.IceServer.builder(config.urls).apply {
                config.username?.let { setUsername(it) }
                config.credential?.let { setPassword(it) }
            }.createIceServer()
        })
        peerConnection = try {
            requireNotNull(factory.createPeerConnection(configuration, Observer()))
        } catch (error: Throwable) {
            factory.dispose()
            throw error
        }
        updateState(TransportState.CONNECTING)
    }

    suspend fun createOffer(): SessionDescriptionPayload = createLocalDescription(SessionDescription.Type.OFFER)

    suspend fun createAnswer(): SessionDescriptionPayload = createLocalDescription(SessionDescription.Type.ANSWER)

    suspend fun setRemoteDescription(description: SessionDescriptionPayload) {
        val type = when (description.type.uppercase()) {
            "OFFER" -> SessionDescription.Type.OFFER
            "ANSWER" -> SessionDescription.Type.ANSWER
            else -> error("unsupported WebRTC description type")
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error)) }
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }, SessionDescription(type, description.sdp))
        }
    }

    suspend fun awaitDataChannelOpen() { dataChannelOpen.await() }

    fun provideOffererDataChannel() {
        check(!closed.get()) { "WebRTC transport is closed" }
        if (dataChannel == null) dataChannel = peerConnection.createDataChannel("othello", DataChannel.Init()).also { attach(it) }
    }

    override suspend fun send(command: MoveCommand) {
        sendPayload(MoveCommandJson.encode(command))
    }

    override suspend fun sendFinish(command: FinishCommand) {
        sendPayload(FinishCommandJson.encode(command))
    }

    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable {
        commandListeners += onCommand
        return AutoCloseable { commandListeners -= onCommand }
    }

    override fun observeFinish(onCommand: (FinishCommand) -> Unit): AutoCloseable {
        finishListeners += onCommand
        return AutoCloseable { finishListeners -= onCommand }
    }

    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable {
        stateListeners += onState
        onState(state)
        return AutoCloseable { stateListeners -= onState }
    }

    override fun diagnostics(): TransportDiagnostics = TransportDiagnostics(
        state = state,
        iceState = iceState,
        peerConnectionState = peerConnectionState,
        dataChannelState = dataChannelState,
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        updateState(TransportState.CLOSING)
        val cancellation = CancellationException("WebRTC transport closed")
        dataChannelOpen.completeExceptionally(cancellation)
        iceGatheringComplete.completeExceptionally(cancellation)
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel?.dispose()
        peerConnection.close()
        peerConnection.dispose()
        factory.dispose()
        commandListeners.clear()
        finishListeners.clear()
        updateState(TransportState.CLOSED)
        stateListeners.clear()
    }

    private fun sendPayload(payload: String) {
        check(!closed.get() && dataChannel?.state() == DataChannel.State.OPEN) { "DataChannel is not open" }
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "DataChannel payload is too large" }
        check(dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) == true) { "DataChannel send failed" }
    }

    private suspend fun createLocalDescription(type: SessionDescription.Type): SessionDescriptionPayload {
        val description = suspendCancellableCoroutine<SessionDescription> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) { if (continuation.isActive) continuation.resume(description) }
                override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error)) }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            }
            if (type == SessionDescription.Type.OFFER) peerConnection.createOffer(observer, MediaConstraints())
            else peerConnection.createAnswer(observer, MediaConstraints())
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error)) }
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }, description)
        }
        if (!iceGatheringComplete.isCompleted) {
            // Signaling is intentionally non-trickle. An unreachable STUN server
            // must not prevent a usable host-candidate SDP from being published.
            withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MILLIS) { iceGatheringComplete.await() }
        }
        val gathered = peerConnection.localDescription ?: description
        return SessionDescriptionPayload(gathered.type.canonicalForm(), gathered.description)
    }

    private fun attach(channel: DataChannel) {
        if (closed.get()) {
            channel.close()
            channel.dispose()
            return
        }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (closed.get()) return
                when (channel.state()) {
                    DataChannel.State.OPEN -> { dataChannelState = "OPEN"; updateState(TransportState.OPEN); dataChannelOpen.complete(Unit) }
                    DataChannel.State.CLOSING -> { dataChannelState = "CLOSING"; updateState(TransportState.CLOSING) }
                    DataChannel.State.CLOSED -> { dataChannelState = "CLOSED"; updateState(TransportState.CLOSED) }
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed.get() || buffer.binary || buffer.data.remaining() > MAX_PAYLOAD_BYTES) {
                    updateState(TransportState.FAILED)
                    return
                }
                val payload = StandardCharsets.UTF_8.decode(buffer.data).toString()
                MoveCommandJson.decode(payload).onSuccess { decodedCommand -> commandListeners.forEach { it(decodedCommand) } }
                    .onFailure {
                        FinishCommandJson.decode(payload)
                            .onSuccess { decodedCommand -> finishListeners.forEach { it(decodedCommand) } }
                            .onFailure { updateState(TransportState.FAILED) }
                    }
            }
        })
    }

    private fun updateState(next: TransportState) {
        if (closed.get() && next != TransportState.CLOSING && next != TransportState.CLOSED) return
        state = next
        stateListeners.forEach { it(next) }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (closed.get()) return
            if (newState == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.complete(Unit)
        }
        override fun onDataChannel(channel: DataChannel) {
            if (closed.get() || dataChannel != null) {
                channel.close()
                channel.dispose()
                return
            }
            dataChannel = channel
            attach(channel)
        }
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (closed.get()) return
            iceState = newState.name
            peerConnectionState = when (newState) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "CONNECTED"
                PeerConnection.IceConnectionState.DISCONNECTED -> "DISCONNECTED"
                PeerConnection.IceConnectionState.FAILED -> "FAILED"
                PeerConnection.IceConnectionState.CLOSED -> "CLOSED"
                else -> newState.name
            }
            if (newState == PeerConnection.IceConnectionState.FAILED) updateState(TransportState.FAILED)
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<org.webrtc.MediaStream>) = Unit
    }


    private companion object {
        const val MAX_PAYLOAD_BYTES = 32 * 1024
        const val ICE_GATHERING_TIMEOUT_MILLIS = 10_000L
        val factoryInitialized = AtomicBoolean(false)
    }
}

interface SignalingSubscription : AutoCloseable
