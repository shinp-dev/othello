package com.example.othello.network.peopleplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface PeoplePlayMessage {
    val protocolVersion: Int
    val type: String
    val roomId: String
}

sealed interface PeoplePlayClientMessage : PeoplePlayMessage
sealed interface PeoplePlayServerMessage : PeoplePlayMessage

@Serializable
data class TakeSeat(
    override val roomId: String,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "TAKE_SEAT",
) : PeoplePlayClientMessage

@Serializable
data class LeaveSeat(
    override val roomId: String,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "LEAVE_SEAT",
) : PeoplePlayClientMessage

@Serializable
data class MoveSnapshot(
    override val roomId: String,
    @SerialName("ply") val wirePly: Int,
    val move: PeoplePlayMove,
    val board: List<Int>,
    val nextTurn: PlayerColor?,
    val terminalCandidate: Boolean,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "MOVE_SNAPSHOT",
) : PeoplePlayClientMessage

@Serializable
data class Desync(
    override val roomId: String,
    val observedPly: Int,
    val reason: DesyncReason,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "DESYNC",
) : PeoplePlayClientMessage

@Serializable
data class ResultReport(
    override val roomId: String,
    @SerialName("ply") val wirePly: Int,
    val result: ReportedResult,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "RESULT_REPORT",
) : PeoplePlayClientMessage

@Serializable
data class TimeoutSelf(
    override val roomId: String,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "TIMEOUT_SELF",
) : PeoplePlayClientMessage

@Serializable
data class PeoplePlayRoomSnapshot(
    override val roomId: String,
    val phase: RoomPhase,
    val timeControl: TimeControl,
    val seats: PeoplePlaySeats,
    val players: PeoplePlayPlayers?,
    @SerialName("currentPly") val wirePly: Int,
    val board: List<Int>,
    val move: PeoplePlayMove?,
    val nextTurn: PlayerColor?,
    val terminalCandidate: Boolean,
    val resultCheckPly: Int?,
    val spectatorCount: Int,
    val spectatorAvatarPreview: List<AvatarId>,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "ROOM_SNAPSHOT",
) : PeoplePlayServerMessage

@Serializable
data class ResultCheck(
    override val roomId: String,
    @SerialName("ply") val wirePly: Int,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "RESULT_CHECK",
) : PeoplePlayServerMessage

@Serializable
data class GameOver(
    override val roomId: String,
    @SerialName("ply") val wirePly: Int,
    val finishReason: FinishReason,
    val outcome: Outcome,
    val winner: PlayerColor?,
    val decidedAt: Long,
    val finalSnapshot: PeoplePlayRoomSnapshot,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "GAME_OVER",
) : PeoplePlayServerMessage {
    val result: PeoplePlayGameResult get() = PeoplePlayGameResult(finishReason, outcome, winner)
}

@Serializable
data class PeoplePlayError(
    override val roomId: String,
    val code: PeoplePlayErrorCode,
    val rejectedType: String,
    @SerialName("currentPly") val wirePly: Int,
    val rejectedPly: Int? = null,
    override val protocolVersion: Int = PEOPLE_PLAY_PROTOCOL_VERSION,
    override val type: String = "ERROR",
) : PeoplePlayServerMessage

