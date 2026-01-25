package com.radiacode.ble

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

/**
 * Session Management System
 * Handles named session recording, storage, and comparison
 */
object SessionManager {

    private const val PREFS_NAME = "radiacode_sessions"
    private const val KEY_SESSIONS_JSON = "sessions_json"
    private const val KEY_ACTIVE_SESSION = "active_session_id"
    private const val KEY_SESSION_DATA_PREFIX = "session_data_"
    private const val MAX_SESSIONS = 50
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Session metadata
     */
    data class Session(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val startTimeMs: Long,
        var endTimeMs: Long? = null,
        var sampleCount: Int = 0,
        var doseMin: Float = Float.MAX_VALUE,
        var doseMax: Float = Float.MIN_VALUE,
        var doseMean: Float = 0f,
        var cpsMin: Float = Float.MAX_VALUE,
        var cpsMax: Float = Float.MIN_VALUE,
        var cpsMean: Float = 0f,
        var deviceIds: List<String> = emptyList(),
        var locationName: String? = null,
        var notes: String = "",
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val isActive: Boolean get() = endTimeMs == null
        
        val durationMs: Long get() = (endTimeMs ?: System.currentTimeMillis()) - startTimeMs
        
        val durationFormatted: String get() {
            val seconds = durationMs / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return when {
                hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
                else -> String.format("%d:%02d", minutes, secs)
            }
        }
        
        fun toJson(): String {
            return """{"id":"$id","name":"${name.replace("\"", "\\\"")}","startTimeMs":$startTimeMs,"endTimeMs":${endTimeMs ?: "null"},"sampleCount":$sampleCount,"doseMin":$doseMin,"doseMax":$doseMax,"doseMean":$doseMean,"cpsMin":$cpsMin,"cpsMax":$cpsMax,"cpsMean":$cpsMean,"deviceIds":[${deviceIds.joinToString(",") { "\"$it\"" }}],"locationName":${locationName?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},"notes":"${notes.replace("\"", "\\\"")}","createdAt":$createdAt}"""
        }
        
        companion object {
            fun fromJson(json: String): Session? {
                return try {
                    // Simple JSON parsing
                    val id = json.extractString("id") ?: return null
                    val name = json.extractString("name") ?: "Unnamed"
                    val startTimeMs = json.extractLong("startTimeMs") ?: return null
                    val endTimeMs = json.extractLongOrNull("endTimeMs")
                    val sampleCount = json.extractInt("sampleCount") ?: 0
                    val doseMin = json.extractFloat("doseMin") ?: Float.MAX_VALUE
                    val doseMax = json.extractFloat("doseMax") ?: Float.MIN_VALUE
                    val doseMean = json.extractFloat("doseMean") ?: 0f
                    val cpsMin = json.extractFloat("cpsMin") ?: Float.MAX_VALUE
                    val cpsMax = json.extractFloat("cpsMax") ?: Float.MIN_VALUE
                    val cpsMean = json.extractFloat("cpsMean") ?: 0f
                    val locationName = json.extractStringOrNull("locationName")
                    val notes = json.extractString("notes") ?: ""
                    val createdAt = json.extractLong("createdAt") ?: System.currentTimeMillis()
                    
                    Session(
                        id = id,
                        name = name,
                        startTimeMs = startTimeMs,
                        endTimeMs = endTimeMs,
                        sampleCount = sampleCount,
                        doseMin = doseMin,
                        doseMax = doseMax,
                        doseMean = doseMean,
                        cpsMin = cpsMin,
                        cpsMax = cpsMax,
                        cpsMean = cpsMean,
                        locationName = locationName,
                        notes = notes,
                        createdAt = createdAt
                    )
                } catch (e: Exception) {
                    null
                }
            }
            
            private fun String.extractString(key: String): String? {
                val pattern = "\"$key\":\"([^\"]*)\""
                return Regex(pattern).find(this)?.groupValues?.getOrNull(1)
            }
            
            private fun String.extractStringOrNull(key: String): String? {
                val nullPattern = "\"$key\":null"
                if (contains(nullPattern)) return null
                return extractString(key)
            }
            
            private fun String.extractLong(key: String): Long? {
                val pattern = "\"$key\":([0-9]+)"
                return Regex(pattern).find(this)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            
            private fun String.extractLongOrNull(key: String): Long? {
                val nullPattern = "\"$key\":null"
                if (contains(nullPattern)) return null
                return extractLong(key)
            }
            
            private fun String.extractInt(key: String): Int? {
                val pattern = "\"$key\":([0-9]+)"
                return Regex(pattern).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            
            private fun String.extractFloat(key: String): Float? {
                val pattern = "\"$key\":([0-9.E+-]+)"
                return Regex(pattern).find(this)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            }
        }
    }

    /**
     * Session data point
     */
    data class SessionDataPoint(
        val timestampMs: Long,
        val uSvPerHour: Float,
        val cps: Float,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val deviceId: String? = null
    ) {
        fun toCsv(): String = "$timestampMs,$uSvPerHour,$cps,${latitude ?: ""},${longitude ?: ""},${deviceId ?: ""}"
        
        companion object {
            fun fromCsv(csv: String): SessionDataPoint? {
                val parts = csv.split(",")
                if (parts.size < 3) return null
                return try {
                    SessionDataPoint(
                        timestampMs = parts[0].toLong(),
                        uSvPerHour = parts[1].toFloat(),
                        cps = parts[2].toFloat(),
                        latitude = parts.getOrNull(3)?.toDoubleOrNull(),
                        longitude = parts.getOrNull(4)?.toDoubleOrNull(),
                        deviceId = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // ============== Session Management ==============

    /**
     * Start a new recording session
     */
    fun startSession(context: Context, name: String? = null): Session {
        val sessionName = name ?: "Session ${filenameDateFormat.format(Date())}"
        val session = Session(
            name = sessionName,
            startTimeMs = System.currentTimeMillis()
        )
        
        // Save session metadata
        val sessions = getSessions(context).toMutableList()
        sessions.add(0, session)
        
        // Trim old sessions
        while (sessions.size > MAX_SESSIONS) {
            val removed = sessions.removeLast()
            clearSessionData(context, removed.id)
        }
        
        saveSessions(context, sessions)
        setActiveSession(context, session.id)
        
        return session
    }

    /**
     * Stop the active recording session
     */
    fun stopSession(context: Context): Session? {
        val activeId = getActiveSessionId(context) ?: return null
        val sessions = getSessions(context).toMutableList()
        val index = sessions.indexOfFirst { it.id == activeId }
        if (index < 0) return null
        
        val session = sessions[index]
        session.endTimeMs = System.currentTimeMillis()
        
        // Calculate final stats
        val data = getSessionData(context, session.id)
        if (data.isNotEmpty()) {
            session.sampleCount = data.size
            session.doseMin = data.minOf { it.uSvPerHour }
            session.doseMax = data.maxOf { it.uSvPerHour }
            session.doseMean = data.map { it.uSvPerHour }.average().toFloat()
            session.cpsMin = data.minOf { it.cps }
            session.cpsMax = data.maxOf { it.cps }
            session.cpsMean = data.map { it.cps }.average().toFloat()
            session.deviceIds = data.mapNotNull { it.deviceId }.distinct()
        }
        
        sessions[index] = session
        saveSessions(context, sessions)
        setActiveSession(context, null)
        
        return session
    }

    /**
     * Add data point to active session
     */
    fun addDataPoint(
        context: Context,
        uSvPerHour: Float,
        cps: Float,
        latitude: Double? = null,
        longitude: Double? = null,
        deviceId: String? = null
    ) {
        val activeId = getActiveSessionId(context) ?: return
        
        val dataPoint = SessionDataPoint(
            timestampMs = System.currentTimeMillis(),
            uSvPerHour = uSvPerHour,
            cps = cps,
            latitude = latitude,
            longitude = longitude,
            deviceId = deviceId
        )
        
        appendSessionData(context, activeId, dataPoint)
    }

    /**
     * Get all sessions
     */
    fun getSessions(context: Context): List<Session> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS_JSON, "[]") ?: "[]"
        
        // Parse JSON array
        val sessions = mutableListOf<Session>()
        val pattern = Regex("\\{[^{}]+\\}")
        pattern.findAll(json).forEach { match ->
            Session.fromJson(match.value)?.let { sessions.add(it) }
        }
        
        return sessions
    }

    /**
     * Get session by ID
     */
    fun getSession(context: Context, sessionId: String): Session? {
        return getSessions(context).find { it.id == sessionId }
    }

    /**
     * Update session metadata
     */
    fun updateSession(context: Context, session: Session) {
        val sessions = getSessions(context).toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = session
            saveSessions(context, sessions)
        }
    }

    /**
     * Delete a session
     */
    fun deleteSession(context: Context, sessionId: String) {
        val sessions = getSessions(context).toMutableList()
        sessions.removeAll { it.id == sessionId }
        saveSessions(context, sessions)
        clearSessionData(context, sessionId)
    }

    /**
     * Get active session ID
     */
    fun getActiveSessionId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_SESSION, null)
    }

    /**
     * Check if currently recording
     */
    fun isRecording(context: Context): Boolean {
        return getActiveSessionId(context) != null
    }

    /**
     * Get session data points
     */
    fun getSessionData(context: Context, sessionId: String): List<SessionDataPoint> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val csv = prefs.getString(KEY_SESSION_DATA_PREFIX + sessionId, "") ?: ""
        
        return csv.split("\n")
            .filter { it.isNotEmpty() }
            .mapNotNull { SessionDataPoint.fromCsv(it) }
    }

    // ============== Private Helpers ==============

    private fun setActiveSession(context: Context, sessionId: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SESSION, sessionId)
            .apply()
    }

