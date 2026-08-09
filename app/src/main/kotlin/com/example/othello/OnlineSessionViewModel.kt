package com.example.othello

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.othello.data.supabase.SupabaseComponent
import com.example.othello.data.supabase.SupabaseModule
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.matchmaking.MatchmakingController

/** Retains the SDK component and one P2P owner across Activity recreation. */
class OnlineSessionViewModel(application: Application) : AndroidViewModel(application) {
    val componentResult: Result<SupabaseComponent> = SupabaseModule.create(scope = viewModelScope)
    val component: SupabaseComponent? = componentResult.getOrNull()
    val matchmaking: MatchmakingController? = component?.let { MatchmakingController(it.matchmakingRepository) }
    var coordinator: WebRtcMatchCoordinator? = null
        private set

    fun startCoordinator(userId: String, assignment: MatchAssignment, debugAutoPlay: Boolean): WebRtcMatchCoordinator {
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
