package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class AuthErrorMessagesTest {
    @Test
    fun loginDoesNotExposeHttpRequestDetails() {
        val message = authErrorMessage(
            AuthOperation.LOGIN,
            IllegalStateException(
                "invalid_credentials URL: https://example.test/auth/v1/token Http Method: POST Headers: Authorization=[Bearer secret]",
            ),
        )

        assertEquals("メールアドレスまたはパスワードが正しくありません。", message)
        assertFalse("URL" in message)
        assertFalse("POST" in message)
        assertFalse("Bearer" in message)
    }

    @Test
    fun signupRateLimitUsesSafeGuidance() {
        val message = authErrorMessage(
            AuthOperation.SIGN_UP,
            IllegalStateException("over_email_send_rate_limit URL: https://example.test/auth/v1/signup"),
        )

        assertEquals("試行回数が多いため、しばらく時間をおいてからお試しください。", message)
        assertFalse("https://" in message)
    }

    @Test
    fun unknownSessionFailureUsesGenericMessage() {
        val message = authErrorMessage(
            AuthOperation.SESSION,
            IllegalStateException("unexpected transport failure: method=POST"),
        )

        assertEquals("ログイン状態を確認できませんでした。アプリを再起動してもう一度お試しください。", message)
        assertFalse("POST" in message)
    }
}
