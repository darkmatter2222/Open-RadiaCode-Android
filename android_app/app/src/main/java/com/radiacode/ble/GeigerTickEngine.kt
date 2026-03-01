package com.radiacode.ble

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesized Geiger counter tick engine with continuous playback.
 *
 * Runs a tight write-loop on a dedicated audio thread that never stops ticking.
 * Each iteration writes one tick PCM burst followed by a computed silence gap.
 * AudioTrack.write() in MODE_STREAM blocks when the internal buffer is full,
 * naturally pacing the output without timers or schedulers.
 *
 * Rate transitions are smoothed: when onDataReceived() delivers a new CPS value,
 * the engine linearly interpolates from the current rendered rate to the new target
 * over INTERPOLATION_MS (200 ms). Silence is written in small chunks (~50 ms) so
 * the loop can re-evaluate the interpolated CPS mid-gap, giving sub-tick-level
 * responsiveness to rate changes.
 *
 * Background radiation is always non-zero, so a MIN_CPS floor (0.2 = one tick
 * every 5 s) ensures the engine is never truly silent while running.
 */
class GeigerTickEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GeigerTickEngine"
        private const val SAMPLE_RATE = 22050
        private const val MAX_CPS = 2000f           // Audio overload cap (supports high nSv/h values)
        private const val MIN_CPS = 0.2f            // Floor: ~1 tick per 5 s (background always exists)
        private const val INTERPOLATION_MS = 200f   // Smooth CPS ramp duration
        private const val SILENCE_CHUNK_SAMPLES = 1102 // ~50 ms at 22050 Hz

        /** Token object for identifying preview-end callbacks in mainHandler. */
        private val PREVIEW_TOKEN = Any()

        @Volatile
        private var instance: GeigerTickEngine? = null

        fun getInstance(context: Context): GeigerTickEngine {
            return instance ?: synchronized(this) {
                instance ?: GeigerTickEngine(context.applicationContext).also { instance = it }
            }
        }

        fun release() {
            instance?.stopInternal()
            instance = null
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var audioTrack: AudioTrack? = null

    // Pre-computed single-tick PCM buffer (regenerated when params change)
    private var tickSamples: ShortArray? = null
    private var tickParamsHash: Int = 0

    // ----- Continuous rate interpolation -----
    // Written by onDataReceived / playPreview on any thread; read by the audio loop.
    @Volatile private var targetCps: Float = MIN_CPS
    @Volatile private var renderCps: Float = MIN_CPS
    @Volatile private var previousCps: Float = MIN_CPS
    @Volatile private var lastCpsChangeTimeMs: Long = 0L

    // ----- Preview control -----
    @Volatile private var isPreviewActive = false
    @Volatile private var previewEndTimeMs: Long = 0L
    private var wasRunningBeforePreview = false

    // Main-thread handler for scheduling preview-end stop
    private val mainHandler = Handler(Looper.getMainLooper())

    // Re-usable zero buffer for writing silence in chunks
    private val silenceChunk = ShortArray(SILENCE_CHUNK_SAMPLES)

    /**
     * Data class holding all configurable tick parameters.
     */
    data class TickParams(
        val toneFrequencyHz: Float = 800f,
        val attackTimeMs: Float = 0.5f,
        val decayRate: Float = 8f,
        val clickDurationMs: Float = 5f,
        val noiseAmount: Float = 0.15f,
        val toneAmount: Float = 0.6f,
        val harmonicAmount: Float = 0.0f,
        val harmonicFreqRatio: Float = 2.0f,
        val resonanceFreqHz: Float = 2000f,
        val resonanceAmount: Float = 0.0f,
        val lowPassCutoff: Float = 1.0f,
        val volume: Float = 0.3f
    )

    /**
     * Geiger counter sound presets modeled after real-world detector types.
     * Each preset configures all synthesis parameters to emulate a specific
     * detector family's characteristic click/chirp sound.
     */
    enum class GeigerPreset(
        val displayName: String,
        val description: String,
        val params: TickParams
    ) {
        CUSTOM("Custom", "User-defined parameters", TickParams()),

        CLASSIC_GM("Classic GM Tube", "Sharp metallic click -- SBM-20 / LND-712 style",
            TickParams(toneFrequencyHz = 1000f, attackTimeMs = 0.2f, decayRate = 15f,
                clickDurationMs = 3f, noiseAmount = 0.3f, toneAmount = 0.5f,
                harmonicAmount = 0.1f, harmonicFreqRatio = 3.0f,
                resonanceFreqHz = 4000f, resonanceAmount = 0.15f,
                lowPassCutoff = 0.8f, volume = 0.35f)),

        PANCAKE_PROBE("Pancake Probe", "Thin, high-pitched tick -- Ludlum 44-9 style",
            TickParams(toneFrequencyHz = 2400f, attackTimeMs = 0.1f, decayRate = 20f,
                clickDurationMs = 2f, noiseAmount = 0.2f, toneAmount = 0.7f,
                harmonicAmount = 0.05f, harmonicFreqRatio = 2.5f,
                resonanceFreqHz = 5000f, resonanceAmount = 0.1f,
                lowPassCutoff = 0.9f, volume = 0.3f)),

        CDV700("CDV-700", "Civil defense chirp -- Cold War era detector",
            TickParams(toneFrequencyHz = 1200f, attackTimeMs = 0.3f, decayRate = 10f,
                clickDurationMs = 8f, noiseAmount = 0.25f, toneAmount = 0.55f,
                harmonicAmount = 0.15f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 3000f, resonanceAmount = 0.2f,
                lowPassCutoff = 0.7f, volume = 0.3f)),

        LUDLUM_2241("Ludlum 2241", "Clean digital beep -- modern survey meter",
            TickParams(toneFrequencyHz = 1500f, attackTimeMs = 0.1f, decayRate = 12f,
                clickDurationMs = 4f, noiseAmount = 0.05f, toneAmount = 0.8f,
                harmonicAmount = 0.0f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 3000f, resonanceAmount = 0.0f,
                lowPassCutoff = 1.0f, volume = 0.3f)),

        INSPECTOR_ALERT("Inspector Alert", "Bright sharp tick -- SE International",
            TickParams(toneFrequencyHz = 3200f, attackTimeMs = 0.1f, decayRate = 25f,
                clickDurationMs = 2f, noiseAmount = 0.15f, toneAmount = 0.65f,
                harmonicAmount = 0.1f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 6000f, resonanceAmount = 0.1f,
                lowPassCutoff = 0.95f, volume = 0.3f)),

        RUSSIAN_SBT10("Russian SBT-10A", "Deep resonant click -- Soviet-era tube",
            TickParams(toneFrequencyHz = 400f, attackTimeMs = 0.4f, decayRate = 6f,
                clickDurationMs = 12f, noiseAmount = 0.35f, toneAmount = 0.45f,
                harmonicAmount = 0.2f, harmonicFreqRatio = 2.5f,
                resonanceFreqHz = 1200f, resonanceAmount = 0.25f,
                lowPassCutoff = 0.5f, volume = 0.35f)),

        EBERLINE("Eberline E-600", "Professional clean pulse -- health physics",
            TickParams(toneFrequencyHz = 1100f, attackTimeMs = 0.2f, decayRate = 14f,
                clickDurationMs = 4f, noiseAmount = 0.08f, toneAmount = 0.75f,
                harmonicAmount = 0.05f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 2200f, resonanceAmount = 0.05f,
                lowPassCutoff = 0.85f, volume = 0.3f)),

        VINTAGE_TUBE("Vintage Tube", "Warm, noisy, lo-fi -- early 1960s detector",
            TickParams(toneFrequencyHz = 500f, attackTimeMs = 0.8f, decayRate = 4f,
                clickDurationMs = 18f, noiseAmount = 0.5f, toneAmount = 0.3f,
                harmonicAmount = 0.25f, harmonicFreqRatio = 3.0f,
                resonanceFreqHz = 800f, resonanceAmount = 0.3f,
                lowPassCutoff = 0.35f, volume = 0.35f)),

        SCINTILLATOR("NaI Scintillator", "Soft thud -- sodium iodide crystal detector",
            TickParams(toneFrequencyHz = 600f, attackTimeMs = 0.6f, decayRate = 5f,
                clickDurationMs = 15f, noiseAmount = 0.12f, toneAmount = 0.5f,
                harmonicAmount = 0.1f, harmonicFreqRatio = 1.5f,
                resonanceFreqHz = 1500f, resonanceAmount = 0.15f,
                lowPassCutoff = 0.4f, volume = 0.35f)),

        HIGH_PITCH("High-Pitch Chirp", "Sharp ultrasonic-style chirp for high sensitivity",
            TickParams(toneFrequencyHz = 5500f, attackTimeMs = 0.1f, decayRate = 30f,
                clickDurationMs = 1.5f, noiseAmount = 0.1f, toneAmount = 0.7f,
                harmonicAmount = 0.15f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 8000f, resonanceAmount = 0.2f,
                lowPassCutoff = 1.0f, volume = 0.25f)),

        RADIACODE("RadiaCode Default", "Balanced synthesized tick for RadiaCode devices",
            TickParams(toneFrequencyHz = 800f, attackTimeMs = 0.5f, decayRate = 8f,
                clickDurationMs = 5f, noiseAmount = 0.15f, toneAmount = 0.6f,
                harmonicAmount = 0.0f, harmonicFreqRatio = 2.0f,
                resonanceFreqHz = 2000f, resonanceAmount = 0.0f,
                lowPassCutoff = 1.0f, volume = 0.3f));

        companion object {
            fun fromName(name: String): GeigerPreset = entries.firstOrNull { it.name == name } ?: CUSTOM
        }
    }

    fun loadParams(): TickParams {
        return TickParams(
            toneFrequencyHz = Prefs.getGeigerToneFrequency(context),
            attackTimeMs = Prefs.getGeigerAttackTime(context),
            decayRate = Prefs.getGeigerDecayRate(context),
            clickDurationMs = Prefs.getGeigerClickDuration(context),
            noiseAmount = Prefs.getGeigerNoiseAmount(context),
            toneAmount = Prefs.getGeigerToneAmount(context),
            harmonicAmount = Prefs.getGeigerHarmonicAmount(context),
            harmonicFreqRatio = Prefs.getGeigerHarmonicFreqRatio(context),
            resonanceFreqHz = Prefs.getGeigerResonanceFreq(context),
            resonanceAmount = Prefs.getGeigerResonanceAmount(context),
            lowPassCutoff = Prefs.getGeigerLowPassCutoff(context),
            volume = Prefs.getGeigerVolume(context)
        )
    }

    // -----------------------------------------------------------------------
    //  Tick synthesis (unchanged)
    // -----------------------------------------------------------------------

    private fun synthesizeTick(params: TickParams): ShortArray {
        val numSamples = (params.clickDurationMs / 1000f * SAMPLE_RATE).toInt().coerceAtLeast(4)
        val samples = FloatArray(numSamples)
        val rng = java.util.Random(0)

        val attackSamples = (params.attackTimeMs / 1000f * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val clickLen = numSamples.toFloat()

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            val tn = i.toFloat() / clickLen

            val attackNorm = if (i < attackSamples) {
                0.5f * (1f - cos(PI.toFloat() * i / attackSamples))
            } else 1f

            val decayStart = attackSamples.toFloat() / clickLen
            val env = if (tn >= decayStart) {
                attackNorm * exp(-(tn - decayStart) * params.decayRate)
            } else {
                attackNorm * 0.5f
            }

            val tone = params.toneAmount *
                    sin(2f * PI.toFloat() * params.toneFrequencyHz * t)
            val harmonic = params.harmonicAmount *
                    sin(2f * PI.toFloat() * params.toneFrequencyHz * params.harmonicFreqRatio * t)
            val resonance = params.resonanceAmount *
                    sin(2f * PI.toFloat() * params.resonanceFreqHz * t) *
                    exp(-tn * 20f)
            val noise = params.noiseAmount * (rng.nextFloat() * 2f - 1f)

            var sample = (tone + harmonic + resonance + noise) * env
            if (params.lowPassCutoff < 0.99f && i > 0) {
                val alpha = params.lowPassCutoff.coerceIn(0.01f, 1f)
                sample = alpha * sample + (1f - alpha) * samples[i - 1]
            }
            samples[i] = sample.coerceIn(-1f, 1f)
        }

        val pcm = ShortArray(numSamples)
        for (i in samples.indices) {
            pcm[i] = (samples[i] * params.volume * 32767f).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        return pcm
    }

    private fun ensureTickBuffer(params: TickParams) {
        val hash = params.hashCode()
        if (hash != tickParamsHash || tickSamples == null) {
            tickSamples = synthesizeTick(params)
            tickParamsHash = hash
        }
    }

    // -----------------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.d(TAG, "Starting continuous Geiger tick engine")

        audioThread = HandlerThread("GeigerTick").apply { start() }
        audioHandler = Handler(audioThread!!.looper)

        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE) // >= 0.5 s (must be even for 16-bit frames)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        // Launch the continuous tick loop on the audio thread
        audioHandler?.post { continuousTickLoop() }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!isRunning.getAndSet(false)) return
        Log.d(TAG, "Stopping Geiger tick engine")

        isPreviewActive = false
        mainHandler.removeCallbacksAndMessages(PREVIEW_TOKEN)

        // Pause the AudioTrack to unblock any pending write() in the loop.
        // The loop will see isRunning=false and exit on its next iteration.
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.flush() } catch (_: Exception) {}

        // Wait for the audio thread to actually finish before releasing resources.
        // quitSafely() drains the message queue, but our loop is a blocking while-loop
        // so we must use quit() after pausing the track (which unblocks write()).
        val thread = audioThread
        audioThread = null
        audioHandler = null
        thread?.quit()
        try { thread?.join(500) } catch (_: InterruptedException) {}

        // Now safe to stop + release since the loop is no longer running
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    // -----------------------------------------------------------------------
    //  Continuous tick loop (runs on audioThread)
    // -----------------------------------------------------------------------

    /**
     * Core audio loop. Runs on the dedicated HandlerThread.
     *
     * Each iteration:
     *   1. Compute the interpolated CPS (smooth ramp toward target).
     *   2. Write one tick PCM burst.
     *   3. Write silence in small chunks until the inter-tick interval is filled,
     *      re-evaluating CPS between chunks so rate-ups shorten the gap immediately.
     *
     * AudioTrack.write() blocks when the internal buffer is full, which naturally
     * paces output without timers.
     */
    private fun continuousTickLoop() {
        Log.d(TAG, "Continuous tick loop started")

        while (isRunning.get()) {
            // Re-synth if user changed params (invalidateTickBuffer sets tickSamples=null)
            if (tickSamples == null) {
                ensureTickBuffer(loadParams())
            }
            val tick = tickSamples ?: continue

            // ---- Interpolate CPS ----
            val cpsNow = interpolateCps()

            // ---- Total samples for one tick interval ----
            val intervalSamples = (SAMPLE_RATE / cpsNow).toInt().coerceAtLeast(4)

            // At high rates the tick may be longer than the interval.
            // Truncate the tick so it fits, leaving at least 1 sample of silence
            // for audible separation between clicks.
            val tickLen = tick.size.coerceAtMost(intervalSamples - 1).coerceAtLeast(2)
            val silenceNeeded = (intervalSamples - tickLen).coerceAtLeast(0)

            // ---- Write the tick (possibly truncated) ----
            try {
                val track = audioTrack ?: break
                val written = track.write(tick, 0, tickLen)
                if (written < 0) break // error or track released
            } catch (_: Exception) {
                break
            }

            // ---- Write silence in chunks, re-evaluating CPS between chunks ----
            var silenceWritten = 0
            while (silenceWritten < silenceNeeded && isRunning.get()) {
                // Re-interpolate for responsive rate changes mid-gap
                val cpsInner = interpolateCps()
                val newInterval = (SAMPLE_RATE / cpsInner).toInt().coerceAtLeast(4)
                val newTickLen = tick.size.coerceAtMost(newInterval - 1).coerceAtLeast(2)
                val newSilence = (newInterval - newTickLen).coerceAtLeast(0)

                // If rate went up enough, the gap is already filled
                if (silenceWritten >= newSilence) break

                val remaining = newSilence - silenceWritten
                val chunkSize = min(remaining, SILENCE_CHUNK_SAMPLES)

                try {
                    val track = audioTrack ?: break
                    val written = track.write(silenceChunk, 0, chunkSize)
                    if (written < 0) break
                    silenceWritten += written
                } catch (_: Exception) {
                    break
                }
            }
        }

        Log.d(TAG, "Continuous tick loop ended")
    }

    /**
     * Compute the current interpolated CPS from previousCps toward targetCps.
     * Linear ramp over INTERPOLATION_MS, clamped to [MIN_CPS, MAX_CPS].
     */
    private fun interpolateCps(): Float {
        val elapsed = (System.currentTimeMillis() - lastCpsChangeTimeMs).toFloat()
        val t = (elapsed / INTERPOLATION_MS).coerceIn(0f, 1f)
        val cps = previousCps + (targetCps - previousCps) * t
        renderCps = cps.coerceIn(MIN_CPS, MAX_CPS)
        return renderCps
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Called each time a new data reading arrives from the BLE device.
     * Sets the target CPS; the continuous loop smoothly interpolates toward it.
     */
    fun onDataReceived(cps: Float) {
        if (!isRunning.get()) return

        // Clamp to valid range (CPS is never truly zero)
        val effectiveCps = cps.coerceIn(MIN_CPS, MAX_CPS)

        // Snapshot the currently-rendered rate as the ramp starting point
        previousCps = renderCps
        targetCps = effectiveCps
        lastCpsChangeTimeMs = System.currentTimeMillis()
    }

    /**
     * Play a preview at the given CPS for a specified duration.
     * Starts the engine if not already running, and auto-stops it when the
     * preview finishes (only if it was not already running for live data).
     */
    fun playPreview(cps: Float, durationSec: Float = 3f) {
        val effectiveCps = cps.coerceIn(MIN_CPS, MAX_CPS)
        wasRunningBeforePreview = isRunning.get()
        isPreviewActive = true

        // Jump immediately to the preview rate (no ramp)
        previousCps = effectiveCps
        targetCps = effectiveCps
        renderCps = effectiveCps
        lastCpsChangeTimeMs = System.currentTimeMillis()

        if (!wasRunningBeforePreview) {
            start()
        }

        // Schedule auto-stop after the preview duration
        mainHandler.removeCallbacksAndMessages(PREVIEW_TOKEN)
        mainHandler.postAtTime({
            isPreviewActive = false
            if (!wasRunningBeforePreview) {
                stop()
            }
        }, PREVIEW_TOKEN, android.os.SystemClock.uptimeMillis() + (durationSec * 1000).toLong())
    }

    /**
     * Invalidate cached tick buffer, forcing re-synthesis on the next loop iteration.
     * Call this when the user changes any sound parameter.
     */
    fun invalidateTickBuffer() {
        tickParamsHash = 0
        tickSamples = null
    }

    fun isActive(): Boolean = isRunning.get()
}
