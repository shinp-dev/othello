package com.example.othello

import com.example.othello.analysis.api.*
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.*

internal const val OPPONENT_MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
internal const val OPPONENT_MAX_MANIFEST_BYTES = 1024 * 1024
internal const val OPPONENT_MAX_ASSET_BYTES = 8L * 1024 * 1024
internal const val OPPONENT_MAX_EXPANDED_BYTES = 64L * 1024 * 1024
internal const val OPPONENT_MAX_ENTRIES = 256
internal const val OPPONENT_MAX_PACKS = 32
internal const val OPPONENT_INDEX_URL = "https://raw.githubusercontent.com/shinp-dev/chanriva-content/main/opponents/index.json"

internal data class OpponentPackDescriptor(val id: String, val version: Int, val url: String, val sizeBytes: Long, val sha256: String)

internal fun validateOpponentId(value: String): String = value.also {
    require(Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(it)) { "Invalid opponent ID" }
}

internal fun validateOpponentAssetPath(value: String): String = value.also {
    require(it.length <= 160 && Regex("assets/[a-z0-9_-]+\\.(png|webp|jpg|jpeg)").matches(it)) { "Unsafe opponent asset path" }
}

internal fun parseOpponentIndex(raw: String): List<OpponentPackDescriptor> {
    require(raw.toByteArray().size <= 128 * 1024)
    val root = Json.parseToJsonElement(raw).jsonObject.checked("schemaVersion", "packs")
    require(root.int("schemaVersion", 1..1) == 1)
    return root.getValue("packs").jsonArray.also { require(it.size in 1..OPPONENT_MAX_PACKS) }.map {
        val obj = it.jsonObject.checked("id", "version", "url", "sizeBytes", "sha256")
        val url = obj.string("url")
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null && uri.port in setOf(-1, 443))
        val sha = obj.string("sha256").also { require(Regex("[0-9a-f]{64}").matches(it)) }
        OpponentPackDescriptor(validateOpponentId(obj.string("id")), obj.int("version", 1..Int.MAX_VALUE), url,
            obj.long("sizeBytes", 1L..OPPONENT_MAX_ARCHIVE_BYTES), sha)
    }.also { packs -> require(packs.map { it.id }.toSet().size == packs.size) }
}

internal fun parseOpponentManifest(raw: String): OpponentPack {
    require(raw.toByteArray().size <= OPPONENT_MAX_MANIFEST_BYTES)
    val root = Json.parseToJsonElement(raw).jsonObject.checked(
        "schemaVersion", "id", "version", "title", "description", "banner", "home", "players", "availableFrom", "availableUntil",
    )
    root.int("schemaVersion", 1..1)
    val home = root.getValue("home").jsonObject.checked("section", "style", "order")
    val players = root.getValue("players").jsonArray.also { require(it.size in 1..64) }.map { element ->
        val p = element.jsonObject.checked("id", "name", "order", "requires", "ai", "portrait", "winImage", "loseImage", "unlockCelebration")
        Player(
            validateOpponentId(p.string("id")), p.text("name"), p.int("order", 0..10_000),
            p.getValue("requires").jsonArray.also { require(it.size <= 64) }.map { require(it.jsonPrimitive.isString); validateOpponentId(it.jsonPrimitive.content) }
                .also { require(it.size == it.toSet().size) }.toSet(),
            parseOpponentAi(p.getValue("ai").jsonObject),
            validateOpponentAssetPath(p.string("portrait")), validateOpponentAssetPath(p.string("winImage")),
            validateOpponentAssetPath(p.string("loseImage")), OpponentUnlockCelebration.valueOf(p.string("unlockCelebration")),
        )
    }.sortedWith(compareBy({ it.order }, { it.id }))
    val byId = players.associateBy { it.id }
    require(byId.size == players.size) { "Duplicate player ID" }
    // Require a DAG, not just references to existing players. Branches and multiple roots are allowed.
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    fun visit(id: String) {
        if (id in visited) return
        require(visiting.add(id)) { "Cyclic player unlock graph" }
        val player = requireNotNull(byId[id]) { "Unknown unlock prerequisite" }
        player.requires.forEach(::visit)
        visiting.remove(id)
        visited.add(id)
    }
    players.forEach { visit(it.id) }
    val from = root["availableFrom"]?.jsonPrimitive?.content?.let(Instant::parse)
    val until = root["availableUntil"]?.jsonPrimitive?.content?.let(Instant::parse)
    require(from == null || until == null || until > from)
    return OpponentPack(
        validateOpponentId(root.string("id")), root.int("version", 1..Int.MAX_VALUE), root.text("title"), root.text("description"),
        validateOpponentAssetPath(root.string("banner")),
        OpponentHomePlacement(OpponentHomeSection.valueOf(home.string("section")), OpponentHomeStyle.valueOf(home.string("style")), home.int("order", -10_000..10_000)),
        players, from, until,
    )
}

