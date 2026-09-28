package com.example.othello.network.peopleplay

import kotlinx.serialization.Serializable

const val PEOPLE_PLAY_PROTOCOL_VERSION = 1

@Serializable enum class RoomPhase { WAITING, PLAYING, CLOSED }
@Serializable enum class Seat { A, B }
@Serializable enum class PlayerColor { BLACK, WHITE }
@Serializable enum class AvatarId { ADULT_MAN, ADULT_WOMAN, BOY, GIRL, MAGIC_WAND, MAGIC_BOOK }

@Serializable
enum class TimeControl(val initialMillis: Long) {
    TWENTY_MINUTES(1_200_000L),
    FIFTEEN_MINUTES(900_000L),
    TEN_MINUTES(600_000L),
    FIVE_MINUTES(300_000L),
    THREE_MINUTES(180_000L),
}

@Serializable
data class PeoplePlayParticipantSummary(
    val memberId: String,
    val displayName: String,
    val avatarId: AvatarId,
)

@Serializable
data class PeoplePlaySeats(
    val a: PeoplePlayParticipantSummary?,
    val b: PeoplePlayParticipantSummary?,
)

@Serializable
data class PeoplePlayPlayers(
    val black: PeoplePlayParticipantSummary,
    val white: PeoplePlayParticipantSummary,
)

@Serializable
data class PeoplePlayMove(val row: Int, val column: Int)

@Serializable enum class DesyncReason { SNAPSHOT_MISMATCH, RESULT_MISMATCH }
@Serializable enum class ReportedResult { BLACK_WIN, WHITE_WIN, DRAW, NOT_FINISHED }
@Serializable enum class FinishReason { NORMAL, TIMEOUT, DISCONNECT, DESYNC }
@Serializable enum class Outcome { BLACK_WIN, WHITE_WIN, DRAW, NO_CONTEST }

@Serializable
data class PeoplePlayGameResult(
    val finishReason: FinishReason,
    val outcome: Outcome,
    val winner: PlayerColor?,
)

@Serializable
enum class PeoplePlayErrorCode {
    BAD_MESSAGE, UNSUPPORTED_VERSION, NOT_MEMBER, NOT_PLAYER, NOT_YOUR_TURN,
    WRONG_PHASE, ALREADY_SEATED, NO_EMPTY_SEAT, NOT_SEATED, BAD_PLY,
    RESULT_PENDING, ROOM_NOT_FOUND, ROOM_CLOSED, PAYLOAD_TOO_LARGE, RATE_LIMITED,
}
