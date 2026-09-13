package com.example.othello

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class OpponentPackFormatTest {
    @Test fun boundedParametersAndUnknownFieldsAreRejected() {
        listOf(
            animalManifest().replace("\"edaxLevel\": 1", "\"edaxLevel\": 99"),
            animalManifest().replace("\"baseMs\": 520", "\"baseMs\": 999999"),
            animalManifest().replace("\"midgamePly\": 25", "\"midgamePly\": 59").replace("\"endgamePly\": 50", "\"endgamePly\": 40"),
            animalManifest().replace("\"section\": \"FEATURED\"", "\"section\": \"EXECUTE\""),
            animalManifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 9"),
            animalManifest().replace("assets/banner.png", "assets/../banner.png"),
            animalManifest().replace("\"personality\": \"NATURAL\"", "\"personality\": \"script\""),
        ).forEach { assertFails { parseOpponentManifest(it) } }
    }

    @Test fun invalidReferencesAndUnlockCyclesAreRejected() {
        val root = Json.parseToJsonElement(animalManifest()).jsonObject
        val players = root.getValue("players").jsonArray.toMutableList()
        val first = players.first().jsonObject.toMutableMap()
        for (dependency in listOf("unknown", "rabbit", "chick")) {
            first["requires"] = JsonArray(listOf(JsonPrimitive(dependency)))
            players[0] = JsonObject(first)
            assertFails { parseOpponentManifest(JsonObject(root + ("players" to JsonArray(players))).toString()) }
        }
    }

    @Test fun homeOrdersSectionsAndUsesStableIdsForTiesAndAvailability() {
        val base = animalPack()
        fun installed(pack: OpponentPack) = InstalledOpponentPack(pack, java.io.File("unused"))
        val later = base.copy(id = "later", home = OpponentHomePlacement(OpponentHomeSection.CHALLENGES, OpponentHomeStyle.COMPACT, -100))
        val earlier = base.copy(id = "earlier", home = base.home.copy(order = -1))
        val expired = base.copy(id = "expired", availableUntil = Instant.parse("2020-01-01T00:00:00Z"))
        val snapshot = OpponentPackSnapshot(listOf(later, expired, base, earlier).map(::installed))
        assertEquals(listOf("earlier", "animal", "later"), snapshot.visiblePacks().map { it.definition.id })
        assertEquals("Animal Challenge", base.title.resolve("fr"))
        assertEquals("どうぶつチャレンジ", base.title.resolve("ja"))
    }
}
