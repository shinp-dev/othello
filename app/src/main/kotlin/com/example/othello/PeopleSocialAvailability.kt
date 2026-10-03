package com.example.othello

import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val PEOPLE_SOCIAL_SLOT_MILLIS = 30L * 60L * 1000L
private const val PEOPLE_SOCIAL_FIRST_HOUR = 19
private const val PEOPLE_SOCIAL_SLOT_COUNT = 6

internal data class PeopleSocialAvailability(
    val slotStartEpochMillis: Long,
    val people: Int,
    val selected: Boolean,
)

internal data class PeopleSocialUiSlot(
    val slotStartEpochMillis: Long,
    val timeLabel: String,
    val people: Int,
    val selected: Boolean,
    val selectable: Boolean,
    val saving: Boolean = false,
)

internal data class PeopleSocialUiState(
    val slots: List<PeopleSocialUiSlot> = emptyList(),
    val loading: Boolean = false,
    val errorCode: String? = null,
)

internal interface PeopleSocialAvailabilityRepository {
    suspend fun getAvailability(
        slotStarts: List<Long>,
        accessToken: String,
    ): List<PeopleSocialAvailability>

    suspend fun setAvailability(
        slotStartEpochMillis: Long,
        enabled: Boolean,
        accessToken: String,
    ): PeopleSocialAvailability

    suspend fun registerPushDevice(token: String, accessToken: String)

    suspend fun unregisterPushDevice(token: String, accessToken: String)
}

internal class UnconfiguredPeopleSocialAvailabilityRepository : PeopleSocialAvailabilityRepository {
    override suspend fun getAvailability(
        slotStarts: List<Long>,
        accessToken: String,
    ): List<PeopleSocialAvailability> = throw IOException("People Social API is unavailable")

    override suspend fun setAvailability(
        slotStartEpochMillis: Long,
        enabled: Boolean,
        accessToken: String,
    ): PeopleSocialAvailability = throw IOException("People Social API is unavailable")

    override suspend fun registerPushDevice(token: String, accessToken: String) {
        throw IOException("People Social API is unavailable")
    }

    override suspend fun unregisterPushDevice(token: String, accessToken: String) {
        throw IOException("People Social API is unavailable")
    }
}

internal class OkHttpPeopleSocialAvailabilityRepository(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PeopleSocialAvailabilityRepository {
    private val apiBaseUrl = normalizePeopleSocialApiBaseUrl(baseUrl)
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun getAvailability(
        slotStarts: List<Long>,
        accessToken: String,
    ): List<PeopleSocialAvailability> = withContext(ioDispatcher) {
        require(accessToken.isNotBlank())
        require(slotStarts.isNotEmpty())
        val url = "$apiBaseUrl/v1/people-social/availability".toHttpUrl().newBuilder().apply {
            slotStarts.forEach { addQueryParameter("slotStart", it.toString()) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PeopleSocialHttpException(response.code, body.peopleSocialErrorCode())
            }
            parsePeopleSocialAvailabilityList(body)
        }
    }

    override suspend fun setAvailability(
        slotStartEpochMillis: Long,
        enabled: Boolean,
        accessToken: String,
    ): PeopleSocialAvailability = withContext(ioDispatcher) {
        require(accessToken.isNotBlank())
        val body = buildJsonObject {
            put("slotStart", JsonPrimitive(slotStartEpochMillis))
            put("enabled", JsonPrimitive(enabled))
        }.toString()
        val request = Request.Builder()
            .url("$apiBaseUrl/v1/people-social/availability")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .put(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PeopleSocialHttpException(response.code, responseBody.peopleSocialErrorCode())
            }
            parsePeopleSocialAvailabilityMutation(responseBody)
        }
    }

    override suspend fun registerPushDevice(token: String, accessToken: String) {
        mutatePushDevice(token, accessToken, "PUT")
    }

    override suspend fun unregisterPushDevice(token: String, accessToken: String) {
        mutatePushDevice(token, accessToken, "DELETE")
    }

    private suspend fun mutatePushDevice(token: String, accessToken: String, method: String) = withContext(ioDispatcher) {
        require(accessToken.isNotBlank())
        require(token.isNotBlank())
        val body = buildJsonObject { put("token", JsonPrimitive(token)) }.toString()
        val requestBody = body.toRequestBody(jsonMediaType)
        val builder = Request.Builder()
            .url("$apiBaseUrl/v1/people-social/push-device")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
        val request = when (method) {
            "PUT" -> builder.put(requestBody).build()
            "DELETE" -> builder.delete(requestBody).build()
            else -> error("Unsupported push-device method")
        }
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PeopleSocialHttpException(response.code, responseBody.peopleSocialErrorCode())
            }
        }
    }
}

