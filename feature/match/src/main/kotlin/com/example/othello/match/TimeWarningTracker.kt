package com.example.othello.match

const val TIME_WARNING_ONE_MINUTE_MILLIS = 60_000L
const val TIME_WARNING_THIRTY_SECONDS_MILLIS = 30_000L

enum class TimeWarning {
    ONE_MINUTE,
    THIRTY_SECONDS,
}

/** Emits each warning once when a match clock crosses its threshold from above. */
class TimeWarningTracker {
    private var lastRemainingMillis = 0L
    private var oneMinuteArmed = false
    private var thirtySecondsArmed = false

    fun reset(initialRemainingMillis: Long) {
        lastRemainingMillis = initialRemainingMillis.coerceAtLeast(0L)
        // A match that starts at or below a threshold must not replay old warnings.
        oneMinuteArmed = initialRemainingMillis > TIME_WARNING_ONE_MINUTE_MILLIS
        thirtySecondsArmed = initialRemainingMillis > TIME_WARNING_THIRTY_SECONDS_MILLIS
    }

    fun onRemainingChanged(remainingMillis: Long): List<TimeWarning> {
        val current = remainingMillis.coerceAtLeast(0L)
        val warnings = buildList {
            if (oneMinuteArmed &&
                lastRemainingMillis >= TIME_WARNING_ONE_MINUTE_MILLIS &&
                current < TIME_WARNING_ONE_MINUTE_MILLIS
            ) {
                add(TimeWarning.ONE_MINUTE)
                oneMinuteArmed = false
            }
            if (thirtySecondsArmed &&
                lastRemainingMillis >= TIME_WARNING_THIRTY_SECONDS_MILLIS &&
                current < TIME_WARNING_THIRTY_SECONDS_MILLIS
            ) {
                add(TimeWarning.THIRTY_SECONDS)
                thirtySecondsArmed = false
            }
        }
        lastRemainingMillis = current
        return warnings
    }
}
