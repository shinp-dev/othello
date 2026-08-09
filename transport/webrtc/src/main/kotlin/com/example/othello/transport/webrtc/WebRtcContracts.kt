package com.example.othello.transport.webrtc

import android.content.Context
import com.example.othello.network.MatchTransport
import com.example.othello.network.MoveCommand
import com.example.othello.network.MoveCommandJson
import com.example.othello.network.TransportState
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
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
    private val stateListeners = mutableSetOf<(TransportState) -> Unit>()
    private val commandListeners = mutableSetOf<(MoveCommand) -> Unit>()
    private val factory: PeerConnectionFactory
    private val peerConnection: PeerConnection
    private var dataChannel: DataChannel? = null
    private var state: TransportState = TransportState.NEW
    private val dataChannelOpen = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val iceGatheringComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val configuration = PeerConnection.RTCConfiguration(iceServers.map { config ->
            PeerConnection.IceServer.builder(config.urls).apply {
                config.username?.let { setUsername(it) }
                config.credential?.let { setPassword(it) }
            }.createIceServer()
        })
        peerConnection = requireNotNull(factory.createPeerConnection(configuration, Observer()))
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
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onSetFailure(error: String) { continuation.resumeWithException(IllegalStateException(error)) }
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }, SessionDescription(type, description.sdp))
        }
    }

    suspend fun awaitDataChannelOpen() { dataChannelOpen.await() }

    fun provideOffererDataChannel() {
        if (dataChannel == null) dataChannel = peerConnection.createDataChannel("othello", DataChannel.Init()).also { attach(it) }
    }

    override suspend fun send(command: MoveCommand) {
        check(dataChannel?.state() == DataChannel.State.OPEN) { "DataChannel is not open" }
        val bytes = MoveCommandJson.encode(command).toByteArray(StandardCharsets.UTF_8)
        check(dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) == true) { "DataChannel send failed" }
    }

    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable {
        commandListeners += onCommand
        return AutoCloseable { commandListeners -= onCommand }
    }

    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable {
        stateListeners += onState
        onState(state)
        return AutoCloseable { stateListeners -= onState }
    }

    override fun close() {
        updateState(TransportState.CLOSING)
        dataChannel?.close()
        peerConnection.close()
        factory.dispose()
        updateState(TransportState.CLOSED)
    }

    private suspend fun createLocalDescription(type: SessionDescription.Type): SessionDescriptionPayload {
        val description = suspendCancellableCoroutine<SessionDescription> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) { continuation.resume(description) }
                override fun onCreateFailure(error: String) { continuation.resumeWithException(IllegalStateException(error)) }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            }
            if (type == SessionDescription.Type.OFFER) peerConnection.createOffer(observer, MediaConstraints())
            else peerConnection.createAnswer(observer, MediaConstraints())
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { continuation.resume(Unit) }
                override fun onSetFailure(error: String) { continuation.resumeWithException(IllegalStateException(error)) }
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
            }, description)
        }
        if (!iceGatheringComplete.isCompleted) iceGatheringComplete.await()
        val gathered = peerConnection.localDescription ?: description
        return SessionDescriptionPayload(gathered.type.canonicalForm(), gathered.description)
    }

    private fun attach(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> { updateState(TransportState.OPEN); dataChannelOpen.complete(Unit) }
                    DataChannel.State.CLOSING -> updateState(TransportState.CLOSING)
                    DataChannel.State.CLOSED -> updateState(TransportState.CLOSED)
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val payload = StandardCharsets.UTF_8.decode(buffer.data).toString()
                MoveCommandJson.decode(payload).onSuccess { decodedCommand -> commandListeners.toList().forEach { it(decodedCommand) } }
                    .onFailure { updateState(TransportState.FAILED) }
            }
        })
    }

    private fun updateState(next: TransportState) {
        state = next
        stateListeners.toList().forEach { it(next) }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.complete(Unit)
        }
        override fun onDataChannel(channel: DataChannel) { dataChannel = channel; attach(channel) }
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (newState == PeerConnection.IceConnectionState.FAILED) updateState(TransportState.FAILED)
            if (newState == PeerConnection.IceConnectionState.DISCONNECTED) updateState(TransportState.CLOSED)
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<org.webrtc.MediaStream>) = Unit
    }
}

interface SignalingSubscription : AutoCloseable
