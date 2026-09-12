package com.example.othello

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Owns the short, Standard-only gacha fanfares and their lifecycle. */
internal class AndroidStandardGachaEffects(
    context: Context,
) : AutoCloseable {
    private enum class Fanfare(
        val resourceId: Int,
        val volume: Float,
    ) {
        COMMON(R.raw.standard_gacha_common, 0.48f),
        RARE(R.raw.standard_gacha_rare, 0.62f),
        SPECIAL(R.raw.standard_gacha_special, 0.72f),
    }

    private val lock = Any()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val sampleIds = mutableMapOf<Fanfare, Int>()
    private val loadedSampleIds = mutableSetOf<Int>()

    private var pendingFanfare: Fanfare? = null
    private var activeStreamId = NO_STREAM
    private var hostActive = true
    private var closed = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(lock) {
                if (closed || status != LOAD_SUCCESS) return@synchronized
                loadedSampleIds += sampleId
                val fanfare = sampleIds.entries.firstOrNull { it.value == sampleId }?.key
                if (hostActive && pendingFanfare == fanfare) {
                    pendingFanfare = null
                    playLocked(fanfare)
                }
            }
        }
        synchronized(lock) {
            Fanfare.entries.forEach { fanfare ->
                sampleIds[fanfare] = soundPool.load(
                    context.applicationContext,
                    fanfare.resourceId,
                    LOAD_PRIORITY,
                )
            }
        }
    }

    /** Emits one crisp break haptic and a rarity-specific fanfare in sync with the burst frame. */
    fun reveal(rarity: StandardContentRarity, view: View) {
        val shouldPlay = synchronized(lock) { hostActive && !closed }
        if (!shouldPlay) return

        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            },
        )

        synchronized(lock) {
            if (!hostActive || closed) return
            val fanfare = rarity.toFanfare()
            val sampleId = sampleIds[fanfare] ?: return
            if (sampleId in loadedSampleIds) {
                playLocked(fanfare)
            } else {
                pendingFanfare = fanfare
            }
        }
    }

    fun onHostStart() {
        synchronized(lock) {
            if (!closed) hostActive = true
        }
    }

    fun onHostStop() {
        synchronized(lock) {
            if (closed) return
            hostActive = false
            pendingFanfare = null
            stopActiveLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            hostActive = false
            pendingFanfare = null
            stopActiveLocked()
            soundPool.release()
            loadedSampleIds.clear()
            sampleIds.clear()
        }
    }

    private fun playLocked(fanfare: Fanfare?) {
        fanfare ?: return
        val sampleId = sampleIds[fanfare] ?: return
        stopActiveLocked()
        activeStreamId = soundPool.play(
            sampleId,
            fanfare.volume,
            fanfare.volume,
            PLAYBACK_PRIORITY,
            NO_LOOP,
            NORMAL_RATE,
        )
    }

    private fun stopActiveLocked() {
        if (activeStreamId == NO_STREAM) return
        soundPool.stop(activeStreamId)
        activeStreamId = NO_STREAM
    }

    private fun StandardContentRarity.toFanfare(): Fanfare = when (this) {
        StandardContentRarity.COMMON -> Fanfare.COMMON
        StandardContentRarity.RARE -> Fanfare.RARE
        StandardContentRarity.SPECIAL -> Fanfare.SPECIAL
    }

    private companion object {
        const val LOAD_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val PLAYBACK_PRIORITY = 1
        const val NO_LOOP = 0
        const val NO_STREAM = 0
        const val NORMAL_RATE = 1f
    }
}

/** Keeps gacha effects scoped to this screen and silent whenever its host is stopped. */
@Composable
internal fun rememberStandardGachaEffects(): AndroidStandardGachaEffects {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val effects = remember(context) { AndroidStandardGachaEffects(context.applicationContext) }

    DisposableEffect(lifecycleOwner, effects) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> effects.onHostStart()
                Lifecycle.Event.ON_STOP -> effects.onHostStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            effects.onHostStart()
        } else {
            effects.onHostStop()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            effects.close()
        }
    }

    return effects
}
