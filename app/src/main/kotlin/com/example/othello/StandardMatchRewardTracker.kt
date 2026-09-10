package com.example.othello

import com.example.othello.analysis.api.StandardTensionLevel

internal enum class StandardWinReward {
    NONE,
    CLUTCH,
    COMEBACK,
}

/**
 * Tracks only Standard-campaign signals that can justify a stronger result celebration.
 *
 * COMEBACK is intentionally conservative: it requires Edax to have seen the AI at least
 * [COMEBACK_SCORE_THRESHOLD] discs ahead on one of its evaluated turns and the human to
 * still win without using Undo.
 *
 * CLUTCH is used when the match reached a CRITICAL presentation state but never crossed
 * the comeback threshold.
 */
internal class StandardMatchRewardTracker {
    private var criticalSeen = false
    private var opponentClearlyAheadSeen = false

    fun recordDecision(
        tension: StandardTensionLevel,
        opponentBestScore: Int?,
    ) {
        if (tension == StandardTensionLevel.CRITICAL) criticalSeen = true
        if (opponentBestScore != null && opponentBestScore >= COMEBACK_SCORE_THRESHOLD) {
            opponentClearlyAheadSeen = true
        }
    }

    fun classify(
        humanWon: Boolean,
        undoUsed: Boolean,
    ): StandardWinReward {
        if (!humanWon || undoUsed) return StandardWinReward.NONE
        return when {
            opponentClearlyAheadSeen -> StandardWinReward.COMEBACK
            criticalSeen -> StandardWinReward.CLUTCH
            else -> StandardWinReward.NONE
        }
    }

    fun reset() {
        criticalSeen = false
        opponentClearlyAheadSeen = false
    }

    private companion object {
        const val COMEBACK_SCORE_THRESHOLD = 8
    }
}
