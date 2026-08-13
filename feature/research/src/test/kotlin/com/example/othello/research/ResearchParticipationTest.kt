package com.example.othello.research

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchParticipationTest {
    @Test
    fun consentV1HasStableVersionAndDigest() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ResearchConsent.canonicalText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(1, ResearchConsent.version)
        assertEquals(ResearchConsent.sha256, digest)
        assertEquals(8, ResearchConsent.statements.size)
        assertTrue(ResearchConsent.statements.any { "Give-to-Get" in it })
        assertFalse(ResearchConsent.canonicalText.contains("匿名加工情報"))
    }

    @Test
    fun foundationStatusKeepsEligibilitySeparateFromParticipation() {
        val status = participationStatus(collectionEnabled = false)

        assertTrue(status.participationOn)
        assertFalse(status.eligible)
        assertFalse(status.collectionAllowed)
    }

    @Test
    fun collectionStatusCopyMatchesDisabledBackendState() {
        val copy = participationStatus(collectionEnabled = false).collectionStatusCopy()

        assertEquals("研究棋譜の収集状態: 停止中", copy.status)
        assertTrue(copy.explanation.contains("収集が有効な期間"))
        assertFalse(copy.explanation.contains("このバージョンでは研究データを収集しません"))
    }

    @Test
    fun collectionStatusCopyMatchesEnabledBackendState() {
        val copy = participationStatus(collectionEnabled = true).collectionStatusCopy()

        assertEquals("研究棋譜の収集状態: 有効", copy.status)
        assertTrue(copy.explanation.contains("研究参加がONの期間"))
        assertTrue(RESEARCH_PUBLICATION_PRIVACY_COPY.contains("集合統計"))
        assertTrue(RESEARCH_PUBLICATION_PRIVACY_COPY.contains("個別プレイヤー"))
    }

    private fun participationStatus(collectionEnabled: Boolean) = ResearchParticipationStatus(
            participationOn = true,
            currentConsentVersion = 1,
            agreedConsentVersion = 1,
            reconsentRequired = false,
            researchSubjectLinked = true,
            currentPeriodExists = true,
            currentParticipationId = "period-1",
            currentPeriodStartedAtEpochMillis = 1L,
            eligible = false,
            canViewResearchData = false,
            qualifyingGameCount = 0,
            requiredGameCount = 10,
            windowDays = 90,
            collectionEnabled = collectionEnabled,
            collectionAllowed = false,
        )
}
