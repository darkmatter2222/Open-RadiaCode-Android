package com.radiacode.ble.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.radiacode.ble.MainActivity
import com.radiacode.ble.R

/**
 * Danger Zone Warning System
 * 
 * Multi-tier alert system that provides visual, audio, and haptic warnings
 * when radiation levels exceed configurable thresholds.
 */
class DangerZoneWarning(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Alert state
    private var currentLevel: AlertLevel = AlertLevel.NORMAL
    private var isAlertActive = false
    private var lastAlertTimeMs = 0L
    private val alertCooldownMs = 5000L // 5 second cooldown between repeated alerts
    
    // Callbacks
    var onAlertLevelChanged: ((AlertLevel, Float) -> Unit)? = null
    var onDangerZoneEntered: ((Float) -> Unit)? = null
    var onDangerZoneExited: (() -> Unit)? = null
    
    // Configurable thresholds (µSv/h)
    private val prefs = context.getSharedPreferences("danger_zone_prefs", Context.MODE_PRIVATE)
    
    var cautionThreshold: Float
        get() = prefs.getFloat("caution_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("caution_threshold", value).apply()
    
    var warningThreshold: Float
        get() = prefs.getFloat("warning_threshold", 2.5f)
        set(value) = prefs.edit().putFloat("warning_threshold", value).apply()
    
    var dangerThreshold: Float
        get() = prefs.getFloat("danger_threshold", 10f)
        set(value) = prefs.edit().putFloat("danger_threshold", value).apply()
    
    var criticalThreshold: Float
        get() = prefs.getFloat("critical_threshold", 100f)
        set(value) = prefs.edit().putFloat("critical_threshold", value).apply()
    
    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound_enabled", true)
        set(value) = prefs.edit().putBoolean("sound_enabled", value).apply()
    
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration_enabled", true)
        set(value) = prefs.edit().putBoolean("vibration_enabled", value).apply()
    
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    init {
        createNotificationChannel()
    }

    /**
     * Check radiation level and trigger appropriate alerts.
     */
    fun checkRadiationLevel(doseRate: Float) {
        val newLevel = determineAlertLevel(doseRate)
        
        // Check for level change
        if (newLevel != currentLevel) {
            val previousLevel = currentLevel
            currentLevel = newLevel
            
            onAlertLevelChanged?.invoke(newLevel, doseRate)
            
            // Check if entering danger zone
            if (newLevel.severity > AlertLevel.CAUTION.severity && previousLevel.severity <= AlertLevel.CAUTION.severity) {
                onDangerZoneEntered?.invoke(doseRate)
            }
            
            // Check if exiting danger zone
            if (newLevel.severity <= AlertLevel.CAUTION.severity && previousLevel.severity > AlertLevel.CAUTION.severity) {
                onDangerZoneExited?.invoke()
            }
            
            // Trigger alerts for elevated levels
            if (newLevel.severity >= AlertLevel.CAUTION.severity) {
                triggerAlert(newLevel, doseRate)
            } else {
                stopAlert()
            }
        } else if (newLevel.severity >= AlertLevel.DANGER.severity) {
            // For ongoing danger/critical, continue alerting
            val now = System.currentTimeMillis()
            if (now - lastAlertTimeMs > alertCooldownMs) {
                triggerAlert(newLevel, doseRate)
            }
        }
    }

    private fun determineAlertLevel(doseRate: Float): AlertLevel {
        return when {
            doseRate >= criticalThreshold -> AlertLevel.CRITICAL
            doseRate >= dangerThreshold -> AlertLevel.DANGER
            doseRate >= warningThreshold -> AlertLevel.WARNING
            doseRate >= cautionThreshold -> AlertLevel.CAUTION
            else -> AlertLevel.NORMAL
        }
    }

    private fun triggerAlert(level: AlertLevel, doseRate: Float) {
        lastAlertTimeMs = System.currentTimeMillis()
        isAlertActive = true
        
        // Sound alert
        if (soundEnabled && level.severity >= AlertLevel.WARNING.severity) {
            playAlertSound(level)
        }
        
        // Vibration alert
        if (vibrationEnabled) {
            triggerVibration(level)
        }
        
        // Notification
        if (notificationsEnabled) {
            showNotification(level, doseRate)
        }
    }

    private fun playAlertSound(level: AlertLevel) {
        stopSound()
        
        try {
            val soundUri = when (level) {
                AlertLevel.CRITICAL -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                AlertLevel.DANGER -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                AlertLevel.WARNING -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = level == AlertLevel.CRITICAL
                prepare()
                start()
            }
            
            // Stop non-critical sounds after a duration
            if (level != AlertLevel.CRITICAL) {
                handler.postDelayed({ stopSound() }, level.soundDurationMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSound() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    private fun triggerVibration(level: AlertLevel) {
        val pattern = level.vibrationPattern
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, if (level == AlertLevel.CRITICAL) 0 else -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, if (level == AlertLevel.CRITICAL) 0 else -1)
        }
    }

    private fun showNotification(level: AlertLevel, doseRate: Float) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_radiation_alert", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(level.title)
            .setContentText("Radiation: %.3f µSv/h - ${level.message}".format(doseRate))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${level.message}\n\nCurrent reading: %.3f µSv/h\n${level.advice}"
                    .format(doseRate)
            ))
            .setPriority(level.notificationPriority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(ContextCompat.getColor(context, level.colorRes))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .also { builder ->
                if (level.severity >= AlertLevel.DANGER.severity) {
                    builder.setOngoing(true)
                    builder.setVibrate(level.vibrationPattern)
                }
            }
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun stopAlert() {
        isAlertActive = false
        stopSound()
        vibrator.cancel()
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun getCurrentAlertLevel() = currentLevel

    fun isInDangerZone() = currentLevel.severity >= AlertLevel.DANGER.severity

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radiation Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for elevated radiation levels"
                enableVibration(true)
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.pro_red)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun destroy() {
        stopAlert()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Get description of current thresholds for display.
     */
    fun getThresholdDescription(): String {
        return """
            Alert Thresholds:
            • Normal: < %.2f µSv/h
            • Caution: %.2f - %.2f µSv/h
            • Warning: %.2f - %.2f µSv/h
            • Danger: %.2f - %.2f µSv/h
            • Critical: > %.2f µSv/h
        """.trimIndent().format(
            cautionThreshold,
            cautionThreshold, warningThreshold,
            warningThreshold, dangerThreshold,
            dangerThreshold, criticalThreshold,
            criticalThreshold
        )
    }

    enum class AlertLevel(
        val severity: Int,
        val title: String,
        val message: String,
        val advice: String,
        val colorRes: Int,
        val vibrationPattern: LongArray,
        val soundDurationMs: Long,
        val notificationPriority: Int
    ) {
        NORMAL(
            severity = 0,
            title = "Normal",
            message = "Radiation levels are normal",
            advice = "No action required.",
            colorRes = R.color.pro_green,
            vibrationPattern = longArrayOf(0),
            soundDurationMs = 0,
            notificationPriority = NotificationCompat.PRIORITY_LOW
        ),
        CAUTION(
            severity = 1,
            title = "⚠️ Caution",
            message = "Slightly elevated radiation detected",
            advice = "This may be normal for certain environments. Monitor the readings.",
            colorRes = R.color.pro_yellow,
            vibrationPattern = longArrayOf(0, 200),
            soundDurationMs = 1000,
            notificationPriority = NotificationCompat.PRIORITY_DEFAULT
        ),
        WARNING(
            severity = 2,
            title = "⚠️ Warning",
            message = "Elevated radiation levels",
            advice = "Consider limiting exposure time. Investigate the source.",
            colorRes = R.color.pro_yellow,
            vibrationPattern = longArrayOf(0, 200, 100, 200),
            soundDurationMs = 2000,
            notificationPriority = NotificationCompat.PRIORITY_HIGH
        ),
        DANGER(
            severity = 3,
            title = "🚨 DANGER",
            message = "High radiation levels detected!",
            advice = "Leave the area immediately. Report to authorities if unexpected.",
            colorRes = R.color.pro_red,
            vibrationPattern = longArrayOf(0, 300, 100, 300, 100, 300),
            soundDurationMs = 5000,
            notificationPriority = NotificationCompat.PRIORITY_MAX
        ),
        CRITICAL(
            severity = 4,
            title = "☢️ CRITICAL",
            message = "EXTREMELY HIGH RADIATION!",
            advice = "EVACUATE IMMEDIATELY! Seek medical attention if exposed.",
            colorRes = R.color.pro_red,
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500),
            soundDurationMs = Long.MAX_VALUE,
            notificationPriority = NotificationCompat.PRIORITY_MAX
        )
    }

    companion object {
        private const val CHANNEL_ID = "radiation_alerts"
        private const val NOTIFICATION_ID = 2001
    }
}
