package com.example.othello

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val STANDARD_CONTENT_SCHEMA_VERSION = 1
internal const val STANDARD_CONTENT_INDEX_URL =
    "https://chanriva.shinp-studio.com/content/index.json"
internal const val STANDARD_CONTENT_MAX_PACK_BYTES = 32L * 1024L * 1024L
internal const val STANDARD_CONTENT_MAX_EXTRACTED_BYTES = 96L * 1024L * 1024L
internal const val STANDARD_CONTENT_MAX_ENTRIES = 4_096
internal const val STANDARD_CONTENT_MAX_ENTRY_BYTES = 16L * 1024L * 1024L
internal const val STANDARD_CONTENT_MAX_CARDS_PER_PACK = 5_000

internal enum class StandardContentCardType(val wireName: String) {
    TRIVIA("trivia"),
    BOOK("book"),
    PERSON("person"),
    HISTORY("history"),
    COLLAB("collab");

    companion object {
        fun fromWireName(value: String): StandardContentCardType =
            entries.firstOrNull { it.wireName == value }
                ?: throw StandardContentFormatException("Unknown card type: $value")
    }
}

internal enum class StandardContentRarity(val wireName: String) {
    COMMON("common"),
    RARE("rare"),
    SPECIAL("special");

    companion object {
        fun fromWireName(value: String): StandardContentRarity =
            entries.firstOrNull { it.wireName == value }
                ?: throw StandardContentFormatException("Unknown rarity: $value")
    }
}

internal data class StandardContentIndex(
    val schemaVersion: Int,
    val packs: List<StandardContentPackDescriptor>,
)

