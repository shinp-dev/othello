package com.example.othello.transport.webrtc

import com.example.othello.network.MatchTransport

data class IceServerConfig(val urls: List<String>, val username: String? = null, val credential: String? = null)

interface WebRtcTransportFactory {
    fun create(matchId: String, iceServers: List<IceServerConfig>): MatchTransport
}

/** Concrete Android WebRTC SDK wiring belongs only in this module. */
interface SignalingSubscription : AutoCloseable
