package com.example.othello

import android.content.Context
import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.game.Disc
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.transport.webrtc.AndroidWebRtcTransport
import com.example.othello.transport.webrtc.AndroidWebRtcTransportFactory
import com.example.othello.transport.webrtc.DefaultIceServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns the P2P session outside Compose so Activity recreation does not create a second peer. */
class WebRtcMatchCoordinator(
    context: Context,
    private val userId: String,
    private val assignment: MatchAssignment,
    private val signaling: SupabaseSignalingDataSource,
    repository: OnlineMatchRepository,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val transport = AndroidWebRtcTransportFactory(context.applicationContext)
        .create(assignment.matchId, DefaultIceServers.publicStun) as AndroidWebRtcTransport
    val controller = OnlineMatchController(
        assignment.matchId,
        if (assignment.assignedDisc.name == "BLACK") Disc.BLACK else Disc.WHITE,
        transport,
        repository,
    )
    private var subscription: AutoCloseable? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        subscription = signaling.subscribe(assignment.matchId) { envelope ->
            if (envelope.senderId == userId) return@subscribe
            scope.launch { handle(envelope) }
        }
        if (assignment.assignedDisc.name == "BLACK") {
            scope.launch {
                transport.provideOffererDataChannel()
                val offer = transport.createOffer()
                signaling.publish(SignalingEnvelope(assignment.matchId, userId, "OFFER", offer.sdp))
            }
        }
    }

    private suspend fun handle(envelope: SignalingEnvelope) {
        when {
            envelope.type == "OFFER" && assignment.assignedDisc.name == "WHITE" -> {
                transport.setRemoteDescription(com.example.othello.transport.webrtc.SessionDescriptionPayload("OFFER", envelope.sdp))
                val answer = transport.createAnswer()
                signaling.publish(SignalingEnvelope(assignment.matchId, userId, "ANSWER", answer.sdp))
            }
            envelope.type == "ANSWER" && assignment.assignedDisc.name == "BLACK" -> {
                transport.setRemoteDescription(com.example.othello.transport.webrtc.SessionDescriptionPayload("ANSWER", envelope.sdp))
            }
        }
        transport.awaitDataChannelOpen()
        controller.onDataChannelOpen()
        subscription?.close()
    }

    override fun close() {
        subscription?.close()
        controller.close()
    }
}