internal data class StandardContentPackDescriptor(
    val id: String,
    val version: Int,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class StandardContentPackManifest(
    val schemaVersion: Int,
    val id: String,
    val version: Int,
)

internal data class StandardContentCard(
    val id: String,
    val type: StandardContentCardType,
    val rarity: StandardContentRarity,
    val title: String,
    val summary: String,
    val body: String?,
    val imagePath: String?,
    val seriesId: String?,
    val tags: Set<String>,
    val attributes: Map<String, String>,
    val sourceLabel: String?,
    val sourceUrl: String?,
    val externalUrl: String?,
    val sortOrder: Int?,
)

internal data class StandardInstalledContentPack(
    val manifest: StandardContentPackManifest,
    val cards: List<StandardContentCard>,
    val directory: java.io.File,
)

internal class StandardContentFormatException(message: String) : IllegalArgumentException(message)

internal fun parseStandardContentIndex(raw: String): StandardContentIndex {
    val root = raw.parseRootObject("content index")
    val schemaVersion = root.requiredPositiveInt("schemaVersion")
    requireSupportedSchema(schemaVersion)
    val packs = root.requiredArray("packs").mapIndexed { index, element ->
        val pack = element.requireObject("packs[$index]")
        val id = pack.requiredIdentifier("id")
        val version = pack.requiredPositiveInt("version")
        val url = pack.requiredHttpsUrl("url")
        val sizeBytes = pack.requiredPositiveLong("sizeBytes")
        if (sizeBytes > STANDARD_CONTENT_MAX_PACK_BYTES) {
            throw StandardContentFormatException("Pack $id exceeds the compressed size limit")
        }
        val sha256 = pack.requiredString("sha256", 64)
            .lowercase(Locale.ROOT)
            .also {
                if (!SHA256_REGEX.matches(it)) {
                    throw StandardContentFormatException("Invalid SHA-256 for pack $id")
                }
            }
        StandardContentPackDescriptor(id, version, url, sizeBytes, sha256)
    }
    if (packs.map { it.id }.toSet().size != packs.size) {
        throw StandardContentFormatException("Duplicate pack id in content index")
    }
    return StandardContentIndex(schemaVersion, packs)
}

internal fun parseStandardContentPackManifest(raw: String): StandardContentPackManifest {
    val root = raw.parseRootObject("pack manifest")
    val schemaVersion = root.requiredPositiveInt("schemaVersion")
    requireSupportedSchema(schemaVersion)
    return StandardContentPackManifest(
        schemaVersion = schemaVersion,
        id = root.requiredIdentifier("id"),
        version = root.requiredPositiveInt("version"),
    )
}

internal fun parseStandardContentCards(raw: String): List<StandardContentCard> {
    val root = raw.parseRootObject("cards")
    val schemaVersion = root.requiredPositiveInt("schemaVersion")
    requireSupportedSchema(schemaVersion)
    val elements = root.requiredArray("cards")
    if (elements.size > STANDARD_CONTENT_MAX_CARDS_PER_PACK) {
        throw StandardContentFormatException("Too many cards in pack")
    }
    val cards = elements.mapIndexed { index, element ->
        val card = element.requireObject("cards[$index]")
        val id = card.requiredCardId("id")
        val type = StandardContentCardType.fromWireName(card.requiredString("type", 32))
        val rarity = card.optionalString("rarity", 32)
            ?.let(StandardContentRarity::fromWireName)
            ?: StandardContentRarity.COMMON
        val imagePath = card.optionalString("imagePath", 256)?.also(::validateAssetPath)
        val seriesId = card.optionalString("seriesId", 128)?.also(::validateIdentifier)
        val sourceUrl = card.optionalHttpsUrl("sourceUrl")
        val externalUrl = card.optionalHttpsUrl("externalUrl")
        StandardContentCard(
            id = id,
            type = type,
            rarity = rarity,
            title = card.requiredString("title", 200),
            summary = card.requiredString("summary", 600),
            body = card.optionalString("body", 8_000),
            imagePath = imagePath,
            seriesId = seriesId,
            tags = card.optionalStringSet("tags", maxItems = 24, maxItemLength = 64),
            attributes = card.optionalStringMap("attributes", maxItems = 32, maxValueLength = 300),
            sourceLabel = card.optionalString("sourceLabel", 200),
            sourceUrl = sourceUrl,
            externalUrl = externalUrl,
            sortOrder = card.optionalNonNegativeInt("sortOrder"),
        )
    }
    if (cards.map { it.id }.toSet().size != cards.size) {
        throw StandardContentFormatException("Duplicate card id in pack")
    }
    return cards
}

private fun requireSupportedSchema(schemaVersion: Int) {
    if (schemaVersion != STANDARD_CONTENT_SCHEMA_VERSION) {
        throw StandardContentFormatException("Unsupported Standard content schema: $schemaVersion")
    }
}

private fun String.parseRootObject(label: String): JsonObject =
    try {
        Json.parseToJsonElement(this) as? JsonObject
            ?: throw StandardContentFormatException("$label must be a JSON object")
    } catch (failure: StandardContentFormatException) {
        throw failure
    } catch (_: Exception) {
        throw StandardContentFormatException("Invalid JSON in $label")
    }

private fun JsonElement.requireObject(label: String): JsonObject =
    this as? JsonObject ?: throw StandardContentFormatException("$label must be an object")

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name] as? JsonArray ?: throw StandardContentFormatException("$name must be an array")

private fun JsonObject.requiredString(name: String, maxLength: Int): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw StandardContentFormatException("$name must be a string")
    if (!primitive.isString) throw StandardContentFormatException("$name must be a string")
    return primitive.content
        .takeIf { it.isNotBlank() && it.length <= maxLength }
        ?: throw StandardContentFormatException("$name is blank or too long")
}

private fun JsonObject.optionalString(name: String, maxLength: Int): String? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive
        ?: throw StandardContentFormatException("$name must be a string")
    if (!primitive.isString) throw StandardContentFormatException("$name must be a string")
    return primitive.content
        .takeIf { it.isNotBlank() && it.length <= maxLength }
        ?: throw StandardContentFormatException("$name is blank or too long")
}

private fun JsonObject.requiredPositiveInt(name: String): Int =
    requiredNumberString(name).toIntOrNull()
        ?.takeIf { it > 0 }
        ?: throw StandardContentFormatException("$name must be a positive integer")

