package com.radiacode.ble

import android.content.Context
import kotlin.math.abs

/**
 * Intelligence Engine for Radiation Monitoring
 * 
 * Provides:
 * - Anomaly detection alerts (active vs recent tracking)
 * - Predictive trending with confidence scores
 * - Pattern recognition and stability analysis
 * - Data quality assessment
 * - Background level estimation
 * - Smart recommendations
 */
object IntelligenceEngine {

    // Number of recent readings to consider as "active" anomaly window
    private const val ACTIVE_ANOMALY_WINDOW = 5
    
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
                summary = "Collecting data... Need at least 10 readings for analysis.",
                dataQuality = DataQuality.INSUFFICIENT,
                sampleCount = readings.size
            )
        }
        
        val doseValues = readings.map { it.uSvPerHour }
        val cpsValues = readings.map { it.cps }
        
        // Calculate statistics
        val doseStats = StatisticsCalculator.calculateStatistics(doseValues)
        val cpsStats = StatisticsCalculator.calculateStatistics(cpsValues)
        
        // Determine data quality based on sample count
        val dataQuality = when {
            readings.size >= 100 -> DataQuality.EXCELLENT
            readings.size >= 50 -> DataQuality.GOOD
            readings.size >= 20 -> DataQuality.FAIR
            else -> DataQuality.LIMITED
        }
        
        // Calculate stability indicator based on coefficient of variation
        val stability = when {
            doseStats.coefficientOfVariation < 15 -> StabilityLevel.STABLE
            doseStats.coefficientOfVariation < 35 -> StabilityLevel.VARIABLE
            else -> StabilityLevel.ERRATIC
        }
        
        // Estimate background level (use median as it's robust to spikes)
        val estimatedBackground = doseStats.median
        
        // Detect anomalies
        val anomalies = mutableListOf<AnomalyReport>()
        
        val doseAnomalyIndices = StatisticsCalculator.detectAnomaliesZScore(doseValues, 2.5f)
        doseAnomalyIndices.forEach { index ->
            val reading = readings.getOrNull(index)
            if (reading != null) {
                val zScore = StatisticsCalculator.calculateZScore(reading.uSvPerHour, doseStats.mean, doseStats.stdDev)
                val isActive = index >= readings.size - ACTIVE_ANOMALY_WINDOW
                anomalies.add(AnomalyReport(
                    type = AnomalyType.DOSE_SPIKE,
                    timestamp = reading.timestampMs,
                    value = reading.uSvPerHour,
                    expectedRange = "${String.format("%.3f", doseStats.mean - 2 * doseStats.stdDev)} - ${String.format("%.3f", doseStats.mean + 2 * doseStats.stdDev)} μSv/h",
                    severity = if (abs(zScore) > 3) AnomalySeverity.HIGH else AnomalySeverity.MEDIUM,
                    description = "Dose rate ${if (zScore > 0) "above" else "below"} normal range (z=${String.format("%.1f", zScore)})",
                    isActive = isActive
                ))
            }
        }
        
        val cpsAnomalyIndices = StatisticsCalculator.detectAnomaliesZScore(cpsValues, 2.5f)
        cpsAnomalyIndices.forEach { index ->
            val reading = readings.getOrNull(index)
            if (reading != null) {
                val zScore = StatisticsCalculator.calculateZScore(reading.cps, cpsStats.mean, cpsStats.stdDev)
                val isActive = index >= readings.size - ACTIVE_ANOMALY_WINDOW
                anomalies.add(AnomalyReport(
                    type = AnomalyType.CPS_SPIKE,
                    timestamp = reading.timestampMs,
                    value = reading.cps,
                    expectedRange = "${String.format("%.1f", cpsStats.mean - 2 * cpsStats.stdDev)} - ${String.format("%.1f", cpsStats.mean + 2 * cpsStats.stdDev)} cps",
                    severity = if (abs(zScore) > 3) AnomalySeverity.HIGH else AnomalySeverity.MEDIUM,
                    description = "Count rate ${if (zScore > 0) "above" else "below"} normal range (z=${String.format("%.1f", zScore)})",
                    isActive = isActive
                ))
            }
        }
        
        // Count active vs recent anomalies
        val activeAnomalyCount = anomalies.count { it.isActive }
        val recentAnomalyCount = anomalies.size
        
        // Trend analysis
        val doseTrend = StatisticsCalculator.calculateTrend(doseValues)
        val cpsTrend = StatisticsCalculator.calculateTrend(cpsValues)
        
        // Predictions
        val predictions = mutableListOf<PredictionReport>()
        
        val nextDose = StatisticsCalculator.predictNextValue(doseValues)
        val nextCps = StatisticsCalculator.predictNextValue(cpsValues)
        
        // Confidence level classification
        val doseConfidence = doseTrend.rSquared * 100
        val confidenceLevel = when {
            doseConfidence >= 70 -> ConfidenceLevel.HIGH
            doseConfidence >= 40 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        
        predictions.add(PredictionReport(
            type = PredictionType.NEXT_DOSE,
            predictedValue = nextDose,
            confidence = doseConfidence,
            confidenceLevel = confidenceLevel,
            description = "Expected dose rate: ${String.format("%.3f", nextDose)} μSv/h"
        ))
        
        predictions.add(PredictionReport(
            type = PredictionType.NEXT_CPS,
            predictedValue = nextCps,
            confidence = cpsTrend.rSquared * 100,
            confidenceLevel = when {
                cpsTrend.rSquared * 100 >= 70 -> ConfidenceLevel.HIGH
                cpsTrend.rSquared * 100 >= 40 -> ConfidenceLevel.MEDIUM
                else -> ConfidenceLevel.LOW
            },
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
        var currentZScore = 0f
        if (lastReading != null) {
            currentZScore = StatisticsCalculator.calculateZScore(lastReading.uSvPerHour, doseStats.mean, doseStats.stdDev)
            if (currentZScore > 2) {
                alerts.add(AlertReport(
                    type = AlertType.ELEVATED_READING,
                    severity = if (currentZScore > 3) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                    title = "Elevated Reading",
                    message = "Current reading is ${String.format("%.1f", currentZScore)} standard deviations above average"
                ))
            }
        }
        
        // Calculate current reading as percentage of background
        val currentVsBackground = if (estimatedBackground > 0.0001f && lastReading != null) {
            (lastReading.uSvPerHour / estimatedBackground * 100)
        } else {
            100f
        }
        
        // Generate summary
        val summary = buildSummary(doseStats, cpsStats, doseTrendDesc, activeAnomalyCount, recentAnomalyCount, alerts.size, stability)
        
        return IntelligenceReport(
            hasEnoughData = true,
            anomalies = anomalies.sortedByDescending { it.timestamp },
            predictions = predictions,
            alerts = alerts.sortedByDescending { it.severity },
            summary = summary,
            doseStatistics = doseStats,
            cpsStatistics = cpsStats,
            doseTrend = doseTrend,
            cpsTrend = cpsTrend,
            dataQuality = dataQuality,
            sampleCount = readings.size,
            stability = stability,
            estimatedBackground = estimatedBackground,
            currentVsBackground = currentVsBackground,
            activeAnomalyCount = activeAnomalyCount,
            recentAnomalyCount = recentAnomalyCount,
            currentZScore = currentZScore,
            doseTrendDescription = doseTrendDesc
        )
    }
    
    private fun buildSummary(
        doseStats: Statistics,
        cpsStats: Statistics,
        doseTrendDesc: TrendDescription,
        activeAnomalyCount: Int,
        recentAnomalyCount: Int,
        alertCount: Int,
        stability: StabilityLevel
    ): String {
        val parts = mutableListOf<String>()
        
        // Stability indicator
        val stabilityIcon = when (stability) {
            StabilityLevel.STABLE -> "●"
            StabilityLevel.VARIABLE -> "◐"
            StabilityLevel.ERRATIC -> "○"
        }
        parts.add("$stabilityIcon ${stability.name}")
        
        // Overall status
        parts.add("Avg: ${String.format("%.3f", doseStats.mean)} μSv/h")
        
        // Trend
        val trendWord = when (doseTrendDesc.direction) {
            TrendDirection.INCREASING -> "↗"
            TrendDirection.DECREASING -> "↘"
            TrendDirection.STABLE -> "→"
        }
        parts.add("Trend: $trendWord")
        
        // Anomalies (show active/recent distinction)
        if (activeAnomalyCount > 0) {
            parts.add("⚠ $activeAnomalyCount active")
        } else if (recentAnomalyCount > 0) {
            parts.add("$recentAnomalyCount recent")
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
    val cpsTrend: TrendResult? = null,
    // New enhanced fields
    val dataQuality: DataQuality = DataQuality.INSUFFICIENT,
    val sampleCount: Int = 0,
    val stability: StabilityLevel = StabilityLevel.VARIABLE,
    val estimatedBackground: Float = 0f,
    val currentVsBackground: Float = 100f,
    val activeAnomalyCount: Int = 0,
    val recentAnomalyCount: Int = 0,
    val currentZScore: Float = 0f,
    val doseTrendDescription: TrendDescription? = null
)

/**
 * Data quality classification based on sample count.
 */
enum class DataQuality {
    INSUFFICIENT,  // <10 samples
    LIMITED,       // 10-19 samples
    FAIR,          // 20-49 samples
    GOOD,          // 50-99 samples
    EXCELLENT      // 100+ samples
}

/**
 * Stability level based on coefficient of variation.
 */
enum class StabilityLevel {
    STABLE,    // CV < 15%
    VARIABLE,  // CV 15-35%
    ERRATIC    // CV > 35%
}

/**
 * Confidence level for predictions.
 */
enum class ConfidenceLevel {
    LOW,       // R² < 40%
    MEDIUM,    // R² 40-70%
    HIGH       // R² > 70%
}

/**
 * Anomaly detection report.
 */
data class AnomalyReport(
    val type: AnomalyType,
    val timestamp: Long,
    val value: Float,
    val expectedRange: String,
    val severity: AnomalySeverity,
    val description: String,
    val isActive: Boolean = false  // True if in the most recent readings
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
    val description: String,
    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.MEDIUM
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
