package com.example.othello

import com.example.othello.profile.PlayDisplayName
import com.example.othello.profile.PlayProfileLookup
import com.example.othello.profile.PlayProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayProfileSelectionFlowTest {
    @Test
    fun noRareRollProducesThreeNormalCandidates() {
        val drawn = drawChanrivaNameCandidates(catalog(), rareRoll = { 0.10 }, chooseDistinct = firstRows)

        assertEquals(3, drawn.size)
        assertTrue(drawn.none(PlayDisplayName::isRare))
    }

    @Test
    fun rareRollProducesNormalNormalRareInTheThirdSlot() {
        val drawn = drawChanrivaNameCandidates(catalog(), rareRoll = { 0.099 }, chooseDistinct = firstRows)

        assertEquals(listOf(false, false, true), drawn.map(PlayDisplayName::isRare))
    }

    @Test
    fun aSingleDrawCanNeverIncludeMoreThanOneRareCandidate() {
        val drawn = drawChanrivaNameCandidates(catalog(), rareRoll = { 0.0 }, chooseDistinct = firstRows)

        assertEquals(1, drawn.count(PlayDisplayName::isRare))
        assertTrue(drawn.last().isRare)
    }

    @Test
    fun candidatesWithinOneDrawHaveDistinctDatabaseIds() {
        val drawn = drawChanrivaNameCandidates(catalog(), rareRoll = { 0.5 })

        assertEquals(3, drawn.map(PlayDisplayName::id).distinct().size)
    }

    @Test
    fun rerollExcludesEveryIdFromTheFirstDraw() = runBlocking {
        val rolls = ArrayDeque(listOf(0.5, 0.5))
        val repository = FakePlayProfileRepository()
        val flow = PlayProfileSelectionFlow(repository, MemoryPendingStore(), rareRoll = { rolls.removeFirst() })
        val first = (flow.enter(USER) as PlayProfileEntryResult.ChooseName).candidates

        val rerolled = flow.reroll(USER, first)

        assertEquals(3, rerolled?.size)
        assertTrue(rerolled!!.none { candidate -> first.any { it.id == candidate.id } })
    }

    @Test
    fun rerollUsesANewIndependentTenPercentRareRollAndTheSixIdsAreDistinct() = runBlocking {
        val rolls = ArrayDeque(listOf(0.5, 0.01))
        val repository = FakePlayProfileRepository()
        val flow = PlayProfileSelectionFlow(repository, MemoryPendingStore(), rareRoll = { rolls.removeFirst() }, chooseDistinct = firstRows)
        val first = (flow.enter(USER) as PlayProfileEntryResult.ChooseName).candidates
        val rerolled = flow.reroll(USER, first)!!

        assertEquals(0, rolls.size)
        assertEquals(listOf(false, false, false), first.map(ChanrivaNameCandidate::isRare))
        assertEquals(listOf(false, false, true), rerolled.map(ChanrivaNameCandidate::isRare))
        assertEquals(6, (first + rerolled).map(ChanrivaNameCandidate::id).distinct().size)
    }

    @Test
    fun rerollCanIndependentlyProduceNoRareAfterARareInitialDraw() = runBlocking {
        val rolls = ArrayDeque(listOf(0.01, 0.5))
        val repository = FakePlayProfileRepository()
        val flow = PlayProfileSelectionFlow(repository, MemoryPendingStore(), rareRoll = { rolls.removeFirst() }, chooseDistinct = firstRows)
        val first = (flow.enter(USER) as PlayProfileEntryResult.ChooseName).candidates
        val rerolled = flow.reroll(USER, first)!!

        assertEquals(listOf(false, false, true), first.map(ChanrivaNameCandidate::isRare))
        assertEquals(listOf(false, false, false), rerolled.map(ChanrivaNameCandidate::isRare))
        assertEquals(6, (first + rerolled).map(ChanrivaNameCandidate::id).distinct().size)
    }

    @Test
    fun unsafeCatalogSizesAreRejectedInsteadOfReturningPartialCandidates() {
        val onlyTwoNormals = catalog().filter { it.isRare || it.id <= 2L }
        val noRare = catalog().filterNot(PlayDisplayName::isRare)

        assertTrue(runCatching { drawChanrivaNameCandidates(onlyTwoNormals, rareRoll = { 0.5 }) }.isFailure)
        assertTrue(runCatching { drawChanrivaNameCandidates(noRare, rareRoll = { 0.5 }) }.isFailure)
        assertTrue(runCatching {
            drawChanrivaNameCandidates(catalog(), chooseDistinct = { pool, count -> List(count) { pool.first() } })
        }.isFailure)
    }

    @Test
    fun confirmationPersistsPendingBeforeInsertAndClearsOnlyAfterInsertSuccess() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPendingStore(events)
        val repository = FakePlayProfileRepository(events)
        val candidates = candidatesForConfirmation()
        val flow = PlayProfileSelectionFlow(repository, store)

        assertTrue(flow.confirm(USER, candidates.first().id, candidates))

        assertEquals(listOf("auth", "pending-save:$USER:1", "insert:1", "pending-clear:$USER"), events)
        assertNull(store.values[USER])
    }

    @Test
    fun insertFailureLeavesThePendingIdForRecovery() = runBlocking {
        val store = MemoryPendingStore()
        val repository = FakePlayProfileRepository().apply { insertFailure = true }
        val candidates = candidatesForConfirmation()
        val flow = PlayProfileSelectionFlow(repository, store)

        assertFalse(flow.confirm(USER, candidates.first().id, candidates))

        assertEquals(1L, store.values[USER])
        assertFalse(repository.events.any { it.startsWith("pending-clear") })
    }

    @Test
    fun pendingEntryRetriesInsertBeforeAnyProfileLookup() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPendingStore(events).apply { values[USER] = 77L }
        val repository = FakePlayProfileRepository(events).apply {
            insertFailure = true
            lookup = PlayProfileLookup.Exists(12L)
        }
        val flow = PlayProfileSelectionFlow(repository, store)

        assertEquals(PlayProfileEntryResult.Proceed, flow.enter(USER))
        assertEquals(listOf("auth", "pending-load:$USER", "insert:77", "profile", "pending-clear:$USER"), events)
    }

    @Test
    fun pendingRetryTreatsAnyExistingSupabaseProfileAsAuthoritative() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPendingStore(events).apply { values[USER] = 77L }
        val repository = FakePlayProfileRepository(events).apply {
            insertFailure = true
            lookup = PlayProfileLookup.Exists(999L)
        }
        val flow = PlayProfileSelectionFlow(repository, store)

        assertEquals(PlayProfileEntryResult.Proceed, flow.enter(USER))
        assertEquals(999L, repository.lookup.let { (it as PlayProfileLookup.Exists).displayNameId })
        assertNull(store.values[USER])
    }

    @Test
    fun pendingRetryKeepsTheChosenIdWhenInsertAndProfileReconciliationBothFail() = runBlocking {
        listOf(PlayProfileLookup.Missing, PlayProfileLookup.Failed(IllegalStateException("offline")))
            .forEach { lookup ->
                val store = MemoryPendingStore().apply { values[USER] = 77L }
                val repository = FakePlayProfileRepository().apply {
                    insertFailure = true
                    this.lookup = lookup
                }
                val flow = PlayProfileSelectionFlow(repository, store)

                assertEquals(PlayProfileEntryResult.NetworkError, flow.enter(USER))
                assertEquals(77L, store.values[USER])
                assertEquals(0, repository.catalogReads)
            }
    }

    @Test
    fun pendingForAnotherAuthenticatedUserIsNotUsed() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPendingStore(events).apply { values[OTHER_USER] = 55L }
        val repository = FakePlayProfileRepository(events)
        val flow = PlayProfileSelectionFlow(repository, store)

        val result = flow.enter(USER)

        assertTrue(result is PlayProfileEntryResult.ChooseName)
        assertNull(store.values[USER])
        assertEquals(55L, store.values[OTHER_USER])
        assertTrue(events.none { it == "insert:55" })
        assertTrue(events.contains("pending-load:$USER"))
    }

    @Test
    fun failedProfileSelectIsNotTreatedAsMissingAndCatalogIsFetchedOnEveryEntry() = runBlocking {
        val repository = FakePlayProfileRepository().apply { lookup = PlayProfileLookup.Failed(IllegalStateException()) }
        val flow = PlayProfileSelectionFlow(repository, MemoryPendingStore())

        assertEquals(PlayProfileEntryResult.NetworkError, flow.enter(USER))
        assertEquals(0, repository.catalogReads)

        repository.lookup = PlayProfileLookup.Missing
        assertTrue(flow.enter(USER) is PlayProfileEntryResult.ChooseName)
        assertTrue(flow.enter(USER) is PlayProfileEntryResult.ChooseName)
        assertEquals(2, repository.catalogReads)
    }

    @Test
    fun pendingSaveFailurePreventsTheSupabaseInsert() = runBlocking {
        val store = MemoryPendingStore().apply { saveResult = false }
        val repository = FakePlayProfileRepository()
        val candidates = candidatesForConfirmation()

        assertFalse(PlayProfileSelectionFlow(repository, store).confirm(USER, candidates.first().id, candidates))
        assertTrue(repository.events.none { it.startsWith("insert:") })
    }

    @Test
    fun candidatePresentationUsesDatabaseIdsAndTheRequiredPlateByPosition() {
        val candidates = listOf(
            PlayDisplayName(40, "one", false, 1),
            PlayDisplayName(41, "two", false, 2),
            PlayDisplayName(42, "three", false, 3),
        ).toChanrivaNameCandidates()
        val rareThird = listOf(
            PlayDisplayName(40, "one", false, 1),
            PlayDisplayName(41, "two", false, 2),
            PlayDisplayName(42, "three", true, 3),
        ).toChanrivaNameCandidates()

        assertEquals(listOf(40L, 41L, 42L), candidates.map(ChanrivaNameCandidate::id))
        assertEquals(R.drawable.chanriva_name_plate_emerald, candidates[0].plateRes)
        assertEquals(R.drawable.chanriva_name_plate_starry, candidates[1].plateRes)
        assertEquals(R.drawable.chanriva_name_plate_emerald, candidates[2].plateRes)
        assertEquals(R.drawable.chanriva_name_plate_rare, rareThird[2].plateRes)
    }

    private fun candidatesForConfirmation() = listOf(
        ChanrivaNameCandidate(1L, "one", false, R.drawable.chanriva_name_plate_emerald),
        ChanrivaNameCandidate(2L, "two", false, R.drawable.chanriva_name_plate_starry),
        ChanrivaNameCandidate(3L, "three", false, R.drawable.chanriva_name_plate_emerald),
    )

    private class FakePlayProfileRepository(
        val events: MutableList<String> = mutableListOf(),
    ) : PlayProfileRepository {
        var userId = USER
        var lookup: PlayProfileLookup = PlayProfileLookup.Missing
        var insertFailure = false
        var catalogReads = 0
        var names = catalog()

        override suspend fun currentAuthenticatedUserId(): String {
            events += "auth"
            return userId
        }

        override suspend fun findCurrentUserProfile(): PlayProfileLookup {
            events += "profile"
            return lookup
        }

        override suspend fun getActiveDisplayNames(): List<PlayDisplayName> {
            catalogReads += 1
            events += "catalog"
            return names
        }

        override suspend fun insertCurrentUserProfile(displayNameId: Long) {
            events += "insert:$displayNameId"
            if (insertFailure) error("insert failed")
        }
    }

    private class MemoryPendingStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : PendingPlayProfileStore {
        val values = mutableMapOf<String, Long>()
        var saveResult = true

        override suspend fun load(userId: String): Long? {
            events += "pending-load:$userId"
            return values[userId]
        }

        override suspend fun save(userId: String, displayNameId: Long): Boolean {
            events += "pending-save:$userId:$displayNameId"
            if (saveResult) values[userId] = displayNameId
            return saveResult
        }

        override suspend fun clear(userId: String): Boolean {
            events += "pending-clear:$userId"
            values.remove(userId)
            return true
        }
    }

    private companion object {
        const val USER = "authenticated-user"
        const val OTHER_USER = "other-user"

        val firstRows: (List<PlayDisplayName>, Int) -> List<PlayDisplayName> = { pool, count -> pool.take(count) }

        fun catalog(): List<PlayDisplayName> =
            (1L..8L).map { PlayDisplayName(it, "normal-$it", false, it.toInt()) } +
                (101L..102L).map { PlayDisplayName(it, "rare-$it", true, it.toInt()) }
    }
}
