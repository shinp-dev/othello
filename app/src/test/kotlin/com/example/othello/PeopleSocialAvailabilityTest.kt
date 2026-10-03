package com.example.othello

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class PeopleSocialAvailabilityTest {
    @Test
    fun todaySlotsUseLocalEveningAndThirtyMinuteSteps() {
        val now = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        val slots = peopleSocialTodaySlots(now, ZoneId.of("Asia/Tokyo"))

        assertEquals(
            listOf(
                "19:00 - 19:30",
                "19:30 - 20:00",
                "20:00 - 20:30",
                "20:30 - 21:00",
                "21:00 - 21:30",
                "21:30 - 22:00",
            ),
            slots.map { it.timeLabel },
        )
        assertEquals(6, slots.size)
        assertEquals(30L * 60L * 1000L, slots[1].slotStartEpochMillis - slots[0].slotStartEpochMillis)
        assertEquals(true, slots.all { it.selectable })
    }

    @Test
    fun availabilityParserRejectsUnknownFields() {
        assertEquals(
            listOf(PeopleSocialAvailability(1000L, 3, true)),
            parsePeopleSocialAvailabilityList("""{"slots":[{"slotStart":1000,"people":3,"selected":true}]}"""),
        )
        assertFails {
            parsePeopleSocialAvailabilityList(
                """{"slots":[{"slotStart":1000,"people":3,"selected":true,"userId":"private"}]}""",
            )
        }
    }

    @Test
    fun repositoryUsesBearerForReadAndWrite() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setBody("""{"slots":[{"slotStart":1000,"people":2,"selected":false}]}""")
                    .addHeader("Content-Type", "application/json"),
            )
            server.enqueue(
                MockResponse()
                    .setBody("""{"slot":{"slotStart":1000,"people":3,"selected":true}}""")
                    .addHeader("Content-Type", "application/json"),
            )
            val repository = OkHttpPeopleSocialAvailabilityRepository(server.url("/").toString().trimEnd('/'))

            assertEquals(
                listOf(PeopleSocialAvailability(1000L, 2, false)),
                repository.getAvailability(listOf(1000L), "fixture-token"),
            )
            assertEquals(
                PeopleSocialAvailability(1000L, 3, true),
                repository.setAvailability(1000L, true, "fixture-token"),
            )

            val get = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(get)
            assertEquals("Bearer fixture-token", get.getHeader("Authorization"))
            assertEquals("/v1/people-social/availability?slotStart=1000", get.path)

            val put = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(put)
            assertEquals("PUT", put.method)
            assertEquals("Bearer fixture-token", put.getHeader("Authorization"))
            assertEquals("/v1/people-social/availability", put.path)
            assertEquals("""{"slotStart":1000,"enabled":true}""", put.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
