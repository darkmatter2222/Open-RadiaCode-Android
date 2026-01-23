package com.radiacode.ble

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VEGA Text-to-Speech Client
 * 
 * Synthesizes speech using the Vega TTS API and plays it directly.
 * Uses the VEGA voice from Doom Eternal - a calm, authoritative AI voice
 * perfect for radiation monitoring alerts.
 * 
 * Voice Style Guidelines:
 * - Calm but urgent for critical readings
 * - Technical but clear
 * - Direct, no pleasantries
 * - Uses precise measurements
 */
object VegaTTS {

    private const val TAG = "VegaTTS"
    
    // API Configuration - can be updated via Prefs later
    private const val DEFAULT_API_URL = "http://99.122.58.29:443"
    private const val TIMEOUT_MS = 30_000 // 30 seconds max
    
    private var executor: ExecutorService? = null
    private var mediaPlayer: MediaPlayer? = null
    private val isPlaying = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    
    /**
     * Initialize the TTS system.
     */
    fun init(context: Context) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor()
        }
        Log.d(TAG, "VegaTTS initialized")
    }
    
    /**
     * Release resources.
     */
    fun release() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            executor?.shutdown()
            executor = null
            isPlaying.set(false)
            isSpeaking.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VegaTTS", e)
        }
    }
    
    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean = isSpeaking.get()
    
    /**
     * Speak text using the Vega TTS API.
     * Runs asynchronously and plays audio when ready.
     * 
     * @param context Android context
     * @param text Text to synthesize and speak
     * @param onComplete Optional callback when speech completes
     * @param onError Optional callback if an error occurs
     */
    fun speak(
        context: Context,
        text: String,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onError?.invoke("Empty text")
            return
        }
        
        // Check if TTS is enabled
        if (!Prefs.isVegaTtsEnabled(context)) {
            Log.d(TAG, "VegaTTS disabled in preferences")
            onComplete?.invoke()
            return
        }
        
        // Don't interrupt if already speaking (queue behavior could be added later)
        if (isSpeaking.get()) {
            Log.d(TAG, "Already speaking, ignoring new request")
            return
        }
        
        val exec = executor ?: run {
            init(context)
            executor!!
        }
        
        isSpeaking.set(true)
        Log.d(TAG, "VegaTTS: Starting synthesis NOW for: $text")
        
        exec.execute {
            try {
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "VegaTTS: API call starting")
                
                val apiUrl = Prefs.getVegaTtsApiUrl(context)
                val url = URL("$apiUrl/api/tts/synthesize")
                
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                
                // Send JSON body
                val jsonBody = """{"text":"${text.replace("\"", "\\\"")}"}"""
                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    Log.e(TAG, "API error: $responseCode - $error")
                    isSpeaking.set(false)
                    onError?.invoke("API error: $responseCode")
                    return@execute
                }
                
                // Read audio data
                val audioData = connection.inputStream.use { it.readBytes() }
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "VegaTTS: Received ${audioData.size} bytes in ${elapsed}ms")
                
                // Save to temp file and play
                val tempFile = File.createTempFile("vega_tts_", ".wav", context.cacheDir)
                FileOutputStream(tempFile).use { fos ->
                    fos.write(audioData)
                }
                
                // Play on main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    playAudioFile(tempFile, onComplete, onError)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "TTS synthesis failed", e)
                isSpeaking.set(false)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }
    
    private fun playAudioFile(
        file: File, 
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        try {
            // Release previous player
            mediaPlayer?.release()
            
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            
            player.setOnCompletionListener {
                Log.d(TAG, "Playback complete")
                isPlaying.set(false)
                isSpeaking.set(false)
                file.delete() // Cleanup temp file
                onComplete?.invoke()
            }
            
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                isPlaying.set(false)
                isSpeaking.set(false)
                file.delete()
                onError?.invoke("Playback error: $what")
                true
            }
            
            player.prepare()
            player.start()
            mediaPlayer = player
            isPlaying.set(true)
            Log.d(TAG, "Playing audio...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            isPlaying.set(false)
            isSpeaking.set(false)
            file.delete()
            onError?.invoke(e.message ?: "Playback failed")
        }
    }
    
    /**
     * Stop any current speech.
     */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying.set(false)
            isSpeaking.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }
    
    // ========== VEGA-Style Message Generators ==========
    
    /**
     * Generate VEGA-style alert announcement.
     * 
     * VEGA speaks in a calm, analytical manner.
     * Measured. Informative. Never panicked.
     */
    fun generateAlertAnnouncement(
        alertName: String,
        metric: String,
        currentValue: Double,
        threshold: Double,
        condition: String,
        severity: String,
        durationSeconds: Int
    ): String {
        val value = if (metric == "dose") {
            String.format("%.2f", currentValue)
        } else {
            String.format("%.0f", currentValue)
        }
        val unit = if (metric == "dose") "microsieverts per hour" else "counts per second"
        
        // VEGA style: Calm. Analytical. Measured.
        return when (severity.lowercase()) {
            "emergency" -> "Emergency. Radiation levels require immediate attention. Current reading: $value $unit."
            "critical" -> "Critical alert. Radiation levels exceed safe thresholds. Current reading: $value $unit. Limiting exposure is advised."
            "high" -> "Warning. Radiation levels are significantly elevated. Current reading: $value $unit. Proceed with caution."
            "medium" -> "Advisory. Radiation levels have increased above baseline. Current reading: $value $unit."
            "low" -> "Notice. A change in radiation levels has been detected. Current reading: $value $unit."
            else -> "Update. Current radiation reading: $value $unit."
        }
    }
    
    /**
     * Generate VEGA-style anomaly announcement.
     */
    fun generateAnomalyAnnouncement(
        anomalyType: String,
        currentValue: Double,
        zScore: Double,
        metric: String
    ): String {
        val unit = if (metric == "dose") "microsieverts" else "counts"
        val value = if (metric == "dose") {
            String.format("%.2f", currentValue)
        } else {
            String.format("%.0f", currentValue)
        }
        val sigma = String.format("%.1f", kotlin.math.abs(zScore))
        
        return when (anomalyType.lowercase()) {
            "spike", "dose_spike", "cps_spike" -> "Anomaly. Spike detected. $value $unit. $sigma sigma."
            "drop", "sudden_drop" -> "Anomaly. Drop detected. $value $unit."
            else -> "Anomaly. $value $unit. $sigma sigma deviation."
        }
    }
    
    /**
     * Generate status announcement.
     */
    fun generateStatusAnnouncement(
        connected: Boolean,
        deviceName: String? = null,
        currentDose: Double? = null
    ): String {
        return if (connected) {
            currentDose?.let { "Online. ${String.format("%.2f", it)} microsieverts." }
                ?: "Online. Systems nominal."
        } else {
            "Connection lost. Reconnecting."
        }
    }
}
