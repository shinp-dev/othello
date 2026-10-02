package com.example.othello

import com.example.othello.network.peopleplay.AvatarId
import kotlin.test.assertEquals
import org.junit.Test

class AccountIdentityTest {
    @Test
    fun derivedAvatarMatchesPeoplePlayServerMapping() {
        assertEquals(AvatarId.ADULT_MAN, derivePeoplePlayAvatar("00000000-0000-4000-8000-000000000001"))
        assertEquals(AvatarId.GIRL, derivePeoplePlayAvatar("FFFFFFFF-0000-4000-8000-000000000003"))
    }
}
