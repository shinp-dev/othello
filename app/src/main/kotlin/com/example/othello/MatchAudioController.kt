package com.example.othello

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.othello.match.TimeWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class AudioPreview(val durationMillis: Long) {
    PINK_NOISE(4_000L),
    ONE_MINUTE_WARNING(WarningToneGenerator.durationMillis(listOf(TimeWarning.ONE_MINUTE))),
    THIRTY_SECONDS_WARNING(WarningToneGenerator.durationMillis(listOf(TimeWarning.THIRTY_SECONDS))),
}

/** Owns all match sound playback and its AudioTrack lifecycle. */
class MatchAudioController(context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pinkNoiseTrack: AudioTrack? = null
    private var pinkNoiseJob: Job? = null
    private var warningTrack: AudioTrack? = null
    private var warningJob: Job? = null
    private var previewTrack: AudioTrack? = null
    private var previewJob: Job? = null
    private var released = false

    @Synchronized
    fun setPinkNoisePlaying(playing: Boolean, volume: Float) {
        if (released) return
        if (!playing) {
            releasePinkNoiseTrack()
            return
        }
        if (pinkNoiseTrack != null && pinkNoiseJob?.isActive == true) {
            runCatching { pinkNoiseTrack?.setVolume(volume.coerceIn(0f, 1f)) }
                .onFailure { error -> Log.e(TAG, "Unable to update pink noise volume", error) }
            return
        }
        val track = pinkNoiseTrack ?: runCatching { createPinkNoiseTrack() }
            .getOrNull()
            ?.also { pinkNoiseTrack = it }
            ?: return
        val started = runCatching {
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
        }.onFailure { error ->
            Log.e(TAG, "Unable to start pink noise", error)
            releasePinkNoiseTrack()
        }.isSuccess
        if (started) {
            val samples = PinkNoiseGenerator.generate(SAMPLE_RATE, PINK_NOISE_SECONDS)
            pinkNoiseJob = scope.launch {
                try {
                    while (isActive && pinkNoiseTrack === track) {
                        if (!writeSamples(track, samples)) break
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to stream pink noise", error)
                } finally {
                    synchronized(this@MatchAudioController) {
                        if (pinkNoiseTrack === track) {
                            pinkNoiseJob = null
                            releasePinkNoiseTrack()
                        }
                    }
                }
            }
        }
    }

    fun playTimeWarnings(warnings: List<TimeWarning>) {
        if (warnings.isEmpty()) return
        synchronized(this) {
            if (released) return
            warningJob?.cancel()
            releaseWarningTrack()
            val samples = WarningToneGenerator.generate(warnings)
            val track = createStreamingTrack(AudioAttributes.CONTENT_TYPE_SONIFICATION) ?: return
            warningTrack = track
            val started = runCatching { track.play() }
                .onFailure { error -> Log.e(TAG, "Unable to play warning tone", error) }
                .isSuccess
            if (!started) {
                releaseWarningTrack()
                return
            }
            warningJob = scope.launch {
                try {
                    writeSamples(track, samples)
                    delay(WarningToneGenerator.durationMillis(warnings) + 100L)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to stream warning tone", error)
                } finally {
                    synchronized(this@MatchAudioController) {
                        if (warningTrack === track) {
                            warningJob = null
                            releaseWarningTrack()
                        }
                    }
                }
            }
        }
    }

    /** Starts a short settings preview, replacing any previous preview. */
    @Synchronized
    fun startPreview(preview: AudioPreview, volume: Float): Boolean {
        if (released) return false
        releasePreviewTrack()

        val samples = when (preview) {
            AudioPreview.PINK_NOISE -> PinkNoiseGenerator.generate(SAMPLE_RATE, PINK_NOISE_SECONDS)
            AudioPreview.ONE_MINUTE_WARNING -> WarningToneGenerator.generate(listOf(TimeWarning.ONE_MINUTE))
            AudioPreview.THIRTY_SECONDS_WARNING -> WarningToneGenerator.generate(listOf(TimeWarning.THIRTY_SECONDS))
        }
        val contentType = if (preview == AudioPreview.PINK_NOISE) {
            AudioAttributes.CONTENT_TYPE_MUSIC
        } else {
            AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        val track = createStreamingTrack(contentType) ?: return false
        if (preview == AudioPreview.PINK_NOISE) {
            runCatching { track.setVolume(volume.coerceIn(0f, 1f)) }
                .onFailure { error ->
                    Log.e(TAG, "Unable to set preview volume", error)
                }
        }
        val started = runCatching { track.play() }
            .onFailure { error -> Log.e(TAG, "Unable to start audio preview", error) }
            .isSuccess
        if (!started) {
            track.release()
            return false
        }
        previewTrack = track
        previewJob = scope.launch {
            try {
                if (preview == AudioPreview.PINK_NOISE) {
                    while (isActive && previewTrack === track) {
                        if (!writeSamples(track, samples)) break
                    }
                } else {
                    writeSamples(track, samples)
                    delay(preview.durationMillis + 100L)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to stream audio preview", error)
            } finally {
                synchronized(this@MatchAudioController) {
                    if (previewTrack === track) {
                        previewJob = null
                        releasePreviewTrack()
                    }
                }
            }
        }
        return true
    }

    @Synchronized
    fun stopPreview() {
        if (released) return
        releasePreviewTrack()
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
        releasePreviewTrack()
        scope.cancel()
    }

    private fun createPinkNoiseTrack(): AudioTrack {
        return requireNotNull(createStreamingTrack(AudioAttributes.CONTENT_TYPE_MUSIC)) {
            "Unable to create pink noise AudioTrack"
        }
    }

    private fun createStreamingTrack(contentType: Int): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioTrack returned invalid min buffer size: $minBufferSize")
            return null
        }
        val track = runCatching {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributesFor(contentType))
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)
                .build()
        }.onFailure { error -> Log.e(TAG, "Unable to create AudioTrack", error) }
            .getOrNull() ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack is not initialized: state=${track.state}")
            track.release()
            return null
        }
        return track
    }

    private suspend fun writeSamples(track: AudioTrack, samples: ShortArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            val written = synchronized(this) {
                if (released) return false
                track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (written <= 0) {
                if (written == 0) {
                    delay(5L)
                    continue
                }
                Log.e(TAG, "AudioTrack write failed: offset=$offset, result=$written")
                return false
            }
            offset += written
        }
        return true
    }

    private fun audioAttributesFor(contentType: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(contentType)
        .build()

    private fun releasePinkNoiseTrack() {
        pinkNoiseJob?.cancel()
        pinkNoiseJob = null
        pinkNoiseTrack?.let { track ->
            runCatching { track.pause() }
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

    private fun releasePreviewTrack() {
        previewJob?.cancel()
        previewJob = null
        previewTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        previewTrack = null
    }

    private companion object {
        const val TAG = "MatchAudioController"
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
