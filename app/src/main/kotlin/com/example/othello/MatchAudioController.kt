package com.example.othello

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.othello.match.TimeWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Owns all match sound playback and its AudioTrack lifecycle. */
class MatchAudioController(context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pinkNoiseTrack: AudioTrack? = null
    private var warningTrack: AudioTrack? = null
    private var warningJob: Job? = null
    private var released = false

    @Synchronized
    fun setPinkNoisePlaying(playing: Boolean, volume: Float) {
        if (released) return
        if (!playing) {
            releasePinkNoiseTrack()
            return
        }
        val track = pinkNoiseTrack ?: runCatching { createPinkNoiseTrack() }
            .getOrNull()
            ?.also { pinkNoiseTrack = it }
            ?: return
        runCatching {
            track.setVolume(volume.coerceIn(0f, 1f))
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        }.onFailure { releasePinkNoiseTrack() }
    }

    fun playTimeWarnings(warnings: List<TimeWarning>) {
        if (warnings.isEmpty()) return
        synchronized(this) {
            if (released) return
            warningJob?.cancel()
            releaseWarningTrack()
            val samples = WarningToneGenerator.generate(warnings)
            val track = runCatching {
                createStaticTrack(samples, AudioAttributes.CONTENT_TYPE_SONIFICATION)
            }.getOrNull() ?: return
            warningTrack = track
            warningJob = scope.launch {
                runCatching {
                    track.play()
                    delay(WarningToneGenerator.durationMillis(warnings) + 100L)
                }
                synchronized(this@MatchAudioController) {
                    if (warningTrack === track) {
                        releaseWarningTrack()
                        warningJob = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        if (released) return
        releasePinkNoiseTrack()
        warningJob?.cancel()
        warningJob = null
        releaseWarningTrack()
    }

    @Synchronized
    override fun close() {
        if (released) return
        released = true
        warningJob?.cancel()
        warningJob = null
        releasePinkNoiseTrack()
        releaseWarningTrack()
        scope.cancel()
    }

    private fun createPinkNoiseTrack(): AudioTrack {
        val samples = PinkNoiseGenerator.generate(SAMPLE_RATE, PINK_NOISE_SECONDS)
        return requireNotNull(createStaticTrack(samples, AudioAttributes.CONTENT_TYPE_MUSIC)) {
            "Unable to create pink noise AudioTrack"
        }.also { track ->
            track.setLoopPoints(0, samples.size, -1)
        }
    }

    private fun createStaticTrack(samples: ShortArray, contentType: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributesFor(contentType))
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        val written = track.write(samples, 0, samples.size)
        if (written != samples.size) {
            track.release()
            return null
        }
        return track
    }

    private fun audioAttributesFor(contentType: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(contentType)
        .build()

    private fun releasePinkNoiseTrack() {
        pinkNoiseTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
        pinkNoiseTrack = null
    }

    private fun releaseWarningTrack() {
        warningTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        warningTrack = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val PINK_NOISE_SECONDS = 2
    }
}

private object WarningToneGenerator {
    private const val SAMPLE_RATE = 44_100
    private const val AMPLITUDE = 0.62

    fun generate(warnings: List<TimeWarning>): ShortArray {
        val samples = ArrayList<Short>()
        warnings.forEachIndexed { index, warning ->
            if (index > 0) appendSilence(samples, 100)
            when (warning) {
                TimeWarning.ONE_MINUTE -> {
                    appendTone(samples, 880.0, 105)
                    appendSilence(samples, 115)
                    appendTone(samples, 880.0, 105)
                    appendSilence(samples, 115)
                    appendTone(samples, 880.0, 105)
                }
                TimeWarning.THIRTY_SECONDS -> {
                    appendTone(samples, 1_180.0, 70)
                    appendSilence(samples, 65)
                    appendTone(samples, 1_180.0, 70)
                    appendSilence(samples, 125)
                    appendTone(samples, 1_180.0, 70)
                    appendSilence(samples, 65)
                    appendTone(samples, 1_180.0, 70)
                    appendSilence(samples, 125)
                    appendTone(samples, 1_180.0, 70)
                    appendSilence(samples, 65)
                    appendTone(samples, 1_180.0, 70)
                }
            }
        }
        return samples.toShortArray()
    }

    fun durationMillis(warnings: List<TimeWarning>): Long = warnings.sumOf { warning ->
        when (warning) {
            TimeWarning.ONE_MINUTE -> 3 * 105L + 2 * 115L
            TimeWarning.THIRTY_SECONDS -> 6 * 70L + 5 * 65L + 2 * 125L
        }
    } + (warnings.size - 1).coerceAtLeast(0) * 100L

    private fun appendTone(samples: MutableList<Short>, frequency: Double, durationMillis: Int) {
        val count = SAMPLE_RATE * durationMillis / 1_000
        val fadeSamples = SAMPLE_RATE / 200
        repeat(count) { index ->
            val fade = minOf(1.0, index.toDouble() / fadeSamples, (count - index).toDouble() / fadeSamples)
            val value = sin(2.0 * PI * frequency * index / SAMPLE_RATE) * AMPLITUDE * fade
            samples += (value * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun appendSilence(samples: MutableList<Short>, durationMillis: Int) {
        repeat(SAMPLE_RATE * durationMillis / 1_000) { samples += 0 }
    }
}

private object PinkNoiseGenerator {
    fun generate(sampleRate: Int, seconds: Int): ShortArray {
        val samples = ShortArray(sampleRate * seconds)
        val random = Random(0xC4A9_2026)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        samples.indices.forEach { index ->
            val white = random.nextDouble(-1.0, 1.0)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
            b6 = white * 0.115926
            samples[index] = (pink.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }
}
