package com.example.othello

import com.example.othello.network.peopleplay.AvatarId
import com.example.othello.network.peopleplay.PeoplePlayParticipantSummary
import com.example.othello.network.peopleplay.PeoplePlayPlayers
import com.example.othello.network.peopleplay.PeoplePlayProtocolCodec
import com.example.othello.network.peopleplay.PeoplePlaySeats
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl
import java.io.IOException
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class PeoplePlayLobbyEntry(
    val roomId: String,
    val timeControl: TimeControl,
    val phase: RoomPhase,
    val seats: PeoplePlaySeats,
    val players: PeoplePlayPlayers?,
    val spectatorCount: Int,
)

internal sealed interface PeoplePlayOpenFailure {
    data class Http(val status: Int, val errorCode: String?) : PeoplePlayOpenFailure
    data object Transport : PeoplePlayOpenFailure
    data object Protocol : PeoplePlayOpenFailure
}

internal interface PeoplePlaySocket {
    fun send(text: String): Boolean
    fun close()
}

internal interface PeoplePlaySocketListener {
    fun onOpen(memberId: String)
    fun onText(text: String)
    fun onClosed()
    fun onFailure(failure: PeoplePlayOpenFailure)
}

internal interface PeoplePlayRepository {
    suspend fun listRooms(accessToken: String): List<PeoplePlayLobbyEntry>
    fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket
    fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket
}

internal class UnconfiguredPeoplePlayRepository : PeoplePlayRepository {
    override suspend fun listRooms(accessToken: String): List<PeoplePlayLobbyEntry> =
        throw PeoplePlayLobbyException("API_UNAVAILABLE")

    override fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket = unavailable(listener)
    override fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket = unavailable(listener)

    private fun unavailable(listener: PeoplePlaySocketListener): PeoplePlaySocket {
        listener.onFailure(PeoplePlayOpenFailure.Http(503, "API_UNAVAILABLE"))
        return object : PeoplePlaySocket {
            override fun send(text: String) = false
            override fun close() = Unit
        }
    }
}

/** Uses the existing Supabase session token and the Phase 1 codec for every game frame. */
internal class OkHttpPeoplePlayRepository(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : PeoplePlayRepository {
    private val apiBaseUrl = normalizeApiBaseUrl(baseUrl)

    override suspend fun listRooms(accessToken: String): List<PeoplePlayLobbyEntry> = withContext(ioDispatcher) {
        require(accessToken.isNotBlank())
        val request = Request.Builder()
            .url("$apiBaseUrl/v1/people-play/rooms")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PeoplePlayHttpException(response.code, response.body?.string().orEmpty().safeErrorCode())
            val body = response.body?.string() ?: throw IOException("Empty lobby response")
            parsePeoplePlayRoomList(body)
        }
    }

    override fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket = openSocket(
        path = "/v1/people-play/rooms/new/socket?timeControl=${TimeControl.TEN_MINUTES.name}",
        accessToken = accessToken,
        listener = listener,
    )

    override fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket {
        require(roomId.isNotBlank())
        return openSocket(
            path = "/v1/people-play/rooms/${encodePathSegment(roomId)}/socket",
            accessToken = accessToken,
            listener = listener,
        )
    }

    private fun openSocket(path: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket {
        require(accessToken.isNotBlank())
        val request = Request.Builder()
            .url(apiBaseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + path)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val memberId = response.header("X-People-Play-Member-Id")
                if (response.code != 101 || memberId.isNullOrBlank()) {
                    listener.onFailure(PeoplePlayOpenFailure.Protocol)
                    webSocket.close(1002, "BAD_HANDSHAKE")
                    return
                }
                listener.onOpen(memberId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener.onFailure(PeoplePlayOpenFailure.Protocol)
                webSocket.close(1003, "TEXT_ONLY")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                val safeCode = response?.body?.use { it.string().safeErrorCode() }
                listener.onFailure(
                    if (status != null) PeoplePlayOpenFailure.Http(status, safeCode)
                    else PeoplePlayOpenFailure.Transport,
                )
            }
        })
        return OkHttpPeoplePlaySocket(socket)
    }

    private fun normalizeApiBaseUrl(value: String): String {
        val parsed = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("People Play API URL is not configured")
        require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank()) {
            "People Play API URL is not configured"
        }
        require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
            "People Play API URL must be an origin"
        }
        return value.trim().trimEnd('/')
    }

    private fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private class OkHttpPeoplePlaySocket(private val webSocket: WebSocket) : PeoplePlaySocket {
        override fun send(text: String): Boolean = webSocket.send(text)
        override fun close() { webSocket.close(1000, "CLIENT_EXIT") }
    }
}

internal class PeoplePlayHttpException(val status: Int, val errorCode: String?) : IOException()

private fun String.safeErrorCode(): String? = runCatching {
    Json.parseToJsonElement(this).jsonObject["error"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.matches(Regex("^[A-Z_]{1,40}$")) }

internal fun parsePeoplePlayRoomList(body: String): List<PeoplePlayLobbyEntry> {
    val root = Json.parseToJsonElement(body).jsonObject
    require(root.keys == setOf("rooms"))
    val rooms = root.required("rooms").jsonArray
    return rooms.map { element ->
        val room = element.jsonObject
        require(room.keys == setOf("roomId", "timeControl", "phase", "seats", "players", "spectatorCount"))
        val playersElement = room.required("players")
        PeoplePlayLobbyEntry(
            roomId = room.requiredString("roomId").also { require(it.isNotBlank()) },
            timeControl = TimeControl.valueOf(room.requiredString("timeControl")),
            phase = RoomPhase.valueOf(room.requiredString("phase")).also { require(it != RoomPhase.CLOSED) },
            seats = room.required("seats").jsonObject.let { seats ->
                require(seats.keys == setOf("a", "b"))
                PeoplePlaySeats(seats.participant("a"), seats.participant("b"))
            },
            players = if (playersElement == JsonNull) null else playersElement.jsonObject.let { players ->
                require(players.keys == setOf("black", "white"))
                PeoplePlayPlayers(players.participant("black") ?: error("missing black"), players.participant("white") ?: error("missing white"))
            },
            spectatorCount = room.required("spectatorCount").jsonPrimitive.intOrNull
                ?.also { require(it >= 0) } ?: error("invalid spectatorCount"),
        )
    }
}

private fun JsonObject.required(name: String): JsonElement = getValue(name)
private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.content
private fun JsonObject.participant(name: String): PeoplePlayParticipantSummary? {
    val value = getValue(name)
    if (value == JsonNull) return null
    val participant = value.jsonObject
    require(participant.keys == setOf("memberId", "displayName", "avatarId"))
    return PeoplePlayParticipantSummary(
        memberId = participant.requiredString("memberId"),
        displayName = participant.requiredString("displayName"),
        avatarId = AvatarId.valueOf(participant.requiredString("avatarId")),
    )
}
