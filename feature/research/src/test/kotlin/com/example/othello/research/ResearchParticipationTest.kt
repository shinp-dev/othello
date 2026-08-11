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
        val status = ResearchParticipationStatus(
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
            collectionEnabled = false,
            collectionAllowed = false,
        )

        assertTrue(status.participationOn)
        assertFalse(status.eligible)
        assertFalse(status.collectionAllowed)
    }
}
