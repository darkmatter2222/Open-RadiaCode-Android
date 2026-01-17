package com.radiacode.ble

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    // Track ongoing alert states (for duration tracking)
    private val alertStartTimes = mutableMapOf<String, Long>()

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

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
    }

    /**
     * Clear all tracking state (call when service stops)
     */
    fun reset() {
        alertStartTimes.clear()
    }
    
    /**
     * Get count of alerts that are currently in "triggered" state (condition met, duration tracking).
     * This is useful for showing alert status in notifications.
     */
    fun getActiveAlertCount(): Int {
        return alertStartTimes.size
    }
}
