package com.radiacode.ble

import android.content.Context
import kotlin.math.abs

/**
 * Intelligence Engine for Radiation Monitoring
 * 
 * Provides:
 * - Anomaly detection alerts
 * - Predictive trending
 * - Pattern recognition
 * - Smart recommendations
 */
object IntelligenceEngine {

    /**
     * Analyze recent readings and return intelligence insights.
     */
    fun analyzeReadings(context: Context, deviceId: String? = null): IntelligenceReport {
        val readings = if (deviceId != null) {
            Prefs.getDeviceRecentReadings(context, deviceId, 120)
        } else {
            Prefs.getRecentReadings(context, 120)
        }
        
        if (readings.size < 10) {
            return IntelligenceReport(
                hasEnoughData = false,
                anomalies = emptyList(),
                predictions = emptyList(),
                alerts = emptyList(),
                summary = "Collecting data... Need at least 10 readings for analysis."
            )
        }
        
        val doseValues = readings.map { it.uSvPerHour }
        val cpsValues = readings.map { it.cps }
        
        // Calculate statistics
        val doseStats = StatisticsCalculator.calculateStatistics(doseValues)
        val cpsStats = StatisticsCalculator.calculateStatistics(cpsValues)
        
        // Detect anomalies
        val anomalies = mutableListOf<AnomalyReport>()
        
        val doseAnomalies = StatisticsCalculator.detectAnomaliesZScore(doseValues, 2.5f)
        doseAnomalies.forEach { index ->
            val reading = readings.getOrNull(index)
            if (reading != null) {
                val zScore = StatisticsCalculator.calculateZScore(reading.uSvPerHour, doseStats.mean, doseStats.stdDev)
                anomalies.add(AnomalyReport(
                    type = AnomalyType.DOSE_SPIKE,
                    timestamp = reading.timestampMs,
                    value = reading.uSvPerHour,
                    expectedRange = "${String.format("%.3f", doseStats.mean - 2 * doseStats.stdDev)} - ${String.format("%.3f", doseStats.mean + 2 * doseStats.stdDev)} μSv/h",
                    severity = if (abs(zScore) > 3) AnomalySeverity.HIGH else AnomalySeverity.MEDIUM,
                    description = "Dose rate ${if (zScore > 0) "above" else "below"} normal range (z=${String.format("%.1f", zScore)})"
                ))
            }
        }
        
        val cpsAnomalies = StatisticsCalculator.detectAnomaliesZScore(cpsValues, 2.5f)
        cpsAnomalies.forEach { index ->
            val reading = readings.getOrNull(index)
            if (reading != null) {
                val zScore = StatisticsCalculator.calculateZScore(reading.cps, cpsStats.mean, cpsStats.stdDev)
                anomalies.add(AnomalyReport(
                    type = AnomalyType.CPS_SPIKE,
                    timestamp = reading.timestampMs,
                    value = reading.cps,
                    expectedRange = "${String.format("%.1f", cpsStats.mean - 2 * cpsStats.stdDev)} - ${String.format("%.1f", cpsStats.mean + 2 * cpsStats.stdDev)} cps",
                    severity = if (abs(zScore) > 3) AnomalySeverity.HIGH else AnomalySeverity.MEDIUM,
                    description = "Count rate ${if (zScore > 0) "above" else "below"} normal range (z=${String.format("%.1f", zScore)})"
                ))
            }
        }
        
        // Trend analysis
        val doseTrend = StatisticsCalculator.calculateTrend(doseValues)
        val cpsTrend = StatisticsCalculator.calculateTrend(cpsValues)
        
        // Predictions
        val predictions = mutableListOf<PredictionReport>()
        
        val nextDose = StatisticsCalculator.predictNextValue(doseValues)
        val nextCps = StatisticsCalculator.predictNextValue(cpsValues)
        
        predictions.add(PredictionReport(
            type = PredictionType.NEXT_DOSE,
            predictedValue = nextDose,
            confidence = doseTrend.rSquared * 100,
            description = "Expected dose rate: ${String.format("%.3f", nextDose)} μSv/h"
        ))
        
        predictions.add(PredictionReport(
            type = PredictionType.NEXT_CPS,
            predictedValue = nextCps,
            confidence = cpsTrend.rSquared * 100,
            description = "Expected count rate: ${String.format("%.1f", nextCps)} cps"
        ))
        
        // Generate alerts
        val alerts = mutableListOf<AlertReport>()
        
        // Alert for increasing trend
        val doseTrendDesc = StatisticsCalculator.getTrendDescription(doseTrend.slope, doseStats)
        if (doseTrendDesc.direction == TrendDirection.INCREASING && 
            doseTrendDesc.strength >= TrendStrength.MODERATE) {
            alerts.add(AlertReport(
                type = AlertType.RISING_TREND,
                severity = when (doseTrendDesc.strength) {
                    TrendStrength.VERY_STRONG -> AlertSeverity.HIGH
                    TrendStrength.STRONG -> AlertSeverity.MEDIUM
                    else -> AlertSeverity.LOW
                },
                title = "Rising Dose Rate",
                message = "Dose rate is increasing at ${String.format("%.1f", doseTrendDesc.percentChangePerUnit)}% per reading"
            ))
        }
        
        // Alert for high variability
        if (doseStats.coefficientOfVariation > 50) {
            alerts.add(AlertReport(
                type = AlertType.HIGH_VARIABILITY,
                severity = AlertSeverity.MEDIUM,
                title = "High Variability",
                message = "Dose readings show high variability (CV=${String.format("%.1f", doseStats.coefficientOfVariation)}%)"
            ))
        }
        
        // Alert if current reading is elevated
        val lastReading = readings.lastOrNull()
        if (lastReading != null) {
            val currentZScore = StatisticsCalculator.calculateZScore(lastReading.uSvPerHour, doseStats.mean, doseStats.stdDev)
            if (currentZScore > 2) {
                alerts.add(AlertReport(
                    type = AlertType.ELEVATED_READING,
                    severity = if (currentZScore > 3) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                    title = "Elevated Reading",
                    message = "Current reading is ${String.format("%.1f", currentZScore)} standard deviations above average"
                ))
            }
        }
        
        // Generate summary
        val summary = buildSummary(doseStats, cpsStats, doseTrendDesc, anomalies.size, alerts.size)
        
        return IntelligenceReport(
            hasEnoughData = true,
            anomalies = anomalies.sortedByDescending { it.timestamp },
            predictions = predictions,
            alerts = alerts.sortedByDescending { it.severity },
            summary = summary,
            doseStatistics = doseStats,
            cpsStatistics = cpsStats,
            doseTrend = doseTrend,
            cpsTrend = cpsTrend
        )
    }
    
