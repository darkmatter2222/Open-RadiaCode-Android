package com.radiacode.ble

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * Centralized sound manager for all app audio feedback.
 * 
 * Uses SoundPool for short sound effects (tick, connect, etc.) for low latency,
 * and MediaPlayer for looping ambient audio.
 * 
 * Sound Types:
 * - DATA_TICK: Very subtle soft tick when data arrives (disabled by default)
 * - CONNECTED: Pleasant chime when device connects
 * - DISCONNECTED: Subtle descending tone when device disconnects
 * - ALARM: Alert sound when a smart alert triggers
 * - ANOMALY: Distinct sound for statistical anomaly detection
 * - AMBIENT: Looping sci-fi ambient drone (disabled by default)
 */
class SoundManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SoundManager"
        
        @Volatile
        private var instance: SoundManager? = null
        
        fun getInstance(context: Context): SoundManager {
            return instance ?: synchronized(this) {
                instance ?: SoundManager(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Initialize the SoundManager (call from service onCreate).
         */
        fun init(context: Context) {
            getInstance(context) // Just ensure instance exists
        }
        
        /**
         * Play a sound effect. This is the main entry point for playing sounds.
         */
        fun play(context: Context, soundType: Prefs.SoundType) {
            getInstance(context).play(soundType)
        }
        
        /**
         * Release resources (call from service onDestroy).
         */
        fun release() {
            instance?.releaseInternal()
            instance = null
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())

    private val debugLogs = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    
    // SoundPool for short sound effects (low latency)
    private var soundPool: SoundPool? = null
    private var dataTickSoundId: Int = 0
    private var connectedSoundId: Int = 0
    private var disconnectedSoundId: Int = 0
    private var alarmSoundId: Int = 0
    private var anomalySoundId: Int = 0
    private var soundsLoaded = AtomicBoolean(false)
    
    // Track which resource name was loaded into each soundId for debugging
    private val soundIdToResourceName = mutableMapOf<Int, String>()
    
    // MediaPlayer for looping ambient audio
    private var ambientPlayer: MediaPlayer? = null
    private var isAmbientPlaying = false
    private var targetAmbientVolume = 0.05f
    
    // Throttle data tick to prevent overwhelming (max 1 per 500ms)
    private var lastDataTickTime = 0L
    private val dataTickThrottleMs = 500L
    
    init {
        initializeSoundPool()
    }
    
    private fun initializeSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            soundPool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build()
            
            soundPool?.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) {
                    Log.d(TAG, "Sound loaded successfully")
                } else {
                    Log.w(TAG, "Sound failed to load, status=$status")
                }
            }
            
            // Load sound effects from raw resources
            // Note: You'll need to add these sound files to res/raw/
            loadSounds()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SoundPool", e)
        }
    }

    private fun describeRawResource(resId: Int): String {
        return try {
            val entryName = context.resources.getResourceEntryName(resId)
            val length = try {
                context.resources.openRawResourceFd(resId)?.use { it.length } ?: -1L
            } catch (_: Throwable) {
                -1L
            }

            val crc32 = CRC32()
            var total = 0
            context.resources.openRawResource(resId).use { input ->
                val buffer = ByteArray(4096)
                while (total < 16 * 1024) {
                    val remaining = 16 * 1024 - total
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    crc32.update(buffer, 0, read)
                    total += read
                }
            }

            "raw/$entryName len=$length crc32=${crc32.value} hashed=$total"
        } catch (t: Throwable) {
            "resId=$resId (describe failed: ${t.javaClass.simpleName})"
        }
    }
    
    private fun loadSounds() {
        try {
            // These will be loaded from res/raw/ folder
            // Sound files should be added as:
            // - sound_data_tick.wav (or .mp3, .ogg)
            // - sound_connected.wav
            // - sound_disconnected.wav
            // - sound_alarm.wav
            // - sound_anomaly.wav
            // - ambient_drone.wav (already exists)
            soundPool?.let { pool ->
                // Clear the tracking map
                soundIdToResourceName.clear()
                
                // Load sounds and track which file goes to which soundId
                val dataTickResId = context.resources.getIdentifier("sound_data_tick", "raw", context.packageName)
                val connectedResId = context.resources.getIdentifier("sound_connected", "raw", context.packageName)
                val disconnectedResId = context.resources.getIdentifier("sound_disconnected", "raw", context.packageName)
                val alarmResId = context.resources.getIdentifier("sound_alarm", "raw", context.packageName)
                val anomalyResId = context.resources.getIdentifier("sound_anomaly", "raw", context.packageName)
                
                if (debugLogs) {
                    Log.d(TAG, "=== LOADING SOUNDS ===")
                    Log.d(TAG, "Resource IDs from getIdentifier: dataTick=$dataTickResId, connected=$connectedResId, disconnected=$disconnectedResId, alarm=$alarmResId, anomaly=$anomalyResId")
                    if (dataTickResId != 0) Log.d(TAG, "sound_data_tick -> ${describeRawResource(dataTickResId)}")
                    if (connectedResId != 0) Log.d(TAG, "sound_connected -> ${describeRawResource(connectedResId)}")
                    if (disconnectedResId != 0) Log.d(TAG, "sound_disconnected -> ${describeRawResource(disconnectedResId)}")
                    if (alarmResId != 0) Log.d(TAG, "sound_alarm -> ${describeRawResource(alarmResId)}")
                    if (anomalyResId != 0) Log.d(TAG, "sound_anomaly -> ${describeRawResource(anomalyResId)}")
                }
                
                if (dataTickResId != 0) {
                    dataTickSoundId = pool.load(context, dataTickResId, 1)
                    soundIdToResourceName[dataTickSoundId] = "sound_data_tick"
                    if (debugLogs) Log.d(TAG, "Loaded sound_data_tick (resId=$dataTickResId) -> soundId=$dataTickSoundId")
                }
                if (connectedResId != 0) {
                    connectedSoundId = pool.load(context, connectedResId, 1)
                    soundIdToResourceName[connectedSoundId] = "sound_connected"
                    if (debugLogs) Log.d(TAG, "Loaded sound_connected (resId=$connectedResId) -> soundId=$connectedSoundId")
                }
                if (disconnectedResId != 0) {
                    disconnectedSoundId = pool.load(context, disconnectedResId, 1)
                    soundIdToResourceName[disconnectedSoundId] = "sound_disconnected"
                    if (debugLogs) Log.d(TAG, "Loaded sound_disconnected (resId=$disconnectedResId) -> soundId=$disconnectedSoundId")
                }
                if (alarmResId != 0) {
                    alarmSoundId = pool.load(context, alarmResId, 1)
                    soundIdToResourceName[alarmSoundId] = "sound_alarm"
                    if (debugLogs) Log.d(TAG, "Loaded sound_alarm (resId=$alarmResId) -> soundId=$alarmSoundId")
                }
                if (anomalyResId != 0) {
                    anomalySoundId = pool.load(context, anomalyResId, 1)
                    soundIdToResourceName[anomalySoundId] = "sound_anomaly"
                    if (debugLogs) Log.d(TAG, "Loaded sound_anomaly (resId=$anomalyResId) -> soundId=$anomalySoundId")
                }

                if (debugLogs) {
                    Log.d(TAG, "=== SOUND ID ASSIGNMENTS ===")
                    Log.d(TAG, "dataTickSoundId=$dataTickSoundId -> ${soundIdToResourceName[dataTickSoundId]}")
                    Log.d(TAG, "connectedSoundId=$connectedSoundId -> ${soundIdToResourceName[connectedSoundId]}")
                    Log.d(TAG, "disconnectedSoundId=$disconnectedSoundId -> ${soundIdToResourceName[disconnectedSoundId]}")
                    Log.d(TAG, "alarmSoundId=$alarmSoundId -> ${soundIdToResourceName[alarmSoundId]}")
                    Log.d(TAG, "anomalySoundId=$anomalySoundId -> ${soundIdToResourceName[anomalySoundId]}")
                }
                
                // Mark sounds as loaded after a brief delay to ensure loading completes
                handler.postDelayed({ 
                    soundsLoaded.set(true)
                    Log.d(TAG, "Sounds marked as loaded")
                }, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sounds", e)
        }
    }
    
    /**
     * Try to load a sound resource, returning 0 if not found.
     */
    private fun tryLoadSound(pool: SoundPool, resourceName: String): Int {
        return try {
            val resId = context.resources.getIdentifier(resourceName, "raw", context.packageName)
            if (resId != 0) {
                pool.load(context, resId, 1)
            } else {
                Log.w(TAG, "Sound resource not found: $resourceName")
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load sound: $resourceName", e)
            0
        }
    }
    
    /**
     * Play a sound effect based on the sound type.
     * Respects user preferences for enabled/disabled sounds and volume levels.
     * @param forcePlay If true, bypasses enabled check and throttling (for test buttons)
     */
    fun play(soundType: Prefs.SoundType, forcePlay: Boolean = false) {
        if (!forcePlay && !Prefs.isSoundEnabled(context, soundType)) {
            Log.d(TAG, "Sound ${soundType.name} is disabled, skipping")
            return
        }
        
        when (soundType) {
            Prefs.SoundType.DATA_TICK -> {
                if (forcePlay) {
                    // Bypass throttle for test
                    playShortSound(dataTickSoundId, soundType)
                } else {
                    playDataTick()
                }
            }
            Prefs.SoundType.CONNECTED -> playShortSound(connectedSoundId, soundType)
            Prefs.SoundType.DISCONNECTED -> playShortSound(disconnectedSoundId, soundType)
            Prefs.SoundType.ALARM -> playShortSound(alarmSoundId, soundType)
            Prefs.SoundType.ANOMALY -> playShortSound(anomalySoundId, soundType)
            Prefs.SoundType.AMBIENT -> startAmbient()
        }
    }
    
    /**
     * Play data tick with throttling to prevent overwhelming.
     */
    private fun playDataTick() {
        val now = System.currentTimeMillis()
        if (now - lastDataTickTime < dataTickThrottleMs) {
            Log.d(TAG, "Data tick throttled (last=$lastDataTickTime, now=$now, diff=${now - lastDataTickTime}ms)")
            return // Throttled
        }
        lastDataTickTime = now
        playShortSound(dataTickSoundId, Prefs.SoundType.DATA_TICK)
    }
    
    private fun playShortSound(soundId: Int, soundType: Prefs.SoundType) {
        val actualFile = soundIdToResourceName[soundId] ?: "UNKNOWN"
        Log.d(TAG, "playShortSound: type=${soundType.name}, soundId=$soundId, actualFile=$actualFile, soundsLoaded=${soundsLoaded.get()}")
        if (!soundsLoaded.get()) {
            Log.d(TAG, "Sound not ready: sounds not loaded yet")
            return
        }
        if (soundId == 0) {
            Log.d(TAG, "Sound not ready: soundId is 0 for ${soundType.name}")
            return
        }
        
        try {
            val volume = Prefs.getSoundVolume(context, soundType)
            Log.d(TAG, ">>> PLAYING: ${soundType.name} -> soundId=$soundId -> FILE=$actualFile at volume $volume")
            val streamId = soundPool?.play(soundId, volume, volume, 1, 0, 1f)
            Log.d(TAG, "SoundPool.play returned streamId=$streamId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play sound", e)
        }
    }
    
    /**
     * Start the ambient background audio (looping).
     * Note: Requires res/raw/ambient_drone.wav to be present.
     */
    fun startAmbient() {
        if (isAmbientPlaying) return
        if (!Prefs.isSoundEnabled(context, Prefs.SoundType.AMBIENT)) return
        
        try {
            stopAmbient()
            
            // Try to load ambient_drone, but gracefully handle if it doesn't exist
            val resId = context.resources.getIdentifier("ambient_drone", "raw", context.packageName)
            if (resId == 0) {
                Log.w(TAG, "ambient_drone.wav not found in res/raw - skipping ambient audio")
                return
            }
            
            ambientPlayer = MediaPlayer.create(context, resId)?.apply {
                isLooping = true
                val volume = Prefs.getSoundVolume(context, Prefs.SoundType.AMBIENT)
                setVolume(volume, volume)
                start()
            }
            if (ambientPlayer != null) {
                isAmbientPlaying = true
                Log.d(TAG, "Started ambient audio")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ambient audio", e)
        }
    }
    
    /**
     * Stop the ambient background audio.
     */
    fun stopAmbient() {
        try {
            ambientPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ambient", e)
        }
        ambientPlayer = null
        isAmbientPlaying = false
        Log.d(TAG, "Stopped ambient audio")
    }
    
    /**
     * Update ambient volume without restarting.
     */
    fun updateAmbientVolume() {
        if (!isAmbientPlaying) return
        try {
            val volume = Prefs.getSoundVolume(context, Prefs.SoundType.AMBIENT)
            ambientPlayer?.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ambient volume", e)
        }
    }
    
    /**
     * Toggle ambient audio on/off based on current state.
     */
    fun toggleAmbient() {
        if (isAmbientPlaying) {
            stopAmbient()
        } else {
            startAmbient()
        }
    }
    
    /**
     * Check if ambient audio is currently playing.
     */
    fun isAmbientPlaying(): Boolean = isAmbientPlaying
    
    /**
     * Refresh ambient state based on user preferences.
     * Call this when sound settings change.
     */
    fun refreshAmbientState() {
        if (Prefs.isSoundEnabled(context, Prefs.SoundType.AMBIENT)) {
            if (!isAmbientPlaying) {
                startAmbient()
            } else {
                updateAmbientVolume()
            }
        } else {
            stopAmbient()
        }
    }
    
    /**
     * Duck ambient audio (reduce volume temporarily).
     * Useful when playing other sounds.
     */
    fun duckAmbient(duckVolume: Float = 0.02f, durationMs: Long = 1000) {
        if (!isAmbientPlaying) return
        
        try {
            val originalVolume = Prefs.getSoundVolume(context, Prefs.SoundType.AMBIENT)
            ambientPlayer?.setVolume(duckVolume, duckVolume)
            
            handler.postDelayed({
                if (isAmbientPlaying) {
                    ambientPlayer?.setVolume(originalVolume, originalVolume)
                }
            }, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to duck ambient", e)
        }
    }
    
    /**
     * Internal release method called from companion object.
     */
    private fun releaseInternal() {
        stopAmbient()
        soundPool?.release()
        soundPool = null
        soundsLoaded.set(false)
        Log.d(TAG, "SoundManager released")
    }
}
