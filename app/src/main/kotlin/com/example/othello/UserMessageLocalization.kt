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
        message == "着手確認待ち" -> context.getString(R.string.match_status_move_confirming)
        message == "対局を同期しています" -> context.getString(R.string.match_status_synchronizing)
        message == "再接続中" -> context.getString(R.string.match_status_reconnecting)
        message == "相手の復帰を待っています" -> context.getString(R.string.match_status_opponent_grace)
        message == "P2P接続を再試行できます" || message == "再接続を再試行できます" ->
            context.getString(R.string.match_retry_connection)
        message == "開始確認を再試行できます" -> context.getString(R.string.match_retry_start_confirmation)
        message == "結果送信を再試行できます" -> context.getString(R.string.retry_result)
        message == "再接続を確認できませんでした" -> context.getString(R.string.match_reconnect_check_failed)
        message == "切断状態の保存を再試行しています" -> context.getString(R.string.match_disconnect_report_retrying)
        message.startsWith("対局プロトコルエラー:") -> context.getString(R.string.match_protocol_error)
        message == "対局状態を再確認できます" -> context.getString(R.string.match_state_recheck_available)
        message == "端末保存を再試行できます" -> context.getString(R.string.match_retry_local_save)
        message == "着手を再送しています" -> context.getString(R.string.match_move_resending)
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
