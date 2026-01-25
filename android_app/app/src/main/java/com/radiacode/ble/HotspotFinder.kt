package com.radiacode.ble

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import kotlin.math.max
import kotlin.math.min

/**
 * Hotspot Finder Mode
 * Provides audio and haptic feedback that increases with radiation level.
 * Like a Geiger counter or metal detector - beeps faster as you approach a source.
 */
class HotspotFinder(private val context: Context) {
    
    companion object {
        const val MIN_INTERVAL_MS = 50L      // Fastest beep rate (high radiation)
        const val MAX_INTERVAL_MS = 2000L    // Slowest beep rate (low radiation)
        
        // Threshold levels (μSv/h)
        const val THRESHOLD_LOW = 0.1f       // Below this, very slow beeps
        const val THRESHOLD_MEDIUM = 0.5f    // Medium beep rate
        const val THRESHOLD_HIGH = 2.0f      // Fast beeps
        const val THRESHOLD_DANGER = 10.0f   // Very fast beeps + strong vibration
    }
    
    /**
     * Finder mode settings
     */
    data class Settings(
        val audioEnabled: Boolean = true,
        val hapticEnabled: Boolean = true,
        val baselineUSvH: Float = 0.1f,      // Baseline level to compare against
        val sensitivity: Float = 1.0f,        // 0.5 = less sensitive, 2.0 = more sensitive
        val volume: Float = 0.7f              // Audio volume 0-1
    )
    
    /**
     * Callback for UI updates
     */
    interface Listener {
        fun onIntensityChanged(intensity: Float)  // 0-1 intensity level
        fun onBaselineUpdated(baselineUSvH: Float)
        fun onPeakDetected(peakUSvH: Float, isNewPeak: Boolean)
    }
    
    private var settings = Settings()
    private var listener: Listener? = null
    private var isActive = false
    
    // Audio
    private var soundPool: SoundPool? = null
    private var beepSoundId: Int = 0
    private var isAudioLoaded = false
    
    // Haptic
    private var vibrator: Vibrator? = null
    
    // Timing
    private val handler = Handler(Looper.getMainLooper())
    private var beepRunnable: Runnable? = null
    private var lastBeepTime = 0L
    private var currentIntervalMs = MAX_INTERVAL_MS
    
    // Tracking
    private var currentReading = 0f
    private var peakReading = 0f
    private var peakTimestamp = 0L
    private var recentReadings = mutableListOf<Float>()
    private val maxRecentReadings = 10
    
    // Adaptive baseline
    private var adaptiveBaseline = 0.1f
    private var baselineReadings = mutableListOf<Float>()
    private val baselineWindowSize = 30
    
    init {
        initAudio()
        initHaptic()
    }
    
