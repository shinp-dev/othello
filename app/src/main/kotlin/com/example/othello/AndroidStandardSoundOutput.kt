package com.example.othello

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Android adapter for Standard-mode presentation sounds.
 *
 * The domain/presentation layer emits semantic [StandardSoundCue] values; this class is the only
 * place that knows about Android audio APIs, bundled raw resources, volumes, and playback rates.
 * Advanced mode never instantiates this adapter.
 */
internal class AndroidStandardSoundOutput(
    context: Context,
) : StandardSoundOutput, AutoCloseable {
    private enum class AudioAsset(val resourceId: Int) {
        HEARTBEAT(R.raw.standard_heartbeat),
        STONE_PLACE(R.raw.standard_stone_place),
        OPPONENT_APPEAR(R.raw.standard_opponent_appear),
        HUMAN_WIN(R.raw.standard_human_win),
        HUMAN_LOSS(R.raw.standard_human_loss),
        DRAW(R.raw.standard_draw),
        LEVEL_CLEAR(R.raw.standard_level_clear),
        CAMPAIGN_CONQUERED(R.raw.standard_campaign_conquered),
    }

    private data class OneShotSpec(
        val asset: AudioAsset,
        val volume: Float,
        val rate: Float = 1f,
    )

    private enum class HeartbeatMode(
        val volume: Float,
        val rate: Float,
    ) {
        TENSE(volume = 0.28f, rate = 0.95f),
        CRITICAL(volume = 0.46f, rate = 1.35f),
    }

    private val lock = Any()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val sampleIds = mutableMapOf<AudioAsset, Int>()
    private val loadedSampleIds = mutableSetOf<Int>()
    private val pendingOneShots = ArrayDeque<StandardSoundCue>()

    private var desiredHeartbeat: HeartbeatMode? = null
    private var heartbeatStreamId = NO_STREAM
    private var hostActive = true
    private var closed = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(lock) {
                if (closed || status != LOAD_SUCCESS) return@synchronized
                loadedSampleIds += sampleId
                flushPendingForLocked(sampleId)
                if (sampleIds[AudioAsset.HEARTBEAT] == sampleId) syncHeartbeatLocked()
            }
        }
        synchronized(lock) {
            AudioAsset.entries.forEach { asset ->
                sampleIds[asset] = soundPool.load(context.applicationContext, asset.resourceId, LOAD_PRIORITY)
            }
        }
    }

    override fun emit(cue: StandardSoundCue) {
        synchronized(lock) {
            if (closed) return
            when (cue) {
                StandardSoundCue.HEARTBEAT_TENSE_START -> setHeartbeatLocked(HeartbeatMode.TENSE)
                StandardSoundCue.HEARTBEAT_CRITICAL_START -> setHeartbeatLocked(HeartbeatMode.CRITICAL)
                StandardSoundCue.HEARTBEAT_STOP -> clearHeartbeatLocked()
                else -> {
                    if (!hostActive) return
                    if (cue.isTerminalResultCue()) pendingOneShots.clear()
                    playOrQueueOneShotLocked(cue)
                }
            }
        }
    }

    /** Pause every Standard sound while the app is not in the foreground. */
    fun onHostStop() {
        synchronized(lock) {
            if (closed || !hostActive) return
            hostActive = false
            soundPool.autoPause()
        }
    }

    /** Resume an interrupted heartbeat, but never replay stale one-shot effects. */
    fun onHostStart() {
        synchronized(lock) {
            if (closed || hostActive) return
            hostActive = true
            soundPool.autoResume()
            syncHeartbeatLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            desiredHeartbeat = null
            pendingOneShots.clear()
            stopHeartbeatStreamLocked()
            soundPool.release()
            loadedSampleIds.clear()
            sampleIds.clear()
        }
    }

    private fun setHeartbeatLocked(mode: HeartbeatMode) {
        if (desiredHeartbeat == mode && heartbeatStreamId != NO_STREAM) return
        desiredHeartbeat = mode
        stopHeartbeatStreamLocked()
        syncHeartbeatLocked()
    }

    private fun clearHeartbeatLocked() {
        desiredHeartbeat = null
        stopHeartbeatStreamLocked()
    }

    private fun syncHeartbeatLocked() {
        if (!hostActive || closed || heartbeatStreamId != NO_STREAM) return
        val mode = desiredHeartbeat ?: return
        val sampleId = sampleIds[AudioAsset.HEARTBEAT] ?: return
        if (sampleId !in loadedSampleIds) return

        heartbeatStreamId = soundPool.play(
            sampleId,
            mode.volume,
            mode.volume,
            PLAYBACK_PRIORITY,
            LOOP_FOREVER,
            mode.rate,
        )
    }

    private fun stopHeartbeatStreamLocked() {
        if (heartbeatStreamId == NO_STREAM) return
        soundPool.stop(heartbeatStreamId)
        heartbeatStreamId = NO_STREAM
    }

    private fun playOrQueueOneShotLocked(cue: StandardSoundCue) {
        val spec = cue.oneShotSpec() ?: return
        val sampleId = sampleIds[spec.asset] ?: return
        if (sampleId in loadedSampleIds) {
            playOneShotLocked(sampleId, spec)
        } else if (pendingOneShots.size < MAX_PENDING_ONE_SHOTS) {
            pendingOneShots.addLast(cue)
        }
    }

    private fun flushPendingForLocked(loadedSampleId: Int) {
        if (!hostActive || pendingOneShots.isEmpty()) return
        val pendingCount = pendingOneShots.size
        repeat(pendingCount) {
            val cue = pendingOneShots.removeFirst()
            val spec = cue.oneShotSpec()
            val sampleId = spec?.let { sampleIds[it.asset] }
            if (spec != null && sampleId == loadedSampleId) {
                playOneShotLocked(loadedSampleId, spec)
            } else {
                pendingOneShots.addLast(cue)
            }
        }
    }

    private fun playOneShotLocked(sampleId: Int, spec: OneShotSpec) {
        soundPool.play(
            sampleId,
            spec.volume,
            spec.volume,
            PLAYBACK_PRIORITY,
            NO_LOOP,
            spec.rate,
        )
    }

    private fun StandardSoundCue.oneShotSpec(): OneShotSpec? = when (this) {
        StandardSoundCue.STONE_PLACED -> OneShotSpec(AudioAsset.STONE_PLACE, volume = 0.55f)
        StandardSoundCue.OPPONENT_APPEARED -> OneShotSpec(AudioAsset.OPPONENT_APPEAR, volume = 0.62f)
        StandardSoundCue.HUMAN_WIN -> OneShotSpec(AudioAsset.HUMAN_WIN, volume = 0.72f)
        StandardSoundCue.CLUTCH_WIN -> OneShotSpec(AudioAsset.HUMAN_WIN, volume = 0.82f, rate = 1.08f)
        StandardSoundCue.COMEBACK_WIN -> OneShotSpec(AudioAsset.LEVEL_CLEAR, volume = 0.86f, rate = 0.92f)
        StandardSoundCue.HUMAN_LOSS -> OneShotSpec(AudioAsset.HUMAN_LOSS, volume = 0.64f)
        StandardSoundCue.DRAW -> OneShotSpec(AudioAsset.DRAW, volume = 0.60f)
        StandardSoundCue.LEVEL_CLEAR -> OneShotSpec(AudioAsset.LEVEL_CLEAR, volume = 0.82f)
        StandardSoundCue.WILD_STAGE_AWAKENED -> OneShotSpec(
            AudioAsset.CAMPAIGN_CONQUERED,
            volume = 0.86f,
            rate = 0.78f,
        )
        StandardSoundCue.CAMPAIGN_CONQUERED -> OneShotSpec(AudioAsset.CAMPAIGN_CONQUERED, volume = 0.88f)
        StandardSoundCue.HEARTBEAT_TENSE_START,
        StandardSoundCue.HEARTBEAT_CRITICAL_START,
        StandardSoundCue.HEARTBEAT_STOP,
        -> null
    }

    private fun StandardSoundCue.isTerminalResultCue(): Boolean = when (this) {
        StandardSoundCue.HUMAN_WIN,
        StandardSoundCue.CLUTCH_WIN,
        StandardSoundCue.COMEBACK_WIN,
        StandardSoundCue.HUMAN_LOSS,
        StandardSoundCue.DRAW,
        StandardSoundCue.LEVEL_CLEAR,
        StandardSoundCue.WILD_STAGE_AWAKENED,
        StandardSoundCue.CAMPAIGN_CONQUERED,
        -> true
        else -> false
    }

    private companion object {
        const val MAX_STREAMS = 6
        const val MAX_PENDING_ONE_SHOTS = 12
        const val LOAD_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val PLAYBACK_PRIORITY = 1
        const val LOOP_FOREVER = -1
        const val NO_LOOP = 0
        const val NO_STREAM = 0
    }
}
