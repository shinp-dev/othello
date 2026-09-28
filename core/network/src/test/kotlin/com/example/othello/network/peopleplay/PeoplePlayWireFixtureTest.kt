package com.example.othello.network.peopleplay

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeoplePlayWireFixtureTest {
    private val root: File by lazy {
        val candidates = listOf(File("protocol/people-play/v1"), File("../../protocol/people-play/v1"))
        candidates.first { it.isDirectory }.canonicalFile
    }
    private val json = Json { ignoreUnknownKeys = false }
    private val clientFiles = listOf("take-seat", "leave-seat", "move-snapshot", "desync", "result-report", "timeout-self")
    private val serverFiles = listOf(
        "room-snapshot-waiting", "room-snapshot-playing", "result-check", "game-over-normal-black",
        "game-over-normal-draw", "game-over-timeout", "game-over-disconnect", "game-over-desync", "error",
    )

    @Test fun everyClientFixtureDecodesAndEncodesIdentically() {
        assertEquals(6, clientFiles.size)
        clientFiles.forEach { name ->
            val wire = File(root, "fixtures/client/$name.json").readText()
            val message = PeoplePlayProtocolCodec.decodeClientMessage(wire).getOrThrow()
            assertEquals(json.parseToJsonElement(wire), json.parseToJsonElement(PeoplePlayProtocolCodec.encodeClientMessage(message)), name)
            assertTrue(PeoplePlayProtocolCodec.decodeServerMessage(wire).isFailure, name)
        }
    }

    @Test fun everyServerFixtureDecodesAndEncodesIdentically() {
        assertEquals(9, serverFiles.size)
        serverFiles.forEach { name ->
            val wire = File(root, "fixtures/server/$name.json").readText()
            val message = PeoplePlayProtocolCodec.decodeServerMessage(wire).getOrThrow()
            assertEquals(json.parseToJsonElement(wire), json.parseToJsonElement(PeoplePlayProtocolCodec.encodeServerMessage(message)), name)
            assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(wire).isFailure, name)
        }
    }

    @Test fun authorizationMatrixMatchesSixClientCommandRoles() {
        val matrix = json.parseToJsonElement(File(root, "authorization-matrix.json").readText()).jsonObject
        assertEquals(1, matrix.getValue("protocolVersion").jsonPrimitive.int)
        val commands = matrix.getValue("commands").jsonArray.map { it.jsonObject }
        val expected = mapOf(
            "TAKE_SEAT" to ("WAITING" to "SPECTATOR"),
            "LEAVE_SEAT" to ("WAITING" to "SEATED_MEMBER"),
            "MOVE_SNAPSHOT" to ("PLAYING" to "CURRENT_TURN_PLAYER"),
            "DESYNC" to ("PLAYING" to "PLAYER"),
            "RESULT_REPORT" to ("PLAYING" to "PLAYER"),
            "TIMEOUT_SELF" to ("PLAYING" to "PLAYER"),
        )
        assertEquals(expected.keys, commands.map { it.getValue("type").jsonPrimitive.content }.toSet())
        assertEquals(6, commands.size)
        commands.forEach { row ->
            val type = row.getValue("type").jsonPrimitive.content
            assertEquals(expected.getValue(type).first, row.getValue("phase").jsonPrimitive.content)
            assertEquals(expected.getValue(type).second, row.getValue("sender").jsonPrimitive.content)
            assertTrue(row.getValue("conditions") is JsonArray)
        }
        val move = commands.first { it.getValue("type").jsonPrimitive.content == "MOVE_SNAPSHOT" }
        assertTrue(move.getValue("conditions").jsonArray.any { it.jsonPrimitive.content == "result_check_inactive" })
    }

    @Test fun fixtureCatalogHasOnlySpecifiedFiles() {
        assertEquals(clientFiles.map { "$it.json" }.toSet(), File(root, "fixtures/client").list()!!.toSet())
        assertEquals(serverFiles.map { "$it.json" }.toSet(), File(root, "fixtures/server").list()!!.toSet())
    }
}
