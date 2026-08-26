package com.example.othello

import android.app.Application

/** Process-level owners for device-only app services. */
class OthelloApplication : Application() {
    val localGameRecordStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JsonFileLocalGameRecordStore(this)
    }

    val localGameRecordPersistence by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalGameRecordPersistenceProcessOwner(localGameRecordStore)
    }

    val positionReviewStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JsonFilePositionReviewStore(this)
    }

    val theorySessionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JsonFileTheorySessionStore(this)
    }

    val theorySessionPersistence by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TheorySessionPersistenceProcessOwner(theorySessionStore)
    }

    val theoryAnalysisCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JsonFileTheoryAnalysisCache(this)
    }
}