private fun parseOpponentAi(obj: JsonObject): StandardAiConfig {
    obj.checked("edaxLevel", "personality", "moves", "think", "tension")
    val moves = obj.getValue("moves").jsonObject.checked("openingWeights", "midgameWeights", "endgameWeights", "openingMaxScoreLoss", "endgameMaxScoreLoss", "midgamePly", "endgamePly")
    fun weights(key: String): List<Double> = moves.getValue(key).jsonArray.also { require(it.size in 1..16) }.map {
        require(!it.jsonPrimitive.isString)
        it.jsonPrimitive.double.also { value -> require(value.isFinite() && value in 0.0..1.0) }
    }.also { require(it.first() > 0 && it.sum() > 0) }
    val moveProfile = StandardWeightedMoveProfile(
        weights("openingWeights"), weights("midgameWeights"), weights("endgameWeights"),
        moves.int("openingMaxScoreLoss", 0..64), moves.int("endgameMaxScoreLoss", 0..64),
        moves.int("midgamePly", 1..59).toDouble(), moves.int("endgamePly", 2..60).toDouble(),
    )
    val t = obj.getValue("think").jsonObject.checked("baseMs", "forcedMoveMs", "ambiguousGapThreshold", "ambiguousBonusMs", "riskyBestScoreThreshold", "riskyChoiceBonusMs", "allNegativeBonusMs", "manyMovesThreshold", "manyMovesBonusMs", "nonBestChoiceBonusMs", "endgameStartPly", "endgameBonusMs", "minMs", "maxMs")
    fun ms(key: String) = t.long(key, 0L..5_000L)
    val think = StandardAdaptiveThinkTimeProfile(
        ms("baseMs"), ms("forcedMoveMs"), t.int("ambiguousGapThreshold", 0..64), ms("ambiguousBonusMs"),
        t.int("riskyBestScoreThreshold", -64..64), ms("riskyChoiceBonusMs"), ms("allNegativeBonusMs"),
        t.int("manyMovesThreshold", 1..64), ms("manyMovesBonusMs"), ms("nonBestChoiceBonusMs"),
        t.int("endgameStartPly", 0..60), ms("endgameBonusMs"), ms("minMs"), ms("maxMs"),
    )
    val v = obj.getValue("tension").jsonObject.checked("ambiguousGapThreshold", "riskyBestScoreThreshold", "lowMobilityThreshold", "endgameStartPly", "tenseScore", "criticalScore")
    val tension = StandardAdaptiveTensionProfile(v.int("ambiguousGapThreshold", 0..64), v.int("riskyBestScoreThreshold", -64..64),
        v.int("lowMobilityThreshold", 2..64), v.int("endgameStartPly", 0..60), v.int("tenseScore", 1..8), v.int("criticalScore", 2..9))
    val id = StandardAiPersonalityId.valueOf(obj.string("personality"))
    return StandardAiConfig(obj.int("edaxLevel", 1..4), StandardAiPersonality(
        id, if (id == StandardAiPersonalityId.SERIOUS) StandardBestMovePolicy else StandardWeightedMoveSelectionPolicy(moveProfile),
        StandardAdaptiveThinkTimePolicy(think), StandardAdaptiveTensionPolicy(tension),
    ))
}

private fun JsonObject.checked(vararg allowed: String): JsonObject = also { require(keys.all { it in allowed }) { "Unsupported opponent field" } }
private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.long(key: String, range: LongRange): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long.also { require(it in range) }
private fun JsonObject.int(key: String, range: IntRange): Int = long(key, range.first.toLong()..range.last.toLong()).toInt()
private fun JsonObject.text(key: String): OpponentText {
    val values = getValue(key).jsonObject
    require("en" in values && values.size in 1..16)
    return OpponentText(values.mapValues { (language, value) ->
        require(Regex("[a-z]{2,3}").matches(language))
        require(value.jsonPrimitive.isString)
        value.jsonPrimitive.content.also { require(it.isNotBlank() && it.length <= 512 && it.none { c -> c.isISOControl() }) }
    })
}
