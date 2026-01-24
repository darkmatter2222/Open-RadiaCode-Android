package com.radiacode.ble.spectrogram

import android.content.Context
import android.util.Log
import com.radiacode.ble.SpectrumData
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Repository for storing and retrieving spectrogram data.
 * Handles persistence to disk and memory caching.
 * 
 * Storage structure:
 *   /data/data/com.radiacode.ble/files/spectrogram/
 *     ├── snapshots/
 *     │   └── {deviceId}/
 *     │       └── {date}.snp  (daily snapshot files)
 *     ├── segments/
 *     │   └── {deviceId}.seg  (connection segments)
 *     ├── backgrounds/
 *     │   └── backgrounds.bg  (saved backgrounds)
 *     └── metadata.json
 */
class SpectrogramRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "SpectrogramRepo"
        private const val DIR_SPECTROGRAM = "spectrogram"
        private const val DIR_SNAPSHOTS = "snapshots"
        private const val DIR_SEGMENTS = "segments"
        private const val DIR_BACKGROUNDS = "backgrounds"
        private const val SNAPSHOT_EXTENSION = ".snp"
        private const val SEGMENT_EXTENSION = ".seg"
        private const val BACKGROUND_FILE = "backgrounds.bg"
        
        // 30 days in milliseconds
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        
        // Max snapshots to keep in memory cache per device
        private const val MAX_MEMORY_CACHE = 1000
        
        @Volatile
        private var instance: SpectrogramRepository? = null
        
        fun getInstance(context: Context): SpectrogramRepository {
            return instance ?: synchronized(this) {
                instance ?: SpectrogramRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Executor for background I/O
    private val ioExecutor = Executors.newSingleThreadExecutor()
    
    // Memory cache for recent snapshots (deviceId -> list of snapshots)
    private val snapshotCache = ConcurrentHashMap<String, MutableList<SpectrumSnapshot>>()
    private val cacheLock = ReentrantReadWriteLock()
    
    // Connection segments cache
    private val segmentCache = ConcurrentHashMap<String, MutableList<ConnectionSegment>>()
    private val segmentLock = ReentrantReadWriteLock()
    
    // Background spectra cache
    private val backgroundCache = mutableListOf<BackgroundSpectrum>()
    private val backgroundLock = ReentrantReadWriteLock()
    
    // Current snapshot ID counter
    private var nextSnapshotId = System.currentTimeMillis()
    
    // Current background ID counter
    private var nextBackgroundId = 1L
    
    init {
        ensureDirectoriesExist()
        loadBackgrounds()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DIRECTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun getBaseDir(): File = File(context.filesDir, DIR_SPECTROGRAM)
    private fun getSnapshotsDir(): File = File(getBaseDir(), DIR_SNAPSHOTS)
    private fun getSnapshotsDir(deviceId: String): File = File(getSnapshotsDir(), sanitizeFilename(deviceId))
    private fun getSegmentsDir(): File = File(getBaseDir(), DIR_SEGMENTS)
    private fun getBackgroundsDir(): File = File(getBaseDir(), DIR_BACKGROUNDS)
    
    private fun ensureDirectoriesExist() {
        getSnapshotsDir().mkdirs()
        getSegmentsDir().mkdirs()
        getBackgroundsDir().mkdirs()
    }
    
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }
    
    private fun getDateString(timestampMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return String.format("%04d%02d%02d", 
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SNAPSHOT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Add a new spectrum snapshot.
     * Adds to memory cache and queues for disk persistence.
     */
    fun addSnapshot(
        deviceId: String,
        spectrumData: SpectrumData,
        isDifferential: Boolean,
        connectionState: ConnectionSegmentType = ConnectionSegmentType.CONNECTED
    ): SpectrumSnapshot {
        val snapshot = SpectrumSnapshot(
            id = nextSnapshotId++,
            deviceId = deviceId,
            timestampMs = spectrumData.timestampMs,
            spectrumData = spectrumData,
            isDifferential = isDifferential,
            connectionState = connectionState
        )
        
        // Add to memory cache
        cacheLock.write {
            val deviceSnapshots = snapshotCache.getOrPut(deviceId) { mutableListOf() }
            deviceSnapshots.add(snapshot)
            
            // Trim cache if too large
            while (deviceSnapshots.size > MAX_MEMORY_CACHE) {
                deviceSnapshots.removeAt(0)
            }
        }
        
        // Queue for disk write
        ioExecutor.execute {
            writeSnapshotToDisk(snapshot)
        }
        
        return snapshot
    }
    
    /**
     * Add recovered spectrum data from a disconnection period.
     */
    fun addRecoveredSnapshot(
        deviceId: String,
        spectrumData: SpectrumData,
        disconnectedStartMs: Long,
        disconnectedEndMs: Long
    ): SpectrumSnapshot {
        // Create a snapshot marked as recovered
        val snapshot = SpectrumSnapshot(
            id = nextSnapshotId++,
            deviceId = deviceId,
            // Use the midpoint of disconnection as the timestamp
            timestampMs = (disconnectedStartMs + disconnectedEndMs) / 2,
            spectrumData = spectrumData,
            isDifferential = false, // Recovered data is accumulated
            connectionState = ConnectionSegmentType.DISCONNECTED_RECOVERED
        )
        
        cacheLock.write {
            val deviceSnapshots = snapshotCache.getOrPut(deviceId) { mutableListOf() }
            
            // Insert in chronological order
            val insertIndex = deviceSnapshots.indexOfFirst { it.timestampMs > snapshot.timestampMs }
            if (insertIndex >= 0) {
                deviceSnapshots.add(insertIndex, snapshot)
            } else {
                deviceSnapshots.add(snapshot)
            }
            
            while (deviceSnapshots.size > MAX_MEMORY_CACHE) {
                deviceSnapshots.removeAt(0)
            }
        }
        
        ioExecutor.execute {
            writeSnapshotToDisk(snapshot)
        }
        
        return snapshot
    }
    
    /**
     * Get snapshots for a device within a time range.
     */
    fun getSnapshots(deviceId: String, startMs: Long, endMs: Long): List<SpectrumSnapshot> {
        // First check memory cache
        val cached = cacheLock.read {
            snapshotCache[deviceId]?.filter { it.timestampMs in startMs..endMs }
        }
        
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }
        
        // Load from disk if not in cache
        return loadSnapshotsFromDisk(deviceId, startMs, endMs)
    }
    
    /**
     * Get all snapshots for a device (up to MAX_MEMORY_CACHE).
     */
    fun getAllSnapshots(deviceId: String): List<SpectrumSnapshot> {
        return cacheLock.read {
            snapshotCache[deviceId]?.toList() ?: emptyList()
        }
    }
    
    /**
     * Get the latest snapshot for a device.
     */
    fun getLatestSnapshot(deviceId: String): SpectrumSnapshot? {
        return cacheLock.read {
            snapshotCache[deviceId]?.lastOrNull()
        }
    }
    
    private fun writeSnapshotToDisk(snapshot: SpectrumSnapshot) {
        try {
            val deviceDir = getSnapshotsDir(snapshot.deviceId)
            deviceDir.mkdirs()
            
            val dateStr = getDateString(snapshot.timestampMs)
            val file = File(deviceDir, "$dateStr$SNAPSHOT_EXTENSION")
            
            BufferedWriter(FileWriter(file, true)).use { writer ->
                writer.write(snapshot.toStorageFormat())
                writer.newLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapshot to disk", e)
        }
    }
    
    private fun loadSnapshotsFromDisk(deviceId: String, startMs: Long, endMs: Long): List<SpectrumSnapshot> {
        val result = mutableListOf<SpectrumSnapshot>()
        val deviceDir = getSnapshotsDir(deviceId)
        
        if (!deviceDir.exists()) return result
        
        try {
            val files = deviceDir.listFiles { f -> f.extension == "snp" } ?: return result
            
            for (file in files) {
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val snapshot = SpectrumSnapshot.fromStorageFormat(line!!)
                        if (snapshot != null && snapshot.timestampMs in startMs..endMs) {
                            result.add(snapshot)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshots from disk", e)
        }
        
        return result.sortedBy { it.timestampMs }
    }
    
    /**
     * Load recent snapshots into memory cache for a device.
     */
    fun loadRecentSnapshotsIntoCache(deviceId: String, maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        ioExecutor.execute {
            val now = System.currentTimeMillis()
            val snapshots = loadSnapshotsFromDisk(deviceId, now - maxAgeMs, now)
            
            cacheLock.write {
                val cache = snapshotCache.getOrPut(deviceId) { mutableListOf() }
                cache.clear()
                cache.addAll(snapshots.takeLast(MAX_MEMORY_CACHE))
            }
            
            Log.d(TAG, "Loaded ${snapshots.size} snapshots into cache for $deviceId")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTION SEGMENT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a connection state change.
     */
    fun recordConnectionChange(deviceId: String, isConnected: Boolean) {
        val now = System.currentTimeMillis()
        
        segmentLock.write {
            val segments = segmentCache.getOrPut(deviceId) { mutableListOf() }
            
            // Close the previous segment if any
            val lastSegment = segments.lastOrNull()
            if (lastSegment != null && lastSegment.endMs == null) {
                val closedSegment = lastSegment.copy(endMs = now)
                segments[segments.lastIndex] = closedSegment
            }
            
            // Start new segment
            val newType = if (isConnected) ConnectionSegmentType.CONNECTED else ConnectionSegmentType.DISCONNECTED_LOST
            segments.add(ConnectionSegment(
                startMs = now,
                endMs = null,
                type = newType,
                deviceId = deviceId
            ))
        }
        
        // Persist to disk
        ioExecutor.execute {
            saveSegmentsToDisk(deviceId)
        }
    }
    
    /**
     * Mark a disconnection period as having recovered data.
     */
    fun markDisconnectionRecovered(deviceId: String, startMs: Long, endMs: Long) {
        segmentLock.write {
            val segments = segmentCache[deviceId] ?: return@write
            
            for (i in segments.indices) {
                val segment = segments[i]
                if (segment.type == ConnectionSegmentType.DISCONNECTED_LOST &&
                    segment.startMs >= startMs && (segment.endMs ?: Long.MAX_VALUE) <= endMs) {
                    segments[i] = segment.copy(type = ConnectionSegmentType.DISCONNECTED_RECOVERED)
                }
            }
        }
        
        ioExecutor.execute {
            saveSegmentsToDisk(deviceId)
        }
    }
    
    /**
     * Get connection segments for a device.
     */
    fun getConnectionSegments(deviceId: String): List<ConnectionSegment> {
        return segmentLock.read {
            segmentCache[deviceId]?.toList() ?: emptyList()
        }
    }
    
    /**
     * Get connection segments within a time range.
     */
    fun getConnectionSegments(deviceId: String, startMs: Long, endMs: Long): List<ConnectionSegment> {
        return segmentLock.read {
            segmentCache[deviceId]?.filter { segment ->
                val segEnd = segment.endMs ?: System.currentTimeMillis()
                segment.startMs < endMs && segEnd > startMs
            } ?: emptyList()
        }
    }
    
    /**
     * Get a session summary for display.
     */
    fun getSessionSummary(deviceId: String): SessionSummary {
        val segments = getConnectionSegments(deviceId)
        val snapshots = getAllSnapshots(deviceId)
        
        if (segments.isEmpty() && snapshots.isEmpty()) {
            return SessionSummary(
                startMs = System.currentTimeMillis(),
                endMs = null,
                connectedDurationMs = 0,
                disconnectedDurationMs = 0,
                snapshotCount = 0,
                deviceId = deviceId
            )
        }
        
        val startMs = minOf(
            segments.firstOrNull()?.startMs ?: Long.MAX_VALUE,
            snapshots.firstOrNull()?.timestampMs ?: Long.MAX_VALUE
        )
        
        val endMs = if (segments.lastOrNull()?.endMs == null) null else {
            maxOf(
                segments.lastOrNull()?.endMs ?: 0L,
                snapshots.lastOrNull()?.timestampMs ?: 0L
            )
        }
        
        var connectedMs = 0L
        var disconnectedMs = 0L
        
        for (segment in segments) {
            val duration = segment.durationMs
            when (segment.type) {
                ConnectionSegmentType.CONNECTED -> connectedMs += duration
                else -> disconnectedMs += duration
            }
        }
        
        return SessionSummary(
            startMs = startMs,
            endMs = endMs,
            connectedDurationMs = connectedMs,
            disconnectedDurationMs = disconnectedMs,
            snapshotCount = snapshots.size,
            deviceId = deviceId
        )
    }
    
    private fun saveSegmentsToDisk(deviceId: String) {
        try {
            val segments = segmentLock.read { segmentCache[deviceId]?.toList() } ?: return
            val file = File(getSegmentsDir(), "${sanitizeFilename(deviceId)}$SEGMENT_EXTENSION")
            
            BufferedWriter(FileWriter(file)).use { writer ->
                for (segment in segments) {
                    writer.write(segment.toStorageFormat())
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save segments to disk", e)
        }
    }
    
    /**
     * Load segments from disk for a device.
     */
    fun loadSegmentsFromDisk(deviceId: String) {
        ioExecutor.execute {
            try {
                val file = File(getSegmentsDir(), "${sanitizeFilename(deviceId)}$SEGMENT_EXTENSION")
                if (!file.exists()) return@execute
                
                val segments = mutableListOf<ConnectionSegment>()
                
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        ConnectionSegment.fromStorageFormat(line!!)?.let { segments.add(it) }
                    }
                }
                
                segmentLock.write {
                    segmentCache[deviceId] = segments
                }
                
                Log.d(TAG, "Loaded ${segments.size} segments from disk for $deviceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load segments from disk", e)
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BACKGROUND SPECTRUM OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Save current spectrum as a background for subtraction.
     */
    fun saveAsBackground(name: String, deviceId: String, spectrumData: SpectrumData): BackgroundSpectrum {
        val background = BackgroundSpectrum.fromSpectrumData(
            id = nextBackgroundId++,
            name = name,
            deviceId = deviceId,
            data = spectrumData
        )
        
        backgroundLock.write {
            backgroundCache.add(background)
        }
        
        ioExecutor.execute {
            saveBackgroundsToDisk()
        }
        
        return background
    }
    
    /**
     * Get all saved backgrounds.
     */
    fun getBackgrounds(): List<BackgroundSpectrum> {
        return backgroundLock.read {
            backgroundCache.toList()
        }
    }
    
    /**
     * Get backgrounds for a specific device.
     */
    fun getBackgrounds(deviceId: String): List<BackgroundSpectrum> {
        return backgroundLock.read {
            backgroundCache.filter { it.deviceId == deviceId }
        }
    }
    
    /**
     * Delete a background.
     */
    fun deleteBackground(id: Long) {
        backgroundLock.write {
            backgroundCache.removeAll { it.id == id }
        }
        
        ioExecutor.execute {
            saveBackgroundsToDisk()
        }
    }
    
    private fun loadBackgrounds() {
        ioExecutor.execute {
            try {
                val file = File(getBackgroundsDir(), BACKGROUND_FILE)
                if (!file.exists()) return@execute
                
                val backgrounds = mutableListOf<BackgroundSpectrum>()
                var maxId = 0L
                
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        BackgroundSpectrum.fromStorageFormat(line!!)?.let { 
                            backgrounds.add(it)
                            if (it.id > maxId) maxId = it.id
                        }
                    }
                }
                
                backgroundLock.write {
                    backgroundCache.clear()
                    backgroundCache.addAll(backgrounds)
                    nextBackgroundId = maxId + 1
                }
                
                Log.d(TAG, "Loaded ${backgrounds.size} backgrounds from disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load backgrounds from disk", e)
            }
        }
    }
    
    private fun saveBackgroundsToDisk() {
        try {
            val backgrounds = backgroundLock.read { backgroundCache.toList() }
            val file = File(getBackgroundsDir(), BACKGROUND_FILE)
            
            BufferedWriter(FileWriter(file)).use { writer ->
                for (bg in backgrounds) {
                    writer.write(bg.toStorageFormat())
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save backgrounds to disk", e)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP & MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Clean up old data (older than 30 days).
     */
    fun cleanupOldData() {
        ioExecutor.execute {
            val cutoffMs = System.currentTimeMillis() - MAX_AGE_MS
            val cutoffDate = getDateString(cutoffMs)
            
            // Clean up snapshot files
            val snapshotsDir = getSnapshotsDir()
            snapshotsDir.listFiles()?.forEach { deviceDir ->
                deviceDir.listFiles { f -> f.extension == "snp" }?.forEach { file ->
                    val fileDate = file.nameWithoutExtension
                    if (fileDate < cutoffDate) {
                        file.delete()
                        Log.d(TAG, "Deleted old snapshot file: ${file.name}")
                    }
                }
            }
            
            // Clean up old segments
            segmentLock.write {
                for ((deviceId, segments) in segmentCache) {
                    val filtered = segments.filter { 
                        (it.endMs ?: System.currentTimeMillis()) > cutoffMs 
                    }.toMutableList()
                    segmentCache[deviceId] = filtered
                }
            }
            
            // Save cleaned segments
            segmentCache.keys.forEach { deviceId ->
                saveSegmentsToDisk(deviceId)
            }
            
            Log.d(TAG, "Cleanup complete")
        }
    }
    
    /**
     * Clear all data for a device.
     */
    fun clearDeviceData(deviceId: String) {
        // Clear memory
        cacheLock.write {
            snapshotCache.remove(deviceId)
        }
        segmentLock.write {
            segmentCache.remove(deviceId)
        }
        
        // Clear disk
        ioExecutor.execute {
            val deviceDir = getSnapshotsDir(deviceId)
            deviceDir.deleteRecursively()
            
            val segmentFile = File(getSegmentsDir(), "${sanitizeFilename(deviceId)}$SEGMENT_EXTENSION")
            segmentFile.delete()
        }
    }
    
    /**
     * Get total storage used by spectrogram data.
     */
    fun getStorageUsedBytes(): Long {
        return getBaseDir().walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }
}