private fun JsonObject.requiredPositiveLong(name: String): Long =
    requiredNumberString(name).toLongOrNull()
        ?.takeIf { it > 0L }
        ?: throw StandardContentFormatException("$name must be a positive integer")

private fun JsonObject.optionalNonNegativeInt(name: String): Int? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive
        ?: throw StandardContentFormatException("$name must be an integer")
    if (primitive.isString) throw StandardContentFormatException("$name must be an integer")
    return primitive.content.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: throw StandardContentFormatException("$name must be a non-negative integer")
}

private fun JsonObject.requiredNumberString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw StandardContentFormatException("$name must be an integer")
    if (primitive.isString) throw StandardContentFormatException("$name must be an integer")
    return primitive.content
}

private fun JsonObject.requiredIdentifier(name: String): String =
    requiredString(name, 64).also(::validateIdentifier)

private fun JsonObject.requiredCardId(name: String): String =
    requiredString(name, 128).also {
        if (!CARD_ID_REGEX.matches(it)) {
            throw StandardContentFormatException("Invalid card id: $it")
        }
    }

private fun validateIdentifier(value: String) {
    if (!IDENTIFIER_REGEX.matches(value)) {
        throw StandardContentFormatException("Invalid identifier: $value")
    }
}

private fun JsonObject.requiredHttpsUrl(name: String): String =
    requiredString(name, 2_048).also(::validateHttpsUrl)

private fun JsonObject.optionalHttpsUrl(name: String): String? =
    optionalString(name, 2_048)?.also(::validateHttpsUrl)

private fun validateHttpsUrl(value: String) {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw StandardContentFormatException("Invalid HTTPS URL")
    }
    if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw StandardContentFormatException("Only HTTPS URLs without user info are allowed")
    }
}

private fun JsonObject.optionalStringSet(
    name: String,
    maxItems: Int,
    maxItemLength: Int,
): Set<String> {
    val array = this[name] as? JsonArray ?: return emptySet()
    if (array.size > maxItems) throw StandardContentFormatException("$name has too many items")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
            ?: throw StandardContentFormatException("$name[$index] must be a string")
        if (!primitive.isString) throw StandardContentFormatException("$name[$index] must be a string")
        primitive.content.takeIf { it.isNotBlank() && it.length <= maxItemLength }
            ?: throw StandardContentFormatException("$name[$index] is blank or too long")
    }.toSet()
}

private fun JsonObject.optionalStringMap(
    name: String,
    maxItems: Int,
    maxValueLength: Int,
): Map<String, String> {
    val objectValue = this[name] as? JsonObject ?: return emptyMap()
    if (objectValue.size > maxItems) throw StandardContentFormatException("$name has too many entries")
    return objectValue.mapValues { (key, element) ->
        if (!ATTRIBUTE_KEY_REGEX.matches(key)) {
            throw StandardContentFormatException("Invalid attribute key: $key")
        }
        val primitive = element as? JsonPrimitive
            ?: throw StandardContentFormatException("Attribute $key must be a string")
        if (!primitive.isString) throw StandardContentFormatException("Attribute $key must be a string")
        primitive.content.takeIf { it.length <= maxValueLength }
            ?: throw StandardContentFormatException("Attribute $key is too long")
    }
}

internal fun validateAssetPath(path: String) {
    if (
        !path.startsWith("assets/") ||
        path.startsWith("/") ||
        '\\' in path ||
        path.split('/').any { it.isBlank() || it == "." || it == ".." } ||
        !ASSET_EXTENSION_REGEX.containsMatchIn(path)
    ) {
        throw StandardContentFormatException("Invalid content asset path: $path")
    }
}

private val IDENTIFIER_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val CARD_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,127}")
private val ATTRIBUTE_KEY_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val ASSET_EXTENSION_REGEX = Regex("\\.(?:webp|png|jpe?g|ogg|wav)$", RegexOption.IGNORE_CASE)
