package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardCollectionUiContractTest {
    private val source =
        File("src/main/kotlin/com/example/othello/StandardCollectionScreen.kt").readText()

    @Test
    fun collectionLoadsBundledContentOffTheMainThreadAndUsesLocalObtainedIds() {
        assertTrue("withContext(Dispatchers.IO)" in source)
        assertTrue("application.standardContent.snapshot()" in source)
        assertTrue("collectionStore.obtainedCardIds(userId)" in source)
        assertFalse("StandardContentIndexFetcher" in source)
        assertFalse("refresh(" in source)
    }

    @Test
    fun uncollectedCardsStayConcealedAndCannotOpenDetails() {
        assertTrue("R.string.standard_collection_unknown_title" in source)
        assertTrue("R.string.standard_collection_locked_supporting" in source)
        assertTrue("onOpen = if (obtained)" in source)
        assertTrue("takeIf { it.card.id in obtainedCardIds }" in source)
    }

    @Test
    fun collectionUiHasProgressCategoryFiltersAndSourceLinksWithoutAdvancedDependencies() {
        assertTrue("LinearProgressIndicator(" in source)
        assertTrue("FilterChip(" in source)
        assertTrue("StandardCollectionFilter.TRIVIA" in source)
        assertFalse("StandardCollectionFilter.ALL" in source)
        assertFalse("StandardCollectionFilter.UNOBTAINED" in source)
        assertTrue("R.string.standard_collection_open_source" in source)
        assertTrue("StandardContentArtwork(" in source)
        assertTrue("entry.card.rarity.visualColors" in source)
        assertTrue("maxLines = 1" in source)
        assertTrue("TextOverflow.Ellipsis" in source)
        assertFalse("AuthenticatedApp" in source)
        assertFalse("Edax" in source)
        assertFalse("AppDestination" in source)
    }
}
