package com.example.othello

import com.example.othello.records.LocalGameRecordStore
import com.example.othello.game.GameState
import com.example.othello.network.peopleplay.PeoplePlayProtocolCodec
import com.example.othello.network.peopleplay.PeoplePlayRoomSnapshot
import com.example.othello.network.peopleplay.PeoplePlaySeats
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeoplePlayLifecycleTest {
    @Test
    fun successfulRoomCreateRefreshesLobbyAfterFirstServerSnapshot() = runBlocking {
        val repository = CreateSuccessRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = PeoplePlaySessionOwner(
            scope,
            repository,
            { "fixture-token" },
            LocalGameRecordPersistenceCoordinator(RecordingStore(), scope),
        )
        try {
            session.createRoom()
            assertEquals(0, repository.listCalls.get())
            repository.listener.onText(
                PeoplePlayProtocolCodec.encodeServerMessage(
                    PeoplePlayRoomSnapshot(
                        roomId = "created-room",
                        phase = RoomPhase.WAITING,
                        timeControl = TimeControl.TEN_MINUTES,
                        seats = PeoplePlaySeats(null, null),
                        players = null,
                        wirePly = 0,
                        board = GameState().board.toWireBoard(),
                        move = null,
                        nextTurn = PlayerColor.BLACK,
                        terminalCandidate = false,
                        resultCheckPly = null,
                        spectatorCount = 0,
                        spectatorAvatarPreview = emptyList(),
                    ),
                ),
            )
            assertEquals(1, repository.listCalls.get())
            assertEquals("created-room", session.activeRoom.value?.state?.value?.roomId)
        } finally {
            session.leaveRoom()
            scope.cancel()
        }
    }

    @Test
    fun lobbyFetchesImmediatelyPollsOnceStopsWhileHiddenAndRefreshesOnForeground() = runBlocking {
        val repository = CountingRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lobby = PeoplePlayLobbyController(scope, repository, { "fixture-token" }, pollIntervalMillis = 45)
        try {
            lobby.setLifecycle(isVisible = true, isForeground = true)
            lobby.setLifecycle(isVisible = true, isForeground = true)
            delay(130)
            val foregroundCalls = repository.listCalls.get()
            assertTrue(foregroundCalls in 2..4, "Expected one 45ms polling loop, got $foregroundCalls requests")

            lobby.setLifecycle(isVisible = true, isForeground = false)
            val pausedCalls = repository.listCalls.get()
            delay(100)
            assertEquals(pausedCalls, repository.listCalls.get())

            lobby.setLifecycle(isVisible = false, isForeground = true)
            delay(70)
            assertEquals(pausedCalls, repository.listCalls.get())

            lobby.setLifecycle(isVisible = true, isForeground = true)
            delay(10)
            assertTrue(repository.listCalls.get() > pausedCalls)
        } finally {
            lobby.setLifecycle(isVisible = false, isForeground = false)
            scope.cancel()
        }
    }

    @Test
    fun joinFailureRefreshesLobbyAndSurfacesSanitizedRoomError() = runBlocking {
        val repository = CountingRepository(failJoinWith = PeoplePlayOpenFailure.Http(404, "ROOM_NOT_FOUND"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = RecordingStore()
        val persistence = LocalGameRecordPersistenceCoordinator(store, scope)
        val session = PeoplePlaySessionOwner(scope, repository, { "fixture-token" }, persistence)
        try {
            session.joinRoom("stale-room")
            delay(20)
            assertEquals(1, repository.listCalls.get())
            assertEquals("ROOM_NOT_FOUND", session.lobby.state.value.errorCode)
            assertEquals(null, session.activeRoom.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingRoomOpenBeforeTokenCompletesNeverCreatesLateSocket() = runBlocking {
        val tokenGate = CompletableDeferred<String?>()
        val repository = CountingRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = PeoplePlaySessionOwner(
            scope,
            repository,
            { withContext(NonCancellable) { tokenGate.await() } },
            LocalGameRecordPersistenceCoordinator(RecordingStore(), scope),
        )
        try {
            session.createRoom()
            session.cancelOpening()
            tokenGate.complete("fixture-token")
            delay(20)

            assertEquals(null, session.activeRoom.value)
            assertEquals(0, repository.createCalls.get())
        } finally {
            scope.cancel()
        }
    }

    private class CountingRepository(
        private val failJoinWith: PeoplePlayOpenFailure? = null,
    ) : PeoplePlayRepository {
        val listCalls = AtomicInteger()
        val createCalls = AtomicInteger()
        override suspend fun listRooms(accessToken: String): List<PeoplePlayLobbyEntry> {
            listCalls.incrementAndGet()
            return emptyList()
        }
        override fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket =
            FakeSocket().also {
                createCalls.incrementAndGet()
                listener.onFailure(PeoplePlayOpenFailure.Transport)
            }
        override fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket =
            FakeSocket().also { listener.onFailure(requireNotNull(failJoinWith)) }
    }

    private class CreateSuccessRepository : PeoplePlayRepository {
        val listCalls = AtomicInteger()
        lateinit var listener: PeoplePlaySocketListener
        override suspend fun listRooms(accessToken: String): List<PeoplePlayLobbyEntry> {
            listCalls.incrementAndGet()
            return emptyList()
        }
        override fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket {
            this.listener = listener
            listener.onOpen("member-creator")
            return FakeSocket()
        }
        override fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener) =
            error("Not used in this test")
    }

    private class FakeSocket : PeoplePlaySocket {
        override fun send(text: String) = true
        override fun close() = Unit
    }

    private class RecordingStore : LocalGameRecordStore {
        override suspend fun list(limit: Int) = emptyList<com.example.othello.records.LocalGameRecord>()
        override suspend fun save(record: com.example.othello.records.LocalGameRecord) = Unit
        override suspend fun delete(localId: String) = Unit
    }
}