internal class PeopleSocialHttpException(
    val status: Int,
    val errorCode: String?,
) : IOException()

internal class PeopleSocialAvailabilityController(
    private val scope: CoroutineScope,
    private val repository: PeopleSocialAvailabilityRepository,
    private val accessToken: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow(
        PeopleSocialUiState(slots = peopleSocialTodaySlots(now(), zoneId)),
    )
    val state: StateFlow<PeopleSocialUiState> = _state

    private var refreshJob: Job? = null
    private val mutationJobs = mutableMapOf<Long, Job>()

    fun refresh() {
        refreshJob?.cancel()
        _state.value = _state.value.copy(loading = true, errorCode = null)
        refreshJob = scope.launch {
            mutationJobs.values.toList().joinAll()
            val currentSlots = peopleSocialTodaySlots(now(), zoneId)
            try {
                val token = accessToken()?.takeIf(String::isNotBlank)
                    ?: throw PeopleSocialHttpException(401, "AUTH_REQUIRED")
                val remote = repository.getAvailability(
                    currentSlots.map(PeopleSocialUiSlot::slotStartEpochMillis),
                    token,
                ).associateBy(PeopleSocialAvailability::slotStartEpochMillis)
                _state.value = PeopleSocialUiState(
                    slots = currentSlots.map { slot ->
                        val value = requireNotNull(remote[slot.slotStartEpochMillis]) {
                            "Missing social slot ${slot.slotStartEpochMillis}"
                        }
                        slot.copy(people = value.people, selected = value.selected)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val previousBySlot = _state.value.slots.associateBy(PeopleSocialUiSlot::slotStartEpochMillis)
                _state.value = PeopleSocialUiState(
                    slots = currentSlots.map { current ->
                        previousBySlot[current.slotStartEpochMillis]?.copy(
                            timeLabel = current.timeLabel,
                            selectable = current.selectable,
                            saving = false,
                        ) ?: current
                    },
                    errorCode = (error as? PeopleSocialHttpException)?.errorCode ?: "SOCIAL_UNAVAILABLE",
                )
            }
        }
    }

    fun toggle(slotStartEpochMillis: Long) {
        val before = _state.value
        val target = before.slots.firstOrNull { it.slotStartEpochMillis == slotStartEpochMillis } ?: return
        if (!target.selectable || target.saving) return
        val nextSelected = !target.selected
        val optimisticPeople = (target.people + if (nextSelected) 1 else -1).coerceAtLeast(0)
        _state.value = before.copy(
            slots = before.slots.map {
                if (it.slotStartEpochMillis == slotStartEpochMillis) {
                    it.copy(selected = nextSelected, people = optimisticPeople, saving = true)
                } else it
            },
            errorCode = null,
        )

        val job = scope.launch {
            try {
                val token = accessToken()?.takeIf(String::isNotBlank)
                    ?: throw PeopleSocialHttpException(401, "AUTH_REQUIRED")
                val saved = repository.setAvailability(slotStartEpochMillis, nextSelected, token)
                _state.value = _state.value.copy(
                    slots = _state.value.slots.map {
                        if (it.slotStartEpochMillis == slotStartEpochMillis) {
                            it.copy(
                                people = saved.people,
                                selected = saved.selected,
                                saving = false,
                            )
                        } else it
                    },
                    errorCode = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    slots = _state.value.slots.map {
                        if (it.slotStartEpochMillis == slotStartEpochMillis) target.copy(saving = false) else it
                    },
                    errorCode = (error as? PeopleSocialHttpException)?.errorCode ?: "SOCIAL_UNAVAILABLE",
                )
            }
        }
        mutationJobs[slotStartEpochMillis] = job
    }

    fun reset() {
        refreshJob?.cancel()
        refreshJob = null
        mutationJobs.values.forEach { it.cancel() }
        mutationJobs.clear()
        _state.value = PeopleSocialUiState(slots = peopleSocialTodaySlots(now(), zoneId))
    }
}

internal fun peopleSocialTodaySlots(
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<PeopleSocialUiSlot> {
    val nowZoned = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
    val first = nowZoned.toLocalDate().atTime(PEOPLE_SOCIAL_FIRST_HOUR, 0).atZone(zoneId)
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return (0 until PEOPLE_SOCIAL_SLOT_COUNT).map { index ->
        val start = first.plusMinutes(index * 30L)
        val end = start.plusMinutes(30)
        val startMillis = start.toInstant().toEpochMilli()
        PeopleSocialUiSlot(
            slotStartEpochMillis = startMillis,
            timeLabel = "${start.format(formatter)} - ${end.format(formatter)}",
            people = 0,
            selected = false,
            selectable = startMillis + PEOPLE_SOCIAL_SLOT_MILLIS > nowEpochMillis,
        )
    }
}

internal fun peopleSocialPreviewUiState(): PeopleSocialUiState {
    val labels = listOf(
        "19:00 - 19:30",
        "19:30 - 20:00",
        "20:00 - 20:30",
        "20:30 - 21:00",
        "21:00 - 21:30",
        "21:30 - 22:00",
    )
    val counts = listOf(2, 4, 7, 5, 3, 1)
    return PeopleSocialUiState(
        slots = labels.mapIndexed { index, label ->
            PeopleSocialUiSlot(
                slotStartEpochMillis = index.toLong(),
                timeLabel = label,
                people = counts[index] + if (index in 2..3) 1 else 0,
                selected = index in 2..3,
                selectable = true,
            )
        },
    )
}

internal fun parsePeopleSocialAvailabilityList(body: String): List<PeopleSocialAvailability> {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root.keys == setOf("slots"))
    return root.getValue("slots").jsonArray.map(::parsePeopleSocialAvailability)
}

internal fun parsePeopleSocialAvailabilityMutation(body: String): PeopleSocialAvailability {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root.keys == setOf("slot"))
    return parsePeopleSocialAvailability(root.getValue("slot"))
}

private fun parsePeopleSocialAvailability(element: kotlinx.serialization.json.JsonElement): PeopleSocialAvailability {
    val row = element.jsonObject
    require(row.keys == setOf("slotStart", "people", "selected"))
    val slotStart = row.getValue("slotStart").jsonPrimitive.longOrNull ?: error("Invalid slotStart")
    val people = row.getValue("people").jsonPrimitive.intOrNull ?: error("Invalid people")
    val selected = row.getValue("selected").jsonPrimitive.booleanOrNull ?: error("Invalid selected")
    require(people >= 0)
    return PeopleSocialAvailability(slotStart, people, selected)
}

private fun String.peopleSocialErrorCode(): String? = runCatching {
    Json.parseToJsonElement(this).jsonObject["error"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.matches(Regex("^[A-Z_]{1,40}$")) }

private fun normalizePeopleSocialApiBaseUrl(value: String): String {
    val parsed = runCatching { URI(value.trim()) }.getOrNull()
        ?: throw IllegalArgumentException("People Social API URL is not configured")
    require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank()) {
        "People Social API URL is not configured"
    }
    require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
        "People Social API URL must be an origin"
    }
    return value.trim().trimEnd('/')
}