    private fun saveSessions(context: Context, sessions: List<Session>) {
        val json = "[" + sessions.joinToString(",") { it.toJson() } + "]"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS_JSON, json)
            .apply()
    }

    private fun appendSessionData(context: Context, sessionId: String, dataPoint: SessionDataPoint) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_SESSION_DATA_PREFIX + sessionId
        val existing = prefs.getString(key, "") ?: ""
        val newData = if (existing.isEmpty()) dataPoint.toCsv() else "$existing\n${dataPoint.toCsv()}"
        
        prefs.edit()
            .putString(key, newData)
            .apply()
    }

    private fun clearSessionData(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION_DATA_PREFIX + sessionId)
            .apply()
    }

    // ============== Session Comparison ==============

    /**
     * Compare two sessions
     */
    data class SessionComparison(
        val session1: Session,
        val session2: Session,
        val doseAvgDiff: Float,
        val doseAvgDiffPercent: Float,
        val cpsAvgDiff: Float,
        val cpsAvgDiffPercent: Float,
        val doseMaxDiff: Float,
        val cpsMaxDiff: Float,
        val summary: String
    )

    fun compareSessions(context: Context, sessionId1: String, sessionId2: String): SessionComparison? {
        val s1 = getSession(context, sessionId1) ?: return null
        val s2 = getSession(context, sessionId2) ?: return null

        val doseAvgDiff = s2.doseMean - s1.doseMean
        val doseAvgDiffPercent = if (s1.doseMean > 0) (doseAvgDiff / s1.doseMean) * 100 else 0f
        
        val cpsAvgDiff = s2.cpsMean - s1.cpsMean
        val cpsAvgDiffPercent = if (s1.cpsMean > 0) (cpsAvgDiff / s1.cpsMean) * 100 else 0f

        val doseMaxDiff = s2.doseMax - s1.doseMax
        val cpsMaxDiff = s2.cpsMax - s1.cpsMax

        val summary = when {
            doseAvgDiffPercent > 20 -> "Session 2 shows ${String.format("%.0f", doseAvgDiffPercent)}% higher dose rate"
            doseAvgDiffPercent < -20 -> "Session 2 shows ${String.format("%.0f", -doseAvgDiffPercent)}% lower dose rate"
            else -> "Sessions show similar radiation levels"
        }

        return SessionComparison(
            session1 = s1,
            session2 = s2,
            doseAvgDiff = doseAvgDiff,
            doseAvgDiffPercent = doseAvgDiffPercent,
            cpsAvgDiff = cpsAvgDiff,
            cpsAvgDiffPercent = cpsAvgDiffPercent,
            doseMaxDiff = doseMaxDiff,
            cpsMaxDiff = cpsMaxDiff,
            summary = summary
        )
    }
}
