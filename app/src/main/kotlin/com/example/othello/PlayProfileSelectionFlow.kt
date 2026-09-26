package com.example.othello

import com.example.othello.profile.PlayDisplayName
import com.example.othello.profile.PlayProfileLookup
import com.example.othello.profile.PlayProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

internal sealed interface PlayProfileEntryResult {
    data object Proceed : PlayProfileEntryResult
    data class ChooseName(val candidates: List<ChanrivaNameCandidate>) : PlayProfileEntryResult
    data object NetworkError : PlayProfileEntryResult
}

/** Owns the ordering and retry rules for the two people-flow entry points. */
internal class PlayProfileSelectionFlow(
    private val repository: PlayProfileRepository,
    private val pendingStore: PendingPlayProfileStore,
    private val rareRoll: () -> Double = { Random.nextDouble() },
    private val chooseDistinct: (List<PlayDisplayName>, Int) -> List<PlayDisplayName> = { pool, count ->
        pool.shuffled(Random.Default).take(count)
    },
) {
    private val operationMutex = Mutex()

    suspend fun enter(expectedUserId: String): PlayProfileEntryResult = operationMutex.withLock {
        try {
            val userId = currentUserMatching(expectedUserId)
            val pendingDisplayNameId = pendingStore.load(userId)
            if (pendingDisplayNameId != null) {
                return@withLock retryPending(userId, pendingDisplayNameId)
            }

            when (repository.findCurrentUserProfile()) {
                is PlayProfileLookup.Exists -> PlayProfileEntryResult.Proceed
                PlayProfileLookup.Missing -> {
                    val catalog = repository.getActiveDisplayNames()
                    PlayProfileEntryResult.ChooseName(
                        drawChanrivaNameCandidates(catalog, rareRoll = rareRoll, chooseDistinct = chooseDistinct)
                            .toChanrivaNameCandidates(),
                    )
                }
                is PlayProfileLookup.Failed -> PlayProfileEntryResult.NetworkError
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlayProfileEntryResult.NetworkError
        }
    }

    /** A reroll always fetches the active catalog again and excludes every previous id. */
    suspend fun reroll(
        expectedUserId: String,
        currentCandidates: List<ChanrivaNameCandidate>,
    ): List<ChanrivaNameCandidate>? = operationMutex.withLock {
        try {
            currentUserMatching(expectedUserId)
            if (currentCandidates.size != CANDIDATE_COUNT) return@withLock null
            val catalog = repository.getActiveDisplayNames()
            drawChanrivaNameCandidates(
                activeNames = catalog,
                excludedIds = currentCandidates.mapTo(mutableSetOf(), ChanrivaNameCandidate::id),
                rareRoll = rareRoll,
                chooseDistinct = chooseDistinct,
            ).toChanrivaNameCandidates()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Persists locally before the authenticated insert; insert failures leave the pending id intact. */
    suspend fun confirm(
        expectedUserId: String,
        selectedDisplayNameId: Long,
        visibleCandidates: List<ChanrivaNameCandidate>,
    ): Boolean = operationMutex.withLock {
        try {
            val userId = currentUserMatching(expectedUserId)
            if (visibleCandidates.size != CANDIDATE_COUNT ||
                visibleCandidates.count { it.id == selectedDisplayNameId } != 1
            ) {
                return@withLock false
            }

            if (!pendingStore.save(userId, selectedDisplayNameId)) return@withLock false
            repository.insertCurrentUserProfile(selectedDisplayNameId)
            pendingStore.clear(userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun currentUserMatching(expectedUserId: String): String {
        require(expectedUserId.isNotBlank())
        val authenticatedUserId = repository.currentAuthenticatedUserId()
        check(authenticatedUserId == expectedUserId) {
            "The active Supabase user changed during play-profile selection"
        }
        return authenticatedUserId
    }

    private suspend fun retryPending(userId: String, pendingDisplayNameId: Long): PlayProfileEntryResult {
        val inserted = try {
            repository.insertCurrentUserProfile(pendingDisplayNameId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        if (inserted) {
            return if (pendingStore.clear(userId)) {
                PlayProfileEntryResult.Proceed
            } else {
                PlayProfileEntryResult.NetworkError
            }
        }

        return when (repository.findCurrentUserProfile()) {
            is PlayProfileLookup.Exists -> if (pendingStore.clear(userId)) {
                PlayProfileEntryResult.Proceed
            } else {
                PlayProfileEntryResult.NetworkError
            }
            PlayProfileLookup.Missing,
            is PlayProfileLookup.Failed -> PlayProfileEntryResult.NetworkError
        }
    }

    private companion object {
        const val CANDIDATE_COUNT = 3
    }
}
