package com.radiacode.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Evaluates smart alerts against recent readings and fires notifications.
 * 
 * This evaluator supports:
 * - Threshold-based alerts (above/below fixed value)
 * - Statistical alerts (outside N standard deviations from mean)
 * - Duration requirements (condition must persist for X seconds)
 * - Cooldown periods (minimum time between repeated alerts)
 */
class AlertEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "AlertEvaluator"
        private const val ALERT_NOTIF_BASE_ID = 10000
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Track ongoing alert states (for duration tracking)
    private val alertStartTimes = mutableMapOf<String, Long>()
    
    init {
        // Ensure notification channel exists on initialization
        ensureNotificationChannel()
    }
    
    /**
     * Ensure the alert notification channel exists.
     * Called on init and can be called to refresh channel settings.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = AlertConfigActivity.NOTIFICATION_CHANNEL_ID
            
            // Check if channel already exists
            val existingChannel = notificationManager.getNotificationChannel(channelId)
            if (existingChannel != null) {
                Log.d(TAG, "Notification channel $channelId already exists")
                return
            }
            
            // Create channel with appropriate sound settings
            val enableSystemSound = Prefs.isNotificationSystemSoundEnabled(context)
            val soundUri = if (enableSystemSound) {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            } else {
                null
            }
            
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            
            val channel = NotificationChannel(
                channelId,
                "Radiation Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when radiation levels exceed configured thresholds"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(soundUri, audioAttributes)
            }
            
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Created notification channel $channelId (systemSound=$enableSystemSound)")
        }
    }

    /**
     * Evaluate all enabled alerts against current readings.
     * Called after each new reading is received.
     */
    fun evaluate(currentDose: Float, currentCps: Float, recentReadings: List<Prefs.LastReading>) {
        val alerts = Prefs.getSmartAlerts(context).filter { it.enabled }
        if (alerts.isEmpty()) {
            Log.d(TAG, "No enabled alerts to evaluate")
            return
        }

        val now = System.currentTimeMillis()
        Log.d(TAG, "Evaluating ${alerts.size} alerts. Dose=$currentDose, CPS=$currentCps")

        for (alert in alerts) {
            val isTriggered = evaluateAlert(alert, currentDose, currentCps, recentReadings)
            Log.d(TAG, "Alert '${alert.name}': metric=${alert.metric}, condition=${alert.condition}, threshold=${alert.threshold}, triggered=$isTriggered")
            
            if (isTriggered) {
                // Track start time for duration check
                if (!alertStartTimes.containsKey(alert.id)) {
                    alertStartTimes[alert.id] = now
                    Log.d(TAG, "Alert '${alert.name}' condition first met, starting duration timer")
                }
                
                val startTime = alertStartTimes[alert.id] ?: now

                // Persist active state so UI can render immediately (even before duration requirement is met)
                try {
                    Prefs.upsertActiveAlert(
                        context,
                        Prefs.ActiveAlertState(
                            alertId = alert.id,
                            alertName = alert.name,
                            metric = alert.metric,
                            color = alert.color,
                            severity = alert.severity,
                            windowStartMs = startTime,
                            lastSeenMs = now
                        )
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to persist active alert state", t)
                }

                val durationMs = now - startTime
                val requiredDurationMs = alert.durationSeconds * 1000L
                Log.d(TAG, "Alert '${alert.name}': duration ${durationMs}ms / ${requiredDurationMs}ms required")
                
                // Check if duration requirement is met
                if (durationMs >= requiredDurationMs) {
                    // Check cooldown
                    val timeSinceLastTrigger = now - alert.lastTriggeredMs
                    Log.d(TAG, "Alert '${alert.name}': cooldown check - ${timeSinceLastTrigger}ms since last, need ${alert.cooldownMs}ms")
                    if (timeSinceLastTrigger >= alert.cooldownMs) {
                        // Fire the alert! Pass in the duration window start time
                        Log.d(TAG, "FIRING ALERT '${alert.name}'!")
                        fireAlert(alert, currentDose, currentCps, startTime)
                        
                        // Update last triggered time
                        val updatedAlert = alert.copy(lastTriggeredMs = now)
                        Prefs.updateSmartAlert(context, updatedAlert)
                    }
                }
            } else {
                // Condition no longer met, reset start time
                if (alertStartTimes.containsKey(alert.id)) {
                    Log.d(TAG, "Alert '${alert.name}' condition no longer met, resetting timer")
                }
                alertStartTimes.remove(alert.id)

                // Clear persisted active state
                try {
                    Prefs.removeActiveAlert(context, alert.id)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun evaluateAlert(
        alert: Prefs.SmartAlert, 
        currentDose: Float, 
        currentCps: Float,
        recentReadings: List<Prefs.LastReading>
    ): Boolean {
        val currentValue = when (alert.metric) {
            "dose" -> currentDose.toDouble()
            "count" -> currentCps.toDouble()
            else -> return false
        }

        return when (alert.condition) {
            "above" -> currentValue > alert.threshold
            "below" -> currentValue < alert.threshold
            "outside_sigma" -> {
                // Calculate statistics from recent readings
                val values = when (alert.metric) {
                    "dose" -> recentReadings.map { it.uSvPerHour.toDouble() }
                    "count" -> recentReadings.map { it.cps.toDouble() }
                    else -> emptyList()
                }
                
                if (values.size < 10) {
                    // Not enough data for statistical analysis
                    false
                } else {
                    val mean = values.average()
                    val variance = values.map { (it - mean) * (it - mean) }.average()
                    val stdDev = sqrt(variance)
                    
                    if (stdDev == 0.0) {
                        false
                    } else {
                        val zScore = (currentValue - mean) / stdDev
                        kotlin.math.abs(zScore) > alert.sigma
                    }
                }
            }
            else -> false
        }
    }

    private fun fireAlert(alert: Prefs.SmartAlert, currentDose: Float, currentCps: Float, durationWindowStartMs: Long) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "Firing alert: ${alert.name}")
        
        // Speak alert using VEGA TTS FIRST (starts async, runs parallel with notification)
        if (alert.voiceEnabled && Prefs.isVegaTtsEnabled(context)) {
            Log.d(TAG, "VegaTTS: Initiating voice alert NOW")
            val currentValue = if (alert.metric == "dose") currentDose.toDouble() else currentCps.toDouble()
            val announcement = VegaTTS.generateAlertAnnouncement(
                alertName = alert.name,
                metric = alert.metric,
                currentValue = currentValue,
                threshold = alert.threshold,
                condition = alert.condition,
                severity = alert.severity,
                durationSeconds = alert.durationSeconds
            )
            Log.d(TAG, "VegaTTS: Announcement text: $announcement")
            VegaTTS.speak(context, announcement)
        }
        
        // Play alert sound
        SoundManager.play(context, Prefs.SoundType.ALARM)

        // Record the alert event for chart visualization
        // durationWindowStartMs = when the condition first became true
        // now = when the alert fires (notification sent)
        // cooldownEndMs = when the cooldown period ends
        val alertEvent = Prefs.AlertEvent(
            alertId = alert.id,
            alertName = alert.name,
            triggerTimestampMs = now,
            durationWindowStartMs = durationWindowStartMs,
            cooldownEndMs = now + alert.cooldownMs,
            metric = alert.metric,
            color = alert.color,  // Already a string like "amber"
            severity = alert.severity,  // Already a string like "medium"
            thresholdValue = alert.threshold,
            thresholdUnit = alert.thresholdUnit  // Already a string like "usv_h"
        )
        Prefs.addAlertEvent(context, alertEvent)
        Log.d(TAG, "Alert event recorded: trigger=${now}, durationStart=${durationWindowStartMs}, cooldownEnd=${now + alert.cooldownMs}")

        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No notification permission, skipping alert")
                return
            }
        }

        // Build notification content
        val metricLabel = if (alert.metric == "dose") "Dose" else "Count"
        val currentValueText = when (alert.metric) {
            "dose" -> "%.3f μSv/h".format(currentDose)
            "count" -> "%.1f cps".format(currentCps)
            else -> ""
        }
        
        val conditionText = when (alert.condition) {
            "above" -> "above ${alert.threshold}"
            "below" -> "below ${alert.threshold}"
            "outside_sigma" -> "outside ${alert.sigma.toInt()}σ"
            else -> alert.condition
        }

        val title = "${alert.getSeverityEnum().icon} ${alert.name}"
        val text = "$metricLabel $conditionText for ${alert.durationSeconds}s\nCurrent: $currentValueText"

        // Create intent to open app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification - sound is controlled by the channel, not the builder
        val notification = NotificationCompat.Builder(context, AlertConfigActivity.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        // Use unique ID based on alert ID
        val notifId = ALERT_NOTIF_BASE_ID + kotlin.math.abs(alert.id.hashCode() % 1000)
        notificationManager.notify(notifId, notification)
        Log.d(TAG, "Posted notification ID $notifId for alert '${alert.name}'")
    }

    /**
     * Clear all tracking state (call when service stops)
     */
    fun reset() {
        alertStartTimes.clear()
        statisticalTriggerCooldowns.clear()
    }
    
    /**
     * Get count of alerts that are currently in "triggered" state (condition met, duration tracking).
     * This is useful for showing alert status in notifications.
     */
    fun getActiveAlertCount(): Int {
        return alertStartTimes.size
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICAL ALERT EVALUATION (VEGA Intelligence)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Cooldown tracking for statistical triggers
    private val statisticalTriggerCooldowns = mutableMapOf<String, Long>()
    private val DEFAULT_STATISTICAL_COOLDOWN_MS = 30_000L  // 30 seconds between same type triggers
    
    /**
     * Evaluate statistical alerts from VEGA engine snapshots.
     * This runs alongside classic threshold alerts for comprehensive coverage.
     */
    fun evaluateStatistical(
        doseSnapshot: StatisticalEngine.AnalysisSnapshot,
        cpsSnapshot: StatisticalEngine.AnalysisSnapshot,
        engine: StatisticalEngine
    ) {
        // Load statistical alert configuration from preferences
        val config = loadStatisticalConfig()
        if (!config.anyEnabled()) {
            return  // No statistical alerts enabled
        }
        
        // Get triggers from the engine
        val triggers = engine.evaluateTriggers(doseSnapshot, cpsSnapshot, config)
        
        val now = System.currentTimeMillis()
        
        for (trigger in triggers) {
            val triggerKey = "${trigger.type}_${trigger.message.hashCode()}"
            val lastTriggerTime = statisticalTriggerCooldowns[triggerKey] ?: 0L
            
            // Check cooldown
            if (now - lastTriggerTime < DEFAULT_STATISTICAL_COOLDOWN_MS) {
                Log.d(TAG, "Statistical trigger on cooldown: ${trigger.type}")
                continue
            }
            
            // Fire the statistical alert
            fireStatisticalAlert(trigger, doseSnapshot, cpsSnapshot)
            statisticalTriggerCooldowns[triggerKey] = now
        }
    }
    
    /**
     * Load statistical alert configuration from preferences.
     */
    private fun loadStatisticalConfig(): StatisticalEngine.StatisticalAlertConfig {
        // Extract user-configured "above" thresholds for dose alerts
        val userAlerts = Prefs.getSmartAlerts(context)
            .filter { it.enabled && it.metric == "dose" && it.condition == "above" }
            .map { it.threshold.toFloat() }
            .distinct()
            .sorted()
        
        return StatisticalEngine.StatisticalAlertConfig(
            zScoreEnabled = Prefs.isStatisticalZScoreEnabled(context),
            zScoreSigmaThreshold = Prefs.getStatisticalZScoreSigma(context),
            rocEnabled = Prefs.isStatisticalRocEnabled(context),
            rocThresholdPercent = Prefs.getStatisticalRocThreshold(context),
            cusumEnabled = Prefs.isStatisticalCusumEnabled(context),
            forecastEnabled = Prefs.isStatisticalForecastEnabled(context),
            forecastThreshold = Prefs.getStatisticalForecastThreshold(context),
            predictiveCrossingEnabled = Prefs.isStatisticalPredictiveCrossingEnabled(context),
            predictiveCrossingWarningSeconds = Prefs.getStatisticalPredictiveCrossingSeconds(context),
            userAlertThresholds = userAlerts
        )
    }
    
    /**
     * Fire a statistical alert with VEGA voice and notification.
     */
    private fun fireStatisticalAlert(
        trigger: StatisticalEngine.StatisticalTrigger,
        doseSnapshot: StatisticalEngine.AnalysisSnapshot,
        cpsSnapshot: StatisticalEngine.AnalysisSnapshot
    ) {
        Log.d(TAG, "Firing statistical alert: ${trigger.type} - ${trigger.message}")
        
        // Generate VEGA announcement
        if (Prefs.isVegaTtsEnabled(context) && Prefs.isStatisticalVoiceEnabled(context)) {
            val announcement = generateStatisticalAnnouncement(trigger, doseSnapshot, cpsSnapshot)
            VegaTTS.speak(context, announcement)
        }
        
        // Play alert sound based on severity
        when (trigger.severity) {
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.CRITICAL,
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.ALERT -> {
                SoundManager.play(context, Prefs.SoundType.ALARM)
            }
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.WARNING -> {
                SoundManager.play(context, Prefs.SoundType.ANOMALY)
            }
            else -> {
                // INFO level - no sound, just notification
            }
        }
        
        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        
        // Build notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val title = when (trigger.severity) {
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.CRITICAL -> "🚨 VEGA Critical Alert"
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.ALERT -> "⚠️ VEGA Alert"
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.WARNING -> "📊 VEGA Warning"
            StatisticalEngine.StatisticalTrigger.TriggerSeverity.INFO -> "📈 VEGA Insight"
        }
        
        val text = trigger.message
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            trigger.type.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, AlertConfigActivity.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$text\n\n" +
                "Dose: ${String.format("%.3f", doseSnapshot.currentValue)} µSv/h (z=${String.format("%.1f", doseSnapshot.zScore.zScore)}σ)\n" +
                "Baseline: ${String.format("%.3f", doseSnapshot.baseline.mean)} ± ${String.format("%.3f", doseSnapshot.baseline.standardDeviation)} µSv/h\n" +
                "Confidence: ${String.format("%.1f", trigger.confidence * 100)}%"
            ))
            .setPriority(when (trigger.severity) {
                StatisticalEngine.StatisticalTrigger.TriggerSeverity.CRITICAL -> NotificationCompat.PRIORITY_MAX
                StatisticalEngine.StatisticalTrigger.TriggerSeverity.ALERT -> NotificationCompat.PRIORITY_HIGH
                else -> NotificationCompat.PRIORITY_DEFAULT
            })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val notifId = ALERT_NOTIF_BASE_ID + 5000 + kotlin.math.abs(trigger.type.hashCode() % 1000)
        notificationManager.notify(notifId, notification)
    }
    
    /**
     * Generate VEGA-style announcement for statistical alerts.
     */
    private fun generateStatisticalAnnouncement(
        trigger: StatisticalEngine.StatisticalTrigger,
        doseSnapshot: StatisticalEngine.AnalysisSnapshot,
        cpsSnapshot: StatisticalEngine.AnalysisSnapshot
    ): String {
        return when (trigger.type) {
            StatisticalEngine.StatisticalTrigger.TriggerType.ZSCORE_ANOMALY -> {
                val sigma = doseSnapshot.zScore.sigmaLevel
                val direction = when (doseSnapshot.zScore.anomalyDirection) {
                    StatisticalEngine.ZScoreResult.AnomalyDirection.ABOVE -> "above"
                    StatisticalEngine.ZScoreResult.AnomalyDirection.BELOW -> "below"
                    else -> "deviating from"
                }
                when (sigma) {
                    4 -> "Critical statistical anomaly. Dose rate is ${sigma} sigma $direction baseline. This is extremely rare. Immediate attention recommended."
                    3 -> "Statistical anomaly detected with high confidence. Dose rate is ${sigma} sigma $direction your established normal. Attention advised."
                    2 -> "I have detected a statistical deviation. Dose rate is ${sigma} sigma $direction baseline. Continued monitoring recommended."
                    else -> "Observing an interesting pattern. Dose rate is slightly $direction normal range."
                }
            }
            
            StatisticalEngine.StatisticalTrigger.TriggerType.RATE_OF_CHANGE -> {
                val trend = doseSnapshot.rateOfChange.trend
                val rate = kotlin.math.abs(doseSnapshot.rateOfChange.ratePercentPerSecond)
                when (trend) {
                    StatisticalEngine.RateOfChangeResult.TrendDirection.RISING -> 
                        "Dose rate increasing at ${String.format("%.1f", rate)} percent per second. You may be approaching a source."
                    StatisticalEngine.RateOfChangeResult.TrendDirection.FALLING -> 
                        "Dose rate decreasing at ${String.format("%.1f", rate)} percent per second. You are moving away from elevated levels."
                    else -> "Rate of change detected."
                }
            }
            
            StatisticalEngine.StatisticalTrigger.TriggerType.CUSUM_CHANGE -> {
                val direction = doseSnapshot.cusum.changeDirection
                when (direction) {
                    StatisticalEngine.CusumResult.ChangeDirection.INCREASE -> 
                        "I have detected a subtle but persistent shift. Background levels are trending upward. This change was too gradual for threshold alerts but is statistically significant."
                    StatisticalEngine.CusumResult.ChangeDirection.DECREASE -> 
                        "Environmental conditions have changed. Background levels have shifted downward. Recording new baseline parameters."
                    else -> "Change point detected in radiation levels."
                }
            }
            
            StatisticalEngine.StatisticalTrigger.TriggerType.FORECAST_THRESHOLD -> {
                val predicted = doseSnapshot.forecast60s.predictedValue
                "Predictive analysis indicates dose rate will reach ${String.format("%.3f", predicted)} microsieverts per hour within 60 seconds based on current trajectory."
            }
            
            StatisticalEngine.StatisticalTrigger.TriggerType.PREDICTIVE_CROSSING -> {
                // Use the message from the trigger which contains the time estimate
                trigger.message
            }
        }
    }
}

/**
 * Extension function to check if any statistical alerts are enabled.
 */
private fun StatisticalEngine.StatisticalAlertConfig.anyEnabled(): Boolean {
    return zScoreEnabled || rocEnabled || cusumEnabled || forecastEnabled || predictiveCrossingEnabled
}