    private fun buildSummary(
        doseStats: Statistics,
        cpsStats: Statistics,
        doseTrendDesc: TrendDescription,
        anomalyCount: Int,
        alertCount: Int
    ): String {
        val parts = mutableListOf<String>()
        
        // Overall status
        parts.add("Avg: ${String.format("%.3f", doseStats.mean)} μSv/h (${String.format("%.1f", cpsStats.mean)} cps)")
        
        // Trend
        val trendWord = when (doseTrendDesc.direction) {
            TrendDirection.INCREASING -> "↗ Rising"
            TrendDirection.DECREASING -> "↘ Falling"
            TrendDirection.STABLE -> "→ Stable"
        }
        parts.add("Trend: $trendWord")
        
        // Anomalies
        if (anomalyCount > 0) {
            parts.add("⚠ $anomalyCount anomalies detected")
        }
        
        // Alerts
        if (alertCount > 0) {
            parts.add("🔔 $alertCount active alerts")
        }
        
        return parts.joinToString(" | ")
    }
}

/**
 * Complete intelligence analysis report.
 */
data class IntelligenceReport(
    val hasEnoughData: Boolean,
    val anomalies: List<AnomalyReport>,
    val predictions: List<PredictionReport>,
    val alerts: List<AlertReport>,
    val summary: String,
    val doseStatistics: Statistics? = null,
    val cpsStatistics: Statistics? = null,
    val doseTrend: TrendResult? = null,
    val cpsTrend: TrendResult? = null
)

/**
 * Anomaly detection report.
 */
data class AnomalyReport(
    val type: AnomalyType,
    val timestamp: Long,
    val value: Float,
    val expectedRange: String,
    val severity: AnomalySeverity,
    val description: String
)

enum class AnomalyType {
    DOSE_SPIKE, CPS_SPIKE, SUDDEN_DROP, PATTERN_BREAK
}

enum class AnomalySeverity {
    LOW, MEDIUM, HIGH
}

/**
 * Prediction report.
 */
data class PredictionReport(
    val type: PredictionType,
    val predictedValue: Float,
    val confidence: Float,
    val description: String
)

enum class PredictionType {
    NEXT_DOSE, NEXT_CPS, TIME_TO_THRESHOLD
}

/**
 * Alert report.
 */
data class AlertReport(
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String
)

enum class AlertType {
    RISING_TREND, FALLING_TREND, HIGH_VARIABILITY, ELEVATED_READING, THRESHOLD_BREACH
}

enum class AlertSeverity {
    LOW, MEDIUM, HIGH
}
