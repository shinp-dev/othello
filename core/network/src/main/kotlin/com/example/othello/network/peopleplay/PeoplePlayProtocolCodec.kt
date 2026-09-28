package com.example.othello.network.peopleplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON text only. Direction is selected by the entry point, never inferred from a shared decoder. */
object PeoplePlayProtocolCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun encodeClientMessage(message: PeoplePlayClientMessage): String {
        PeoplePlayProtocolValidator.validate(message)
        return when (message) {
            is TakeSeat -> json.encodeToString(TakeSeat.serializer(), message)
            is LeaveSeat -> json.encodeToString(LeaveSeat.serializer(), message)
            is MoveSnapshot -> json.encodeToString(MoveSnapshot.serializer(), message)
            is Desync -> json.encodeToString(Desync.serializer(), message)
            is ResultReport -> json.encodeToString(ResultReport.serializer(), message)
            is TimeoutSelf -> json.encodeToString(TimeoutSelf.serializer(), message)
        }
    }

    fun encodeServerMessage(message: PeoplePlayServerMessage): String {
        PeoplePlayProtocolValidator.validate(message)
        return when (message) {
            is PeoplePlayRoomSnapshot -> json.encodeToString(PeoplePlayRoomSnapshot.serializer(), message)
            is ResultCheck -> json.encodeToString(ResultCheck.serializer(), message)
            is GameOver -> json.encodeToString(GameOver.serializer(), message)
            is PeoplePlayError -> json.encodeToString(PeoplePlayError.serializer(), message)
        }
    }

    fun decodeClientMessage(payload: String): Result<PeoplePlayClientMessage> = runCatching {
        val type = checkedType(payload)
        val message = when (type) {
            "TAKE_SEAT" -> json.decodeFromString(TakeSeat.serializer(), payload)
            "LEAVE_SEAT" -> json.decodeFromString(LeaveSeat.serializer(), payload)
            "MOVE_SNAPSHOT" -> json.decodeFromString(MoveSnapshot.serializer(), payload)
            "DESYNC" -> json.decodeFromString(Desync.serializer(), payload)
            "RESULT_REPORT" -> json.decodeFromString(ResultReport.serializer(), payload)
            "TIMEOUT_SELF" -> json.decodeFromString(TimeoutSelf.serializer(), payload)
            else -> throw IllegalArgumentException("Unknown client message type")
        }
        PeoplePlayProtocolValidator.validate(message)
        message
    }

    fun decodeServerMessage(payload: String): Result<PeoplePlayServerMessage> = runCatching {
        val type = checkedType(payload)
        val message = when (type) {
            "ROOM_SNAPSHOT" -> json.decodeFromString(PeoplePlayRoomSnapshot.serializer(), payload)
            "RESULT_CHECK" -> json.decodeFromString(ResultCheck.serializer(), payload)
            "GAME_OVER" -> json.decodeFromString(GameOver.serializer(), payload)
            "ERROR" -> json.decodeFromString(PeoplePlayError.serializer(), payload)
            else -> throw IllegalArgumentException("Unknown server message type")
        }
        PeoplePlayProtocolValidator.validate(message)
        message
    }

    private fun checkedType(payload: String): String {
        val envelope = json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("Expected JSON object")
        require(envelope.containsKey("protocolVersion") && envelope.containsKey("type") && envelope.containsKey("roomId")) {
            "Missing game message envelope field"
        }
        return envelope.getValue("type").jsonPrimitive.content
    }
}
