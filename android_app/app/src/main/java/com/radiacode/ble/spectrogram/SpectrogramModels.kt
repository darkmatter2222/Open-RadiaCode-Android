package com.radiacode.ble.spectrogram

import com.radiacode.ble.SpectrumData

/**
 * A single spectrum snapshot captured at a specific point in time.
 * This is the atomic unit of the spectrogram waterfall display.
 */
data class SpectrumSnapshot(
    /** Unique ID for this snapshot */
    val id: Long,
    /** Device ID this snapshot is from */
    val deviceId: String,
    /** Timestamp when this snapshot was captured */
    val timestampMs: Long,
    /** The spectrum data at this moment */
    val spectrumData: SpectrumData,
    /** Whether this snapshot is from differential (true) or accumulated (false) mode */
    val isDifferential: Boolean,
    /** Connection state when captured */
    val connectionState: ConnectionSegmentType
) {
    /** Convert to serializable format for storage */
    fun toStorageFormat(): String {
        val countsStr = spectrumData.counts.joinToString(",")
        return "$id|$deviceId|$timestampMs|${spectrumData.durationSeconds}|" +
               "${spectrumData.a0}|${spectrumData.a1}|${spectrumData.a2}|" +
               "$isDifferential|${connectionState.name}|$countsStr"
    }
    
    companion object {
        fun fromStorageFormat(line: String): SpectrumSnapshot? {
            return try {
                val parts = line.split("|", limit = 10)
                if (parts.size < 10) return null
                
                val id = parts[0].toLong()
                val deviceId = parts[1]
                val timestampMs = parts[2].toLong()
                val durationSeconds = parts[3].toInt()
                val a0 = parts[4].toFloat()
                val a1 = parts[5].toFloat()
                val a2 = parts[6].toFloat()
                val isDifferential = parts[7].toBoolean()
                val connectionState = ConnectionSegmentType.valueOf(parts[8])
                val counts = parts[9].split(",").map { it.toInt() }.toIntArray()
                
                SpectrumSnapshot(
                    id = id,
                    deviceId = deviceId,
                    timestampMs = timestampMs,
                    spectrumData = SpectrumData(
                        durationSeconds = durationSeconds,
                        a0 = a0,
                        a1 = a1,
                        a2 = a2,
                        counts = counts,
                        timestampMs = timestampMs
                    ),
                    isDifferential = isDifferential,
                    connectionState = connectionState
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Represents a segment of time in the spectrogram timeline.
 * Used for the intelligent merge visualization.
 */
data class ConnectionSegment(
    /** Start time of this segment */
    val startMs: Long,
    /** End time of this segment (null if ongoing) */
    val endMs: Long?,
    /** Type of segment */
    val type: ConnectionSegmentType,
    /** Device ID */
    val deviceId: String
) {
    val durationMs: Long get() = (endMs ?: System.currentTimeMillis()) - startMs
    
    fun toStorageFormat(): String {
        return "$startMs|${endMs ?: -1}|${type.name}|$deviceId"
    }
    
    companion object {
        fun fromStorageFormat(line: String): ConnectionSegment? {
            return try {
                val parts = line.split("|")
                if (parts.size < 4) return null
                ConnectionSegment(
                    startMs = parts[0].toLong(),
                    endMs = parts[1].toLong().takeIf { it >= 0 },
                    type = ConnectionSegmentType.valueOf(parts[2]),
                    deviceId = parts[3]
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Connection segment types for visualizing the timeline.
 */
enum class ConnectionSegmentType {
    /** Device was connected and actively collecting */
    CONNECTED,
    /** Device was disconnected - data recovered on reconnect */
    DISCONNECTED_RECOVERED,
    /** Device was disconnected - no data recovered (too long, or error) */
    DISCONNECTED_LOST
}

/**
 * A saved background spectrum for subtraction.
 */
data class BackgroundSpectrum(
    /** Unique ID */
    val id: Long,
    /** User-provided name for this background */
    val name: String,
    /** Device ID this background was captured from */
    val deviceId: String,
    /** When this background was captured */
    val capturedMs: Long,
    /** Accumulation duration in seconds */
    val durationSeconds: Int,
    /** The spectrum counts (normalized per second for subtraction) */
    val countsPerSecond: FloatArray,
    /** Energy calibration */
    val a0: Float,
    val a1: Float,
    val a2: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BackgroundSpectrum
        return id == other.id && name == other.name && deviceId == other.deviceId &&
               capturedMs == other.capturedMs && durationSeconds == other.durationSeconds &&
               countsPerSecond.contentEquals(other.countsPerSecond)
    }
    
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + capturedMs.hashCode()
        result = 31 * result + durationSeconds
        result = 31 * result + countsPerSecond.contentHashCode()
        return result
    }
    
    fun toStorageFormat(): String {
        val countsStr = countsPerSecond.joinToString(",")
        return "$id|$name|$deviceId|$capturedMs|$durationSeconds|$a0|$a1|$a2|$countsStr"
    }
    
    companion object {
        fun fromStorageFormat(line: String): BackgroundSpectrum? {
            return try {
                val parts = line.split("|", limit = 9)
                if (parts.size < 9) return null
                BackgroundSpectrum(
                    id = parts[0].toLong(),
                    name = parts[1],
                    deviceId = parts[2],
                    capturedMs = parts[3].toLong(),
                    durationSeconds = parts[4].toInt(),
                    a0 = parts[5].toFloat(),
                    a1 = parts[6].toFloat(),
                    a2 = parts[7].toFloat(),
                    countsPerSecond = parts[8].split(",").map { it.toFloat() }.toFloatArray()
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Create a background spectrum from captured spectrum data.
         */
        fun fromSpectrumData(
            id: Long,
            name: String,
            deviceId: String,
            data: SpectrumData
        ): BackgroundSpectrum {
            val durationSec = maxOf(1, data.durationSeconds)
            val cps = FloatArray(data.counts.size) { i ->
                data.counts[i].toFloat() / durationSec
            }
            return BackgroundSpectrum(
                id = id,
                name = name,
                deviceId = deviceId,
                capturedMs = data.timestampMs,
                durationSeconds = data.durationSeconds,
                countsPerSecond = cps,
                a0 = data.a0,
                a1 = data.a1,
                a2 = data.a2
            )
        }
    }
}

/**
 * Display mode for the spectrogram waterfall.
 */
enum class SpectrogramDisplayMode {
    /** Show all data together */
    ALL_TOGETHER,
    /** Color-code by connection segments */
    COLOR_CODED_SEGMENTS,
    /** Show only connected periods */
    CONNECTED_ONLY
}

/**
 * Spectrum source type for collection.
 */
enum class SpectrumSourceType {
    /** Use accumulated spectrum (VS.SPECTRUM) - total since boot/reset */
    ACCUMULATED,
    /** Use differential spectrum (VS.SPEC_DIFF) - recent counts */
    DIFFERENTIAL
}

/**
 * Summary of a spectrogram session for the timeline dropdown.
 */
data class SessionSummary(
    /** Session start time */
    val startMs: Long,
    /** Session end time (null if ongoing) */
    val endMs: Long?,
    /** Total connected duration in ms */
    val connectedDurationMs: Long,
    /** Total disconnected duration in ms */
    val disconnectedDurationMs: Long,
    /** Number of spectrum snapshots */
    val snapshotCount: Int,
    /** Device ID */
    val deviceId: String
) {
    val totalDurationMs: Long get() = (endMs ?: System.currentTimeMillis()) - startMs
    val isActive: Boolean get() = endMs == null
}
