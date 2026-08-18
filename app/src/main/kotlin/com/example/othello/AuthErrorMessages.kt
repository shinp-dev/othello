package com.example.othello

internal enum class AuthOperation {
    SESSION,
    LOGIN,
    SIGN_UP,
    SIGN_OUT,
}

/**
 * Converts Auth/HTTP failures into messages that are safe to show to users.
 * The original exception is intentionally never returned because Supabase
 * client errors may contain request URLs, methods, or headers.
 */
internal fun authErrorMessage(operation: AuthOperation, error: Throwable): String {
    val details = buildString {
        append(error.message.orEmpty())
        error.cause?.message?.let { append(' '); append(it) }
    }.lowercase()

    return when {
        operation == AuthOperation.LOGIN &&
            ("invalid_credentials" in details || "invalid login credentials" in details) ->
            "メールアドレスまたはパスワードが正しくありません。"
        "email_not_confirmed" in details || "email not confirmed" in details ->
            "確認メールのリンクを開いてからログインしてください。"
        "over_email_send_rate_limit" in details ||
            "over_request_rate_limit" in details ||
            "too many requests" in details ->
            "試行回数が多いため、しばらく時間をおいてからお試しください。"
        operation == AuthOperation.SIGN_UP ->
            "アカウントを作成できませんでした。入力内容を確認して、しばらくしてからもう一度お試しください。"
        operation == AuthOperation.SESSION ->
            "ログイン状態を確認できませんでした。アプリを再起動してもう一度お試しください。"
        operation == AuthOperation.SIGN_OUT ->
            "ログアウトに失敗しました。しばらくしてからもう一度お試しください。"
        else ->
            "ログインに失敗しました。メールアドレスとパスワードを確認して、もう一度お試しください。"
    }
}
