package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.network.ClockSnapshot

/** Monotonic two-player clock. UI refreshes only read snapshots; they do not own time. */
class MatchClock(
    private val initialMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(initialMillis > 0) }

    private var settled = ClockSnapshot(initialMillis, initialMillis)
    private var runningDisc: Disc? = null
    private var turnStartedAtMillis: Long = nowMillis()

    fun start(disc: Disc) {
        require(disc != Disc.EMPTY)
        if (runningDisc == null) {
            runningDisc = disc
            turnStartedAtMillis = nowMillis()
        }
    }

    fun snapshot(): ClockSnapshot {
        val running = runningDisc ?: return settled
        val elapsed = (nowMillis() - turnStartedAtMillis).coerceAtLeast(0)
        return when (running) {
            Disc.BLACK -> settled.copy(blackRemainingMillis = (settled.blackRemainingMillis - elapsed).coerceAtLeast(0))
            Disc.WHITE -> settled.copy(whiteRemainingMillis = (settled.whiteRemainingMillis - elapsed).coerceAtLeast(0))
            Disc.EMPTY -> settled
        }
    }

    fun adoptAndStart(snapshot: ClockSnapshot, nextDisc: Disc) {
        require(nextDisc != Disc.EMPTY)
        settled = ClockSnapshot(
            snapshot.blackRemainingMillis.coerceAtMost(initialMillis),
            snapshot.whiteRemainingMillis.coerceAtMost(initialMillis),
        )
        runningDisc = nextDisc
        turnStartedAtMillis = nowMillis()
    }

    fun stop(): ClockSnapshot {
        settled = snapshot()
        runningDisc = null
        return settled
    }
}
