package com.example.othello

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class StandardRealEventsTest {
    @Test
    fun decoderSortsNorthToSouthThenByDateAndEventName() {
        val events = decodeStandardRealEvents(
            """
            [
              {
                "prefectureCode": "27",
                "prefectureName": "大阪府",
                "date": "2026-11-03",
                "eventName": "オセロ交流会",
                "venueName": "大阪ホール",
                "sourceUrl": "https://example.com/osaka"
              },
              {
                "prefectureCode": "13",
                "prefectureName": "東京都",
                "date": "2026-10-18",
                "eventName": "親子オセロ体験会",
                "venueName": "東京会館",
                "sourceUrl": "https://example.com/tokyo-family"
              },
              {
                "prefectureCode": "01",
                "prefectureName": "北海道",
                "date": "2026-12-01",
                "eventName": "札幌オセロ会",
                "venueName": "札幌センター",
                "sourceUrl": "https://example.com/hokkaido"
              },
              {
                "prefectureCode": "13",
                "prefectureName": "東京都",
                "date": "2026-10-18",
                "eventName": "オセロ教室",
                "venueName": "東京文化館",
                "sourceUrl": "https://example.com/tokyo-class"
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "01|2026-12-01|札幌オセロ会",
                "13|2026-10-18|オセロ教室",
                "13|2026-10-18|親子オセロ体験会",
                "27|2026-11-03|オセロ交流会",
            ),
            events.map { "${it.prefectureCode}|${it.date}|${it.eventName}" },
        )
    }

    @Test
    fun decoderRejectsInvalidPrefectureDateAndSourceUrl() {
        val base = """
            [{
              "prefectureCode": "%s",
              "prefectureName": "東京都",
              "date": "%s",
              "eventName": "イベント",
              "venueName": "会場",
              "sourceUrl": "%s"
            }]
        """.trimIndent()

        assertFailsWith<StandardRealEventFormatException> {
            decodeStandardRealEvents(base.format("99", "2026-10-18", "https://example.com/event"))
        }
        assertFailsWith<StandardRealEventFormatException> {
            decodeStandardRealEvents(base.format("13", "2026-02-30", "https://example.com/event"))
        }
        assertFailsWith<StandardRealEventFormatException> {
            decodeStandardRealEvents(base.format("13", "2026-10-18", "intent://example"))
        }
    }

    @Test
    fun fetcherLoadsConfiguredEndpointAndDecodesResponse() = runBlocking {
        var requestedUrl: String? = null
        val fetcher = GitHubStandardRealEventFetcher(
            transport = StandardRealEventHttpTransport { url ->
                requestedUrl = url
                StandardRealEventHttpResponse(
                    statusCode = 200,
                    body = """
                        [{
                          "prefectureCode": "13",
                          "prefectureName": "東京都",
                          "date": "2026-10-18",
                          "eventName": "イベント",
                          "venueName": "会場",
                          "sourceUrl": "https://example.com/event"
                        }]
                    """.trimIndent(),
                )
            },
            endpoint = "https://example.com/events.json",
        )

        val events = fetcher.fetch()

        assertEquals("https://example.com/events.json", requestedUrl)
        assertEquals(1, events.size)
        assertEquals("イベント", events.single().eventName)
    }

    @Test
    fun fetcherRejectsNonSuccessfulResponse() {
        val fetcher = GitHubStandardRealEventFetcher(
            transport = StandardRealEventHttpTransport {
                StandardRealEventHttpResponse(statusCode = 503, body = "")
            },
        )

        assertFailsWith<StandardRealEventFetchException> {
            runBlocking { fetcher.fetch() }
        }
    }
}
