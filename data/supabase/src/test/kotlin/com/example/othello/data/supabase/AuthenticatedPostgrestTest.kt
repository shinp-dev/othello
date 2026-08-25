package com.example.othello.data.supabase

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.SessionRequiredException
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(SupabaseExperimental::class, SupabaseInternal::class, kotlin.time.ExperimentalTime::class)
class AuthenticatedPostgrestTest {
    @Test
    fun signalingUsesRestoredAndRefreshedSessionAndNeverFallsBackToPublishableKey(): Unit = runBlocking {
        val authorizationHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            authorizationHeaders += request.headers[HttpHeaders.Authorization]
            respond(
                content = "null",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = createSupabaseClient("https://example.supabase.co", "sb_publishable_test") {
            httpEngine = engine
            installApplicationSupabasePlugins {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSetupPlatform = false
                alwaysAutoRefresh = false
            }
        }
        val signaling = SupabaseRealtimeSignalingDataSource(client, this)
        val envelope = SignalingEnvelope(
            matchId = "00000000-0000-0000-0000-000000000001",
            senderUserId = "00000000-0000-0000-0000-000000000002",
            type = "ANSWER",
            sdp = "v=0",
        )

        try {
            client.auth.importSession(session("restored-user-jwt"), autoRefresh = false)
            signaling.publish(envelope)

            client.auth.importSession(session("refreshed-user-jwt"), autoRefresh = false)
            signaling.publish(envelope)

            client.auth.clearSession()
            assertFailsWith<SessionRequiredException> {
                signaling.publish(envelope)
            }

            assertEquals(
                listOf<String?>("Bearer restored-user-jwt", "Bearer refreshed-user-jwt"),
                authorizationHeaders,
            )
        } finally {
            signaling.close()
            client.close()
        }
    }

    private fun session(accessToken: String) = UserSession(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        expiresIn = 3_600,
        tokenType = "bearer",
    )
}
