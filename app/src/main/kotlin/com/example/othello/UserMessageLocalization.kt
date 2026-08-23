package com.example.othello

import android.content.Context

/**
 * Domain modules intentionally do not depend on the Android app resources. This
 * adapter keeps their existing state contracts while preventing known Japanese
 * status/error messages from leaking into the English UI.
 */
internal fun localizeUserMessage(context: Context, message: String?): String? {
    if (message == null) return null
    return when {
        message == "接続待ち" -> context.getString(R.string.match_status_connecting)
        message == "対局中" -> context.getString(R.string.match_status_playing)
        message == "結果を送信中" || message == "結果送信中" -> context.getString(R.string.match_status_sending)
        message == "相手の結果待ち" -> context.getString(R.string.match_status_waiting_result)
        message == "相手の開始確認待ち" -> context.getString(R.string.opponent_waiting)
        message == "接続が切断されました" -> context.getString(R.string.match_status_disconnected)
        message == "AI move failed" || message == "Edax AI move is unavailable" -> context.getString(R.string.ai_match_unavailable)
        message.contains("評価データ") -> context.getString(R.string.analysis_data_not_set)
        message.contains("オープニングブック") -> context.getString(R.string.opening_book)
        message.contains("研究データ") || message.contains("研究棋譜") -> context.getString(R.string.research_failed)
        message.any(::isJapaneseUserMessage) -> context.getString(R.string.error)
        else -> message
    }
}

private fun isJapaneseUserMessage(character: Char): Boolean =
    character in '\u3040'..'\u30ff' || character in '\u3400'..'\u9fff'
