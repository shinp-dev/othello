package com.example.othello

import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocketListener
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PeoplePlayRepositoryTest {
    @Test
    fun roomListMapsAllSupportedTimeControlsAndBothProjectionPhases() {
        val durations = listOf(
            "TWENTY_MINUTES", "FIFTEEN_MINUTES", "TEN_MINUTES", "FIVE_MINUTES", "THREE_MINUTES",
        )
        val json = durations.mapIndexed { index, duration ->
            val phase = if (index == 0) "WAITING" else "PLAYING"
            val seats = if (phase == "WAITING") """{"a":{"memberId":"m-a","displayName":"A","avatarId":"ADULT_MAN"},"b":null}"""
            else """{"a":{"memberId":"m-a","displayName":"A","avatarId":"ADULT_MAN"},"b":{"memberId":"m-b","displayName":"B","avatarId":"BOY"}}"""
            val players = if (phase == "WAITING") "null" else """{"black":{"memberId":"m-a","displayName":"A","avatarId":"ADULT_MAN"},"white":{"memberId":"m-b","displayName":"B","avatarId":"BOY"}}"""
            """{"roomId":"room-$index","timeControl":"$duration","phase":"$phase","seats":$seats,"players":$players,"spectatorCount":$index}"""
        }.joinToString(",", prefix = "{\"rooms\":[", postfix = "]}")

        val rooms = parsePeoplePlayRoomList(json)

        assertEquals(durations.map(TimeControl::valueOf), rooms.map { it.timeControl })
        assertEquals(RoomPhase.WAITING, rooms.first().phase)
        assertEquals(null, rooms.first().players)
        assertEquals(RoomPhase.PLAYING, rooms.last().phase)
        assertEquals("m-b", rooms.last().players?.white?.memberId)
        assertEquals(4, rooms.last().spectatorCount)
        assertEquals(R.string.play_lobby_room_20, rooms[0].toLobbyRoom().nameRes)
        assertEquals(R.string.play_lobby_room_3, rooms[4].toLobbyRoom().nameRes)
    }

    @Test
    fun projectionParserRejectsUnknownFieldsAndClosedRows() {
        val extraField = """{"rooms":[{"roomId":"r","timeControl":"TEN_MINUTES","phase":"WAITING","seats":{"a":null,"b":null},"players":null,"spectatorCount":0,"userId":"private"}]}"""
        val closed = """{"rooms":[{"roomId":"r","timeControl":"TEN_MINUTES","phase":"CLOSED","seats":{"a":null,"b":null},"players":null,"spectatorCount":0}]}"""
        assertFails { parsePeoplePlayRoomList(extraField) }
        assertFails { parsePeoplePlayRoomList(closed) }
    }

    @Test
    fun roomListUsesBearerAndWebSocketUpgradeExposesMemberHeader() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"rooms":[]}""").addHeader("Content-Type", "application/json"))
            val repo = OkHttpPeoplePlayRepository(server.url("/").toString().trimEnd('/'))
            assertEquals(emptyList(), repo.listRooms("fixture-token"))
            val listRequest = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(listRequest)
            assertEquals("Bearer fixture-token", listRequest.getHeader("Authorization"))
            assertEquals("/v1/people-play/rooms", listRequest.path)

            val opened = CountDownLatch(1)
            var memberId: String? = null
            server.enqueue(
                MockResponse()
                    .addHeader("X-People-Play-Member-Id", "member-fixture")
                    .withWebSocketUpgrade(object : WebSocketListener() {}),
            )
            repo.joinRoom("room-fixture", "fixture-token", object : PeoplePlaySocketListener {
                override fun onOpen(value: String) { memberId = value; opened.countDown() }
                override fun onText(text: String) = Unit
                override fun onClosed() = Unit
                override fun onFailure(failure: PeoplePlayOpenFailure) { opened.countDown() }
            })
            check(opened.await(3, TimeUnit.SECONDS))
            assertEquals("member-fixture", memberId)
            val joinRequest = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(joinRequest)
            assertEquals("Bearer fixture-token", joinRequest.getHeader("Authorization"))
            assertEquals("/v1/people-play/rooms/room-fixture/socket", joinRequest.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun createRouteIsAlwaysFixedToTenMinutes() {
        val server = MockWebServer()
        server.start()
        try {
            val opened = CountDownLatch(1)
            server.enqueue(
                MockResponse().addHeader("X-People-Play-Member-Id", "creator-fixture")
                    .withWebSocketUpgrade(object : WebSocketListener() {}),
            )
            val socket = OkHttpPeoplePlayRepository(server.url("/").toString().trimEnd('/'))
                .createRoom("fixture-token", object : PeoplePlaySocketListener {
                    override fun onOpen(memberId: String) { opened.countDown() }
                    override fun onText(text: String) = Unit
                    override fun onClosed() = Unit
                    override fun onFailure(failure: PeoplePlayOpenFailure) { opened.countDown() }
                })
            check(opened.await(3, TimeUnit.SECONDS))
            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("/v1/people-play/rooms/new/socket?timeControl=TEN_MINUTES", request.path)
            socket.close()
        } finally {
            server.shutdown()
        }
    }
}
