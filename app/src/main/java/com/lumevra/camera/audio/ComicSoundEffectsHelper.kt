package com.lumevra.camera.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural Web-Audio-style SFX synthesizer powered by Android AudioTrack (44.1 kHz PCM-16).
 * Matches all 5 studio comic sound presets:
 *   comic_punch | heavy_slam | electric_zap | boing_spring | laser_burst
 */
class ComicSoundEffectsHelper {

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun playComicBurst(
        powPreset: String = "comic_punch",
        bamPreset: String = "heavy_slam",
        zapPreset: String = "electric_zap",
        volume: Float = 0.65f,
        muted: Boolean = false
    ) {
        if (muted || volume <= 0.01f) return
        playSoundEffect(powPreset, volume)
        mainHandler.postDelayed({ playSoundEffect(bamPreset, volume * 0.92f) }, 130L)
        mainHandler.postDelayed({ playSoundEffect(zapPreset, volume * 0.85f) }, 260L)
    }

    fun playSoundEffect(preset: String, volume: Float = 0.65f) {
        if (preset == "none" || volume <= 0.01f) return
        val vol = volume.coerceIn(0.05f, 1.0f)
        audioExecutor.execute {
            try {
                val sampleRate = 44100
                val durationSec = when (preset) {
                    "heavy_slam" -> 0.30f
                    "boing_spring" -> 0.34f
                    "electric_zap" -> 0.22f
                    "laser_burst" -> 0.24f
                    else -> 0.20f
                }
                val numSamples = (sampleRate * durationSec).toInt()
                val pcm = ShortArray(numSamples)
                var phase = 0.0
                val rng = Random(System.nanoTime())

                for (i in 0 until numSamples) {
                    val t = i.toFloat() / sampleRate
                    val normT = i.toFloat() / numSamples
                    val env = exp((-5.5f * normT).toDouble()).toFloat()

                    val sampleFloat = when (preset) {
                        "comic_punch" -> {
                            val freq = 260.0 * (1.0 - 0.72 * normT) + 55.0
                            phase += 2.0 * PI * freq / sampleRate
                            val punch = sin(phase) * 0.78
                            val pop = (if (normT < 0.25f) (rng.nextFloat() * 2f - 1f) * 0.45f else 0f)
                            (punch + pop) * env
                        }
                        "heavy_slam" -> {
                            val freq = 140.0 * (1.0 - 0.65 * normT) + 34.0
                            phase += 2.0 * PI * freq / sampleRate
                            val sub = sin(phase).coerceIn(-0.85, 0.85) * 0.85
                            val crunch = (rng.nextFloat() * 2f - 1f) * (1f - normT).coerceIn(0f, 1f) * 0.55f
                            (sub + crunch) * env
                        }
                        "electric_zap" -> {
                            val freq = 960.0 - 640.0 * normT + sin(t * 72.0) * 140.0
                            phase += 2.0 * PI * freq / sampleRate
                            val saw = 2.0 * ((phase / (2.0 * PI)) - kotlin.math.floor(phase / (2.0 * PI) + 0.5))
                            (saw * 0.65 + (rng.nextFloat() * 2f - 1f) * 0.25f) * env
                        }
                        "boing_spring" -> {
                            val vib = sin(t * 48.0) * 120.0 * (1.0 - normT)
                            val freq = 310.0 + 210.0 * sin(normT * PI) + vib
                            phase += 2.0 * PI * freq / sampleRate
                            sin(phase) * 0.82 * exp((-3.2f * normT).toDouble())
                        }
                        "laser_burst" -> {
                            val freq = 1520.0 * exp((-3.8f * normT).toDouble()) + 160.0
                            phase += 2.0 * PI * freq / sampleRate
                            val osc = if (sin(phase) >= 0.0) 0.55 else -0.55
                            osc * env
                        }
                        else -> {
                            phase += 2.0 * PI * 440.0 / sampleRate
                            sin(phase) * 0.5 * env
                        }
                    }

                    val scaled = (sampleFloat * vol * Short.MAX_VALUE * 0.85).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    pcm[i] = scaled.toShort()
                }

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((durationSec * 1000L).toLong() + 45L)
                track.stop()
                track.release()
            } catch (_: Throwable) {
                // Ignore transient audio focus or emulator mixer errors
            }
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        audioExecutor.shutdownNow()
    }
}
