package com.example.othello.match

enum class MatchStatus {
    IDLE, WAITING, SIGNALING, P2P_CONNECTED, PLAYING, FINISHING, CONFIRMED,
    SIGNALING_FAILED, DISCONNECTED, PENDING_RESULT, DISPUTED, FAILED,
}

sealed interface MatchCommand {
    data object JoinQueue : MatchCommand
    data object MatchFound : MatchCommand
    data object OfferAccepted : MatchCommand
    data object DataChannelOpened : MatchCommand
    data object StartConfirmed : MatchCommand
    data object GameFinished : MatchCommand
    data object ResultConfirmed : MatchCommand
    data object ResultPending : MatchCommand
    data object ResultDisputed : MatchCommand
    data object SignalingFailed : MatchCommand
    data object Disconnected : MatchCommand
    data object Retry : MatchCommand
    data object Reset : MatchCommand
}

data class MatchState(val status: MatchStatus = MatchStatus.IDLE, val error: String? = null)

sealed interface MatchTransition {
    data class Accepted(val state: MatchState) : MatchTransition
    data class Rejected(val state: MatchState, val reason: String) : MatchTransition
}

object MatchStateMachine {
    fun reduce(state: MatchState, command: MatchCommand): MatchTransition {
        val next = when (state.status) {
            MatchStatus.IDLE -> if (command === MatchCommand.JoinQueue) MatchStatus.WAITING else null
            MatchStatus.WAITING -> when (command) {
                MatchCommand.MatchFound -> MatchStatus.SIGNALING
                MatchCommand.Reset -> MatchStatus.IDLE
                else -> null
            }
            MatchStatus.SIGNALING -> when (command) {
                MatchCommand.OfferAccepted -> MatchStatus.P2P_CONNECTED
                MatchCommand.SignalingFailed -> MatchStatus.SIGNALING_FAILED
                MatchCommand.Disconnected -> MatchStatus.DISCONNECTED
                else -> null
            }
            MatchStatus.P2P_CONNECTED -> when (command) {
                MatchCommand.DataChannelOpened -> MatchStatus.P2P_CONNECTED
                MatchCommand.StartConfirmed -> MatchStatus.PLAYING
                MatchCommand.Disconnected -> MatchStatus.DISCONNECTED
                else -> null
            }
            MatchStatus.PLAYING -> when (command) {
                MatchCommand.GameFinished -> MatchStatus.FINISHING
                MatchCommand.Disconnected -> MatchStatus.DISCONNECTED
                else -> null
            }
            MatchStatus.FINISHING -> when (command) {
                MatchCommand.ResultConfirmed -> MatchStatus.CONFIRMED
                MatchCommand.ResultPending -> MatchStatus.PENDING_RESULT
                MatchCommand.ResultDisputed -> MatchStatus.DISPUTED
                else -> null
            }
            MatchStatus.SIGNALING_FAILED, MatchStatus.DISCONNECTED -> when (command) {
                MatchCommand.Retry -> MatchStatus.WAITING
                MatchCommand.Reset -> MatchStatus.IDLE
                else -> null
            }
            MatchStatus.PENDING_RESULT -> when (command) {
                MatchCommand.ResultConfirmed -> MatchStatus.CONFIRMED
                MatchCommand.ResultDisputed -> MatchStatus.DISPUTED
                MatchCommand.Reset -> MatchStatus.IDLE
                else -> null
            }
            MatchStatus.DISPUTED, MatchStatus.CONFIRMED -> if (command === MatchCommand.Reset) MatchStatus.IDLE else null
            MatchStatus.FAILED -> if (command === MatchCommand.Reset) MatchStatus.IDLE else null
        }
        return if (next == null) MatchTransition.Rejected(state, "${command::class.simpleName} is not allowed from ${state.status}")
        else MatchTransition.Accepted(MatchState(next))
    }
}
