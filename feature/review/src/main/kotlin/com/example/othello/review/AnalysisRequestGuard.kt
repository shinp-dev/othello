package com.example.othello.review

/** Rejects results from an analysis request superseded by navigation or another request. */
class AnalysisRequestGuard {
    private var generation = 0L

    @Synchronized
    fun begin(positionIdentity: String): AnalysisRequestToken =
        AnalysisRequestToken(++generation, positionIdentity)

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isCurrent(token: AnalysisRequestToken, positionIdentity: String): Boolean =
        token.generation == generation && token.positionIdentity == positionIdentity
}

class AnalysisRequestToken internal constructor(
    internal val generation: Long,
    internal val positionIdentity: String,
)