    private fun initAudio() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, _, status ->
                    isAudioLoaded = status == 0
                }
                // SoundPool will be used with ToneGenerator instead
                isAudioLoaded = true
            }
    }
    
    private fun initHaptic() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    /**
     * Start hotspot finder mode
     */
    fun start(initialSettings: Settings = Settings()) {
        settings = initialSettings
        isActive = true
        peakReading = 0f
        recentReadings.clear()
        baselineReadings.clear()
        
        // Capture initial baseline
        adaptiveBaseline = settings.baselineUSvH
        
        startBeepLoop()
    }
    
    /**
     * Stop hotspot finder mode
     */
    fun stop() {
        isActive = false
        beepRunnable?.let { handler.removeCallbacks(it) }
        beepRunnable = null
        soundPool?.autoPause()
    }
    
    /**
     * Update with a new reading
     */
    fun updateReading(uSvPerHour: Float) {
        if (!isActive) return
        
        currentReading = uSvPerHour
        
        // Track recent readings
        recentReadings.add(uSvPerHour)
        if (recentReadings.size > maxRecentReadings) {
            recentReadings.removeAt(0)
        }
        
        // Update adaptive baseline (using lowest readings)
        baselineReadings.add(uSvPerHour)
        if (baselineReadings.size > baselineWindowSize) {
            baselineReadings.removeAt(0)
        }
        if (baselineReadings.size >= 5) {
            val sorted = baselineReadings.sorted()
            val lowQuartile = sorted.take(sorted.size / 4)
            if (lowQuartile.isNotEmpty()) {
                val newBaseline = lowQuartile.average().toFloat()
                if (newBaseline != adaptiveBaseline) {
                    adaptiveBaseline = newBaseline
                    listener?.onBaselineUpdated(adaptiveBaseline)
                }
            }
        }
        
        // Check for peak
        val isNewPeak = uSvPerHour > peakReading * 1.05f // 5% threshold for new peak
        if (uSvPerHour > peakReading) {
            peakReading = uSvPerHour
            peakTimestamp = System.currentTimeMillis()
            listener?.onPeakDetected(peakReading, isNewPeak)
        }
        
        // Calculate intensity (0-1 based on difference from baseline)
        val intensity = calculateIntensity(uSvPerHour)
        listener?.onIntensityChanged(intensity)
        
        // Update beep interval
        currentIntervalMs = calculateBeepInterval(intensity)
    }
    
    /**
     * Calculate intensity level (0-1) based on reading vs baseline
     */
    private fun calculateIntensity(uSvPerHour: Float): Float {
        val baseline = max(adaptiveBaseline, 0.01f)
        val ratio = (uSvPerHour / baseline) * settings.sensitivity
        
        // Logarithmic scaling for better response
        val intensity = when {
            ratio <= 1f -> 0f
            ratio <= 2f -> (ratio - 1f) * 0.25f
            ratio <= 5f -> 0.25f + (ratio - 2f) * 0.1f
            ratio <= 10f -> 0.55f + (ratio - 5f) * 0.05f
            ratio <= 50f -> 0.8f + (ratio - 10f) * 0.005f
            else -> 1f
        }
        
        return min(1f, max(0f, intensity))
    }
    
    /**
     * Calculate beep interval based on intensity
     */
    private fun calculateBeepInterval(intensity: Float): Long {
        // Inverse relationship: higher intensity = shorter interval
        val range = MAX_INTERVAL_MS - MIN_INTERVAL_MS
        val interval = MAX_INTERVAL_MS - (range * intensity).toLong()
        return max(MIN_INTERVAL_MS, interval)
    }
    
    /**
     * Start the beep loop
     */
    private fun startBeepLoop() {
        beepRunnable = object : Runnable {
            override fun run() {
                if (!isActive) return
                
                val now = System.currentTimeMillis()
                if (now - lastBeepTime >= currentIntervalMs) {
                    playBeep()
                    lastBeepTime = now
                }
                
                // Schedule next check
                handler.postDelayed(this, 50)
            }
        }
        handler.post(beepRunnable!!)
    }
    
    /**
     * Play a beep sound and/or haptic
     */
    private fun playBeep() {
        val intensity = calculateIntensity(currentReading)
        
        // Audio feedback
        if (settings.audioEnabled && isAudioLoaded && beepSoundId != 0) {
            val pitch = 0.5f + intensity * 1.5f  // Pitch increases with intensity
            val volume = settings.volume * (0.3f + intensity * 0.7f)
            soundPool?.play(beepSoundId, volume, volume, 1, 0, pitch)
        }
        
        // Haptic feedback
        if (settings.hapticEnabled && vibrator?.hasVibrator() == true) {
            val duration = (10 + intensity * 40).toLong()  // 10-50ms
            val amplitude = (50 + intensity * 205).toInt()  // 50-255
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        }
    }
    
    /**
     * Reset baseline to current average
     */
    fun resetBaseline() {
        if (recentReadings.isNotEmpty()) {
            adaptiveBaseline = recentReadings.average().toFloat()
            listener?.onBaselineUpdated(adaptiveBaseline)
        }
    }
    
    /**
     * Reset peak
     */
    fun resetPeak() {
        peakReading = currentReading
        peakTimestamp = System.currentTimeMillis()
        listener?.onPeakDetected(peakReading, true)
    }
    
    /**
     * Update settings
     */
    fun updateSettings(newSettings: Settings) {
        settings = newSettings
    }
    
    /**
     * Set listener for callbacks
     */
    fun setListener(newListener: Listener?) {
        listener = newListener
    }
    
    /**
     * Clean up resources
     */
    fun release() {
        stop()
        soundPool?.release()
        soundPool = null
    }
    
    /**
     * Get current state
     */
    fun getState(): State {
        return State(
            isActive = isActive,
            currentReading = currentReading,
            peakReading = peakReading,
            peakTimestamp = peakTimestamp,
            baseline = adaptiveBaseline,
            intensity = calculateIntensity(currentReading),
            beepInterval = currentIntervalMs
        )
    }
    
    data class State(
        val isActive: Boolean,
        val currentReading: Float,
        val peakReading: Float,
        val peakTimestamp: Long,
        val baseline: Float,
        val intensity: Float,
        val beepInterval: Long
    )
}
