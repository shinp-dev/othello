package com.example.othello

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.othello.data.supabase.SupabaseComponent
import com.example.othello.data.supabase.SupabaseModule
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.matchmaking.MatchmakingController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Retains the SDK component and one P2P owner across Activity recreation. */
class OnlineSessionViewModel(application: Application) : AndroidViewModel(application) {
    val componentResult: Result<SupabaseComponent> = SupabaseModule.create(scope = viewModelScope)
    val component: SupabaseComponent? = componentResult.getOrNull()
    val matchmaking: MatchmakingController? = component?.let { MatchmakingController(it.matchmakingRepository) }
    var coordinator: WebRtcMatchCoordinator? = null
        private set
    private val authController = AuthSessionController(
        gatewayResult = componentResult.map { it.authGateway },
        onAuthenticatedSessionEnding = {
            matchmaking?.reset()
            leaveCoordinator()
        },
    )
    internal val authState: StateFlow<AuthState> = authController.state

    init {
        viewModelScope.launch { authController.restoreSession() }
    }

    fun retrySessionRestore() {
        viewModelScope.launch { authController.restoreSession() }
    }

    suspend fun signIn(email: String, password: String) = authController.signIn(email, password)
    suspend fun signUp(email: String, password: String) = authController.signUp(email, password)
    suspend fun requestPasswordReset(email: String) = authController.requestPasswordReset(email)
    suspend fun signOut() = authController.signOut()
    suspend fun finishAccountDeletionSession() = authController.finishAccountDeletionSession()

    fun startCoordinator(
        userId: String,
        assignment: MatchAssignment,
        debugAutoPlay: Boolean,
        debugTimeControlMillis: Long? = null,
    ): WebRtcMatchCoordinator {
        coordinator?.takeIf { it.matchId == assignment.matchId }?.let { return it }
        coordinator?.close()
        val supabase = requireNotNull(component) { "Supabase is not configured" }
        return WebRtcMatchCoordinator(
            getApplication(),
            userId,
            assignment,
            supabase.signalingDataSource,
            supabase.onlineMatchRepository,
            viewModelScope,
            debugAutoPlay,
            debugTimeControlMillis,
        ).also {
            coordinator = it
            it.start()
        }
    }

    suspend fun leaveCoordinator() {
        val leaving = coordinator
        coordinator = null
        leaving?.leave()
    }

    override fun onCleared() {
        coordinator?.close()
        coordinator = null
        component?.close()
        super.onCleared()
    }
}
