package com.example.othello

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PeoplePlayLobbyState(
    val rooms: List<PeoplePlayLobbyEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val isOpeningRoom: Boolean = false,
    val errorCode: String? = null,
)

/** Visibility and app foreground jointly control the single lobby polling job. */
internal class PeoplePlayLobbyController(
    private val scope: CoroutineScope,
    private val repository: PeoplePlayRepository,
    private val accessToken: suspend () -> String?,
    private val pollIntervalMillis: Long = 10_000,
) {
    private val mutableState = MutableStateFlow(PeoplePlayLobbyState())
    val state: StateFlow<PeoplePlayLobbyState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private var visible = false
    private var foreground = false
    private var pollingJob: Job? = null

    fun setLifecycle(isVisible: Boolean, isForeground: Boolean) {
        visible = isVisible
        foreground = isForeground
        val shouldPoll = visible && foreground
        if (!shouldPoll) {
            pollingJob?.cancel()
            pollingJob = null
        } else if (pollingJob?.isActive != true) {
            pollingJob = scope.launch {
                refreshNow()
                while (visible && foreground) {
                    delay(pollIntervalMillis)
                    if (visible && foreground) refreshNow()
                }
            }
        }
    }

    fun refreshImmediately(clearError: Boolean = false): Job = scope.launch {
        if (clearError) mutableState.value = mutableState.value.copy(errorCode = null)
        refreshNow()
    }

    fun setOpening(opening: Boolean) {
        mutableState.value = mutableState.value.copy(isOpeningRoom = opening)
    }

    fun reportOpenFailure(failure: PeoplePlayOpenFailure) {
        val code = when (failure) {
            is PeoplePlayOpenFailure.Http -> failure.errorCode ?: when (failure.status) {
                404 -> "ROOM_NOT_FOUND"
                410 -> "ROOM_CLOSED"
                401 -> "AUTH_REQUIRED"
                else -> "CONNECTION_FAILED"
            }
            PeoplePlayOpenFailure.Protocol -> "BAD_HANDSHAKE"
            PeoplePlayOpenFailure.Transport -> "CONNECTION_FAILED"
        }
        mutableState.value = mutableState.value.copy(isOpeningRoom = false, errorCode = code)
        refreshImmediately()
    }

    suspend fun refreshNow() = refreshMutex.withLock {
        mutableState.value = mutableState.value.copy(isRefreshing = true)
        try {
            val token = accessToken()?.takeIf(String::isNotBlank)
                ?: throw PeoplePlayLobbyException("AUTH_REQUIRED")
            val rooms = repository.listRooms(token)
            mutableState.value = mutableState.value.copy(rooms = rooms, isRefreshing = false)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mutableState.value = mutableState.value.copy(isRefreshing = false)
            throw cancelled
        } catch (failure: PeoplePlayLobbyException) {
            mutableState.value = mutableState.value.copy(isRefreshing = false, errorCode = failure.code)
        } catch (failure: Throwable) {
            val code = (failure as? PeoplePlayHttpException)?.errorCode ?: "CONNECTION_FAILED"
            mutableState.value = mutableState.value.copy(isRefreshing = false, errorCode = code)
        }
    }
}

internal class PeoplePlayLobbyException(val code: String) : Exception()

/** Process-owned room socket/state holder. Compose navigation only observes it. */
internal class PeoplePlaySessionOwner(
    private val scope: CoroutineScope,
    private val repository: PeoplePlayRepository,
    private val accessToken: suspend () -> String?,
    private val persistence: LocalGameRecordPersistenceCoordinator,
) {
    val lobby = PeoplePlayLobbyController(scope, repository, accessToken)
    private val mutableActiveRoom = MutableStateFlow<PeoplePlayRoomStateHolder?>(null)
    val activeRoom: StateFlow<PeoplePlayRoomStateHolder?> = mutableActiveRoom.asStateFlow()

    fun createRoom() = openRoom(roomId = null)
    fun joinRoom(roomId: String) = openRoom(roomId)

    private fun openRoom(roomId: String?) {
        if (mutableActiveRoom.value != null || lobby.state.value.isOpeningRoom) return
        lobby.setOpening(true)
        lateinit var holder: PeoplePlayRoomStateHolder
        holder = PeoplePlayRoomStateHolder(
            scope = scope,
            repository = repository,
            accessToken = accessToken,
            persistence = persistence,
            onReady = { readyRoomId ->
                lobby.setOpening(false)
                if (roomId == null) lobby.refreshImmediately()
                check(readyRoomId.isNotBlank())
            },
            onOpenFailure = { failure ->
                lobby.reportOpenFailure(failure)
                if (mutableActiveRoom.value === holder) mutableActiveRoom.value = null
            },
        )
        mutableActiveRoom.value = holder
        holder.connect(roomId)
    }

    fun leaveRoom() {
        val holder = mutableActiveRoom.value ?: return
        holder.closeByUser()
        mutableActiveRoom.value = null
        lobby.setOpening(false)
        lobby.refreshImmediately(clearError = true)
    }

    fun cancelOpening() = leaveRoom()

    fun closeForSignOut() {
        mutableActiveRoom.value?.closeByUser()
        mutableActiveRoom.value = null
        lobby.setLifecycle(isVisible = false, isForeground = false)
    }

}