/** Pure wire-shape checks. Game rules, turn authority and command authorization belong to later phases. */
object PeoplePlayProtocolValidator {
    fun validate(message: PeoplePlayMessage) {
        require(message.protocolVersion == PEOPLE_PLAY_PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(message.roomId.isNotBlank()) { "Blank room ID" }
        when (message) {
            is TakeSeat -> requireType(message, "TAKE_SEAT")
            is LeaveSeat -> requireType(message, "LEAVE_SEAT")
            is MoveSnapshot -> {
                requireType(message, "MOVE_SNAPSHOT")
                require(message.wirePly >= 1) { "MOVE ply must be positive" }
                validateMove(message.move)
                validateBoard(message.board)
                validateTurn(message.terminalCandidate, message.nextTurn)
            }
            is Desync -> {
                requireType(message, "DESYNC")
                require(message.observedPly >= 1) { "Observed ply must be positive" }
            }
            is ResultReport -> {
                requireType(message, "RESULT_REPORT")
                require(message.wirePly >= 0) { "Negative wire ply" }
            }
            is TimeoutSelf -> requireType(message, "TIMEOUT_SELF")
            is PeoplePlayRoomSnapshot -> {
                requireType(message, "ROOM_SNAPSHOT")
                require(message.phase != RoomPhase.CLOSED) { "CLOSED is not a live snapshot" }
                require(message.wirePly >= 0) { "Negative wire ply" }
                validateBoard(message.board)
                if (message.wirePly == 0) {
                    require(message.move == null) { "Initial snapshot has no move" }
                    require(!message.terminalCandidate && message.nextTurn == PlayerColor.BLACK) { "Invalid initial turn" }
                } else {
                    require(message.move != null) { "Noninitial snapshot requires move" }
                    validateMove(message.move)
                }
                validateTurn(message.terminalCandidate, message.nextTurn)
                require(message.resultCheckPly == null || message.resultCheckPly == message.wirePly) { "Wrong result-check ply" }
                require(message.spectatorCount >= 0) { "Negative spectator count" }
                require(message.spectatorAvatarPreview.size <= 3) { "Too many preview avatars" }
                require(message.spectatorAvatarPreview.size <= message.spectatorCount) { "Preview exceeds spectator count" }
                validateParticipant(message.seats.a)
                validateParticipant(message.seats.b)
                require(message.seats.a == null || message.seats.b == null || message.seats.a.memberId != message.seats.b.memberId) { "One member cannot occupy both seats" }
                when (message.phase) {
                    RoomPhase.WAITING -> {
                        require(message.players == null) { "WAITING has no assigned colors" }
                        require(message.wirePly == 0 && message.resultCheckPly == null) { "WAITING has no moves or result check" }
                    }
                    RoomPhase.PLAYING -> {
                        val players = requireNotNull(message.players) { "PLAYING requires players" }
                        val seatA = requireNotNull(message.seats.a) { "PLAYING requires seat A" }
                        val seatB = requireNotNull(message.seats.b) { "PLAYING requires seat B" }
                        validateParticipant(players.black)
                        validateParticipant(players.white)
                        require(players.black.memberId != players.white.memberId) { "Players must differ" }
                        require(seatA.memberId != seatB.memberId) { "Seats must differ" }
                        require(
                            setOf(players.black.memberId, players.white.memberId) ==
                                setOf(seatA.memberId, seatB.memberId),
                        ) { "PLAYING players must match seats" }
                    }
                    RoomPhase.CLOSED -> error("CLOSED is not a live snapshot")
                }
            }
            is ResultCheck -> {
                requireType(message, "RESULT_CHECK")
                require(message.wirePly >= 0) { "Negative wire ply" }
            }
            is GameOver -> {
                requireType(message, "GAME_OVER")
                require(message.wirePly >= 0) { "Negative wire ply" }
                require(message.decidedAt >= 0) { "Negative epoch milliseconds" }
                validate(message.finalSnapshot)
                require(message.finalSnapshot.phase == RoomPhase.PLAYING) { "GAME_OVER requires a played game" }
                require(message.finalSnapshot.roomId == message.roomId && message.finalSnapshot.wirePly == message.wirePly) { "Final snapshot identity differs" }
                validate(message.result)
            }
            is PeoplePlayError -> {
                requireType(message, "ERROR")
                require(message.wirePly >= 0 && (message.rejectedPly == null || message.rejectedPly >= 0)) { "Negative wire ply" }
                require(message.rejectedType.isNotBlank()) { "Blank rejected type" }
            }
        }
    }

    fun validate(result: PeoplePlayGameResult) {
        val expectedWinner = when (result.outcome) {
            Outcome.BLACK_WIN -> PlayerColor.BLACK
            Outcome.WHITE_WIN -> PlayerColor.WHITE
            Outcome.DRAW, Outcome.NO_CONTEST -> null
        }
        require(result.winner == expectedWinner) { "Outcome and winner disagree" }
        when (result.finishReason) {
            FinishReason.NORMAL -> require(result.outcome != Outcome.NO_CONTEST) { "NORMAL cannot be no contest" }
            FinishReason.TIMEOUT, FinishReason.DISCONNECT -> require(result.outcome == Outcome.BLACK_WIN || result.outcome == Outcome.WHITE_WIN) { "A loss requires a winning color" }
            FinishReason.DESYNC -> require(result.outcome == Outcome.NO_CONTEST) { "DESYNC must be no contest" }
        }
    }

    private fun requireType(message: PeoplePlayMessage, expected: String) = require(message.type == expected) { "Wrong message type" }
    private fun validateMove(move: PeoplePlayMove) = require(move.row in 0..7 && move.column in 0..7) { "Move outside board" }
    private fun validateBoard(board: List<Int>) = require(board.size == 64 && board.all { it in 0..2 }) { "Invalid board" }
    private fun validateTurn(terminal: Boolean, next: PlayerColor?) = require(terminal == (next == null)) { "Terminal flag and next turn disagree" }
    private fun validateParticipant(participant: PeoplePlayParticipantSummary?) {
        if (participant != null) require(participant.memberId.isNotBlank() && participant.displayName.isNotBlank()) { "Blank participant field" }
    }
}
