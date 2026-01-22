package com.radiacode.ble

import android.content.Context
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VEGA Statistical Intelligence Engine
 * 
 * Real-time statistical analysis for radiation monitoring with:
 * - Adaptive baseline learning (EWMA)
 * - Z-score anomaly detection
 * - Rate-of-change (ROC) detection
 * - CUSUM change-point detection
 * - Forecasting (Holt-Winters double exponential smoothing)
 * 
 * "I am quietly analyzing. I will speak when there is something worth saying."
 */
class StatisticalEngine(private val context: Context) {

    companion object {
        private const val TAG = "StatisticalEngine"
        
        // Default EWMA smoothing factors
        private const val DEFAULT_ALPHA = 0.1f  // Level smoothing (slow adaptation)
        private const val DEFAULT_BETA = 0.05f  // Trend smoothing
        
        // CUSUM parameters
        private const val DEFAULT_CUSUM_K = 0.5f  // Slack parameter (0.5 * stdDev)
        private const val DEFAULT_CUSUM_H = 4.0f  // Threshold parameter (4 * stdDev)
        
        // Minimum samples for statistical validity
        private const val MIN_SAMPLES_FOR_STATS = 10
        private const val MIN_SAMPLES_FOR_FORECAST = 5
        
        // Rolling buffer sizes
        private const val SHORT_WINDOW_SIZE = 30      // ~30 seconds at 1Hz
        private const val MEDIUM_WINDOW_SIZE = 300    // ~5 minutes
        private const val LONG_WINDOW_SIZE = 3600     // ~1 hour
        
        // Source proximity tracking (Feature #8)
        private const val PROXIMITY_BUFFER_SIZE = 10  // Track last 10 readings for approach analysis
        private const val MIN_APPROACHING_SAMPLES = 3  // Need at least 3 rising samples
        
        // Singleton instance for app-wide access
        @Volatile
        private var instance: StatisticalEngine? = null
        
        fun getInstance(context: Context): StatisticalEngine {
            return instance ?: synchronized(this) {
                instance ?: StatisticalEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Real-time baseline statistics with adaptive learning.
     */
    data class BaselineStats(
        val mean: Float = 0f,
        val variance: Float = 0f,
        val standardDeviation: Float = 0f,
        val sampleCount: Long = 0,
        val lastUpdateMs: Long = 0,
        val minValue: Float = Float.MAX_VALUE,
        val maxValue: Float = Float.MIN_VALUE
    ) {
        fun isValid(): Boolean = sampleCount >= MIN_SAMPLES_FOR_STATS && standardDeviation > 0.0001f
        
        companion object {
            val EMPTY = BaselineStats()
        }
    }

    /**
     * Z-score analysis result with confidence level.
     */
    data class ZScoreResult(
        val zScore: Float,
        val isAnomaly: Boolean,
        val anomalyDirection: AnomalyDirection,
        val confidencePercent: Float,  // How confident we are this is anomalous (0-100)
        val sigmaLevel: Int            // Which sigma band (1, 2, 3, or 0 if within normal)
    ) {
        enum class AnomalyDirection { ABOVE, BELOW, NORMAL }
        
        companion object {
            val NORMAL = ZScoreResult(0f, false, AnomalyDirection.NORMAL, 0f, 0)
        }
    }

    /**
     * Rate-of-change analysis result.
     */
    data class RateOfChangeResult(
        val ratePerSecond: Float,       // Change per second (absolute)
        val ratePercentPerSecond: Float, // Change per second (relative %)
        val acceleration: Float,         // Second derivative
        val trend: TrendDirection,
        val isSignificant: Boolean,
        val consecutiveDirection: Int    // How many consecutive readings in same direction
    ) {
        enum class TrendDirection { RISING, FALLING, STABLE }
        
        companion object {
            val STABLE = RateOfChangeResult(0f, 0f, 0f, TrendDirection.STABLE, false, 0)
        }
    }

    /**
     * CUSUM change-point detection result.
     */
    data class CusumResult(
        val cusumHigh: Float,           // Cumulative sum for upward changes
        val cusumLow: Float,            // Cumulative sum for downward changes
        val changeDetected: Boolean,
        val changeDirection: ChangeDirection,
        val changeConfidence: Float     // How strong the change signal is (0-1)
    ) {
        enum class ChangeDirection { INCREASE, DECREASE, NONE }
        
        companion object {
            val NONE = CusumResult(0f, 0f, false, ChangeDirection.NONE, 0f)
        }
    }

    /**
     * Forecast result with confidence interval.
     */
    data class ForecastResult(
        val predictedValue: Float,
        val lowerBound: Float,          // Lower confidence bound
        val upperBound: Float,          // Upper confidence bound
        val confidenceLevel: Float,     // e.g., 0.95 for 95%
        val horizonSeconds: Int,        // How far ahead this predicts
        val trend: TrendDirection       // Trend direction in forecast
    ) {
        enum class TrendDirection { RISING, FALLING, STABLE }
        
        fun getRange(): Float = upperBound - lowerBound
        
        companion object {
            val UNKNOWN = ForecastResult(0f, 0f, 0f, 0f, 0, TrendDirection.STABLE)
        }
    }

    /**
     * Cumulative dose tracking result (Tier 2 - Feature #7).
     * Tracks running total and projects time to reach dose limits.
     */
    data class CumulativeDoseResult(
        val totalMicroSv: Float,           // Total accumulated dose in µSv
        val sessionDurationMs: Long,       // How long this session has been running
        val currentRateMicroSvPerHour: Float, // Current dose rate
        val projectedDailyMicroSv: Float,  // Projected 24-hour total at current rate
        val hoursToLimit: Float,           // Hours until reaching daily limit
        val percentOfDailyLimit: Float     // Current % of configured daily limit
    ) {
        companion object {
            val EMPTY = CumulativeDoseResult(0f, 0L, 0f, 0f, Float.POSITIVE_INFINITY, 0f)
            const val DEFAULT_DAILY_LIMIT_USV = 50f  // 50 µSv/day is reasonable for public exposure
        }
        
        /**
         * Get human-readable time to limit string.
         */
        fun getTimeToLimitString(): String {
            return when {
                hoursToLimit.isInfinite() || hoursToLimit > 1000 -> "N/A"
                hoursToLimit < 1 -> "${(hoursToLimit * 60).toInt()} minutes"
                hoursToLimit < 24 -> "${hoursToLimit.toInt()} hours ${((hoursToLimit % 1) * 60).toInt()} min"
                else -> ">${(hoursToLimit / 24).toInt()} days"
            }
        }
    }

    /**
     * Source proximity estimation result (Tier 2 - Feature #8).
     * Uses inverse-square law to estimate distance to a point radiation source.
     * 
     * Note: This is an ESTIMATE only. Actual radiation fields can be complex
     * due to shielding, multiple sources, non-point sources, etc.
     */
    data class SourceProximityResult(
        val isApproaching: Boolean,            // Whether we're moving toward source
        val estimatedDistanceMeters: Float,    // Estimated current distance (if calculable)
        val confidenceLevel: Confidence,       // How confident we are in the estimate
        val approachRateMetersPerSec: Float,   // How fast we're approaching
        val predictedPeakDoseRate: Float,      // Predicted dose at closest approach
        val predictedTimeToClosest: Float,     // Seconds until closest approach
        val recommendation: String             // VEGA guidance text
    ) {
        enum class Confidence { NONE, LOW, MEDIUM, HIGH }
        
        companion object {
            val UNKNOWN = SourceProximityResult(
                isApproaching = false,
                estimatedDistanceMeters = Float.NaN,
                confidenceLevel = Confidence.NONE,
                approachRateMetersPerSec = 0f,
                predictedPeakDoseRate = 0f,
                predictedTimeToClosest = Float.NaN,
                recommendation = "Insufficient data for proximity estimation"
            )
        }
        
        /**
         * Get user-friendly distance string.
         */
        fun getDistanceString(): String {
            return when {
                estimatedDistanceMeters.isNaN() -> "Unknown"
                estimatedDistanceMeters < 1 -> "${(estimatedDistanceMeters * 100).toInt()} cm"
                else -> String.format("%.1f m", estimatedDistanceMeters)
            }
        }
    }

    /**
     * Complete statistical analysis snapshot.
     */
    data class AnalysisSnapshot(
        val timestampMs: Long,
        val currentValue: Float,
        val baseline: BaselineStats,
        val zScore: ZScoreResult,
        val rateOfChange: RateOfChangeResult,
        val cusum: CusumResult,
        val forecast30s: ForecastResult,
        val forecast60s: ForecastResult,
        val forecast300s: ForecastResult
    )

    /**
     * Statistical alert trigger.
     */
    data class StatisticalTrigger(
        val type: TriggerType,
        val severity: TriggerSeverity,
        val message: String,
        val confidence: Float,           // 0-1
        val detectedValue: Float,
        val thresholdValue: Float,
        val timestampMs: Long
    ) {
        enum class TriggerType {
            ZSCORE_ANOMALY,
            RATE_OF_CHANGE,
            CUSUM_CHANGE,
            FORECAST_THRESHOLD,
            PREDICTIVE_CROSSING     // Alert BEFORE threshold is hit
        }
        
        enum class TriggerSeverity {
            INFO, WARNING, ALERT, CRITICAL
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE - Rolling buffers for real-time analysis
    // ═══════════════════════════════════════════════════════════════════════════

    // Dose rate tracking
    private val doseReadings = RollingBuffer(LONG_WINDOW_SIZE)
    private var doseBaseline = BaselineStats.EMPTY
    private var doseCusumHigh = 0f
    private var doseCusumLow = 0f
    
    // Count rate tracking
    private val cpsReadings = RollingBuffer(LONG_WINDOW_SIZE)
    private var cpsBaseline = BaselineStats.EMPTY
    private var cpsCusumHigh = 0f
    private var cpsCusumLow = 0f
    
    // Holt-Winters state for dose
    private var doseLevel = 0f
    private var doseTrend = 0f
    private var doseHWInitialized = false
    
    // Holt-Winters state for CPS
    private var cpsLevel = 0f
    private var cpsTrend = 0f
    private var cpsHWInitialized = false
    
    // Rate of change tracking
    private var lastDoseReading: TimestampedValue? = null
    private var lastCpsReading: TimestampedValue? = null
    private var doseRocDirection = 0  // Consecutive same-direction count
    private var cpsRocDirection = 0
    
    // Cumulative dose tracking (Tier 2 - Feature #7)
    private var cumulativeDoseStartMs: Long = 0L
    private var cumulativeDoseMicroSv: Float = 0f  // Running total in µSv
    private var lastCumulativeDoseUpdateMs: Long = 0L
    
    // Source proximity tracking (Tier 2 - Feature #8)
    // Track recent readings for inverse-square analysis
    private val proximityReadings = ArrayDeque<TimestampedValue>(PROXIMITY_BUFFER_SIZE)

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add a new reading and perform real-time analysis.
     * Call this for every incoming reading from the device.
     * 
     * @return Complete analysis snapshot with all statistical results
     */
    @Synchronized
    fun addReading(doseRate: Float, cps: Float, timestampMs: Long = System.currentTimeMillis()): Pair<AnalysisSnapshot, AnalysisSnapshot> {
        // Add to rolling buffers
        doseReadings.add(TimestampedValue(doseRate, timestampMs))
        cpsReadings.add(TimestampedValue(cps, timestampMs))
        
        // Update cumulative dose (Feature #7)
        updateCumulativeDose(doseRate, timestampMs)
        
        // Update proximity buffer (Feature #8)
        updateProximityBuffer(doseRate, timestampMs)
        
        // Update baselines (EWMA)
        doseBaseline = updateBaseline(doseBaseline, doseRate, timestampMs)
        cpsBaseline = updateBaseline(cpsBaseline, cps, timestampMs)
        
        // Perform analysis for dose
        val doseZScore = calculateZScore(doseRate, doseBaseline)
        val doseRoc = calculateRateOfChange(doseRate, timestampMs, lastDoseReading, doseRocDirection, doseBaseline)
        val doseCusum = updateCusum(doseRate, doseBaseline, doseCusumHigh, doseCusumLow).also {
            doseCusumHigh = it.cusumHigh
            doseCusumLow = it.cusumLow
        }
        updateHoltWinters(doseRate, isDose = true)
        val doseForecast30 = forecast(30, isDose = true)
        val doseForecast60 = forecast(60, isDose = true)
        val doseForecast300 = forecast(300, isDose = true)
        
        // Update last reading and direction counter for dose
        doseRocDirection = updateDirectionCounter(doseRocDirection, doseRoc.trend)
        lastDoseReading = TimestampedValue(doseRate, timestampMs)
        
        // Perform analysis for CPS
        val cpsZScore = calculateZScore(cps, cpsBaseline)
        val cpsRoc = calculateRateOfChange(cps, timestampMs, lastCpsReading, cpsRocDirection, cpsBaseline)
        val cpsCusum = updateCusum(cps, cpsBaseline, cpsCusumHigh, cpsCusumLow).also {
            cpsCusumHigh = it.cusumHigh
            cpsCusumLow = it.cusumLow
        }
        updateHoltWinters(cps, isDose = false)
        val cpsForecast30 = forecast(30, isDose = false)
        val cpsForecast60 = forecast(60, isDose = false)
        val cpsForecast300 = forecast(300, isDose = false)
        
        // Update last reading and direction counter for CPS
        cpsRocDirection = updateDirectionCounter(cpsRocDirection, cpsRoc.trend)
        lastCpsReading = TimestampedValue(cps, timestampMs)
        
        val doseSnapshot = AnalysisSnapshot(
            timestampMs = timestampMs,
            currentValue = doseRate,
            baseline = doseBaseline,
            zScore = doseZScore,
            rateOfChange = doseRoc,
            cusum = doseCusum,
            forecast30s = doseForecast30,
            forecast60s = doseForecast60,
            forecast300s = doseForecast300
        )
        
        val cpsSnapshot = AnalysisSnapshot(
            timestampMs = timestampMs,
            currentValue = cps,
            baseline = cpsBaseline,
            zScore = cpsZScore,
            rateOfChange = cpsRoc,
            cusum = cpsCusum,
            forecast30s = cpsForecast30,
            forecast60s = cpsForecast60,
            forecast300s = cpsForecast300
        )
        
        return Pair(doseSnapshot, cpsSnapshot)
    }

    /**
     * Get the current baseline statistics for dose rate.
     */
    fun getDoseBaseline(): BaselineStats = doseBaseline

    /**
     * Get the current baseline statistics for count rate.
     */
    fun getCpsBaseline(): BaselineStats = cpsBaseline

    /**
     * Get recent dose readings for UI display.
     */
    fun getRecentDoseReadings(count: Int = SHORT_WINDOW_SIZE): List<TimestampedValue> {
        return doseReadings.getRecent(count)
    }

    /**
     * Get recent CPS readings for UI display.
     */
    fun getRecentCpsReadings(count: Int = SHORT_WINDOW_SIZE): List<TimestampedValue> {
        return cpsReadings.getRecent(count)
    }

    /**
     * Get forecast points for chart visualization.
     * Returns list of (relativeTimeSeconds, predictedValue, lowerBound, upperBound)
     */
    fun getForecastPoints(horizonSeconds: Int, intervalSeconds: Int = 5, isDose: Boolean): List<ForecastPoint> {
        val points = mutableListOf<ForecastPoint>()
        for (t in intervalSeconds..horizonSeconds step intervalSeconds) {
            val f = forecast(t, isDose)
            points.add(ForecastPoint(t, f.predictedValue, f.lowerBound, f.upperBound))
        }
        return points
    }
    
    /**
     * Get current cumulative dose information (Feature #7).
     * 
     * @param dailyLimitMicroSv The user's configured daily dose limit in µSv
     * @return CumulativeDoseResult with running total and projections
     */
    @Synchronized
    fun getCumulativeDose(dailyLimitMicroSv: Float = CumulativeDoseResult.DEFAULT_DAILY_LIMIT_USV): CumulativeDoseResult {
        val now = System.currentTimeMillis()
        val sessionDuration = if (cumulativeDoseStartMs > 0) now - cumulativeDoseStartMs else 0L
        
        // Calculate current rate from baseline or last reading
        val currentRate = if (doseBaseline.mean > 0) doseBaseline.mean else lastDoseReading?.value ?: 0f
        
        // Project 24-hour total based on current rate
        val projectedDaily = currentRate * 24f  // µSv/h * 24h = µSv/day
        
        // Calculate hours to reach daily limit
        val remaining = dailyLimitMicroSv - cumulativeDoseMicroSv
        val hoursToLimit = if (currentRate > 0 && remaining > 0) {
            remaining / currentRate
        } else {
            Float.POSITIVE_INFINITY
        }
        
        // Percentage of daily limit
        val percentOfLimit = (cumulativeDoseMicroSv / dailyLimitMicroSv) * 100f
        
        return CumulativeDoseResult(
            totalMicroSv = cumulativeDoseMicroSv,
            sessionDurationMs = sessionDuration,
            currentRateMicroSvPerHour = currentRate,
            projectedDailyMicroSv = projectedDaily,
            hoursToLimit = hoursToLimit,
            percentOfDailyLimit = percentOfLimit
        )
    }
    
    /**
     * Reset cumulative dose counter (e.g., at midnight or user request).
     */
    @Synchronized
    fun resetCumulativeDose() {
        cumulativeDoseMicroSv = 0f
        cumulativeDoseStartMs = System.currentTimeMillis()
        lastCumulativeDoseUpdateMs = cumulativeDoseStartMs
        Log.d(TAG, "Cumulative dose counter reset")
    }
    
    /**
     * Update cumulative dose based on time elapsed since last update.
     * Called internally by addReading.
     */
    private fun updateCumulativeDose(doseRate: Float, timestampMs: Long) {
        // Initialize if this is first reading
        if (cumulativeDoseStartMs == 0L) {
            cumulativeDoseStartMs = timestampMs
            lastCumulativeDoseUpdateMs = timestampMs
            return
        }
        
        // Calculate time delta in hours
        val deltaMs = timestampMs - lastCumulativeDoseUpdateMs
        if (deltaMs <= 0) return
        
        val deltaHours = deltaMs / (1000f * 60f * 60f)  // ms to hours
        
        // Accumulate dose: µSv/h * hours = µSv
        val doseAccumulated = doseRate * deltaHours
        cumulativeDoseMicroSv += doseAccumulated
        lastCumulativeDoseUpdateMs = timestampMs
    }
    
    /**
     * Update proximity tracking buffer.
     * Called internally by addReading.
     */
    private fun updateProximityBuffer(doseRate: Float, timestampMs: Long) {
        if (proximityReadings.size >= PROXIMITY_BUFFER_SIZE) {
            proximityReadings.removeFirst()
        }
        proximityReadings.addLast(TimestampedValue(doseRate, timestampMs))
    }
    
    /**
     * Estimate source proximity using inverse-square law analysis (Feature #8).
     * 
     * This analyzes the rate of change of dose readings to estimate:
     * 1. Whether you're approaching or moving away from a source
     * 2. Approximate distance (assuming point source)
     * 3. Predicted dose at closest approach
     * 
     * @param backgroundRate The known background radiation level (to subtract)
     * @return SourceProximityResult with estimates and recommendations
     */
    @Synchronized
    fun estimateSourceProximity(backgroundRate: Float = 0.1f): SourceProximityResult {
        val readings = proximityReadings.toList()
        
        // Need minimum samples for analysis
        if (readings.size < MIN_APPROACHING_SAMPLES) {
            return SourceProximityResult.UNKNOWN
        }
        
        // Check if we're consistently approaching (dose increasing)
        val recent = readings.takeLast(MIN_APPROACHING_SAMPLES)
        var approachingCount = 0
        for (i in 1 until recent.size) {
            if (recent[i].value > recent[i-1].value) {
                approachingCount++
            }
        }
        
        val isApproaching = approachingCount >= MIN_APPROACHING_SAMPLES - 1
        
        // Calculate rate of change
        val first = readings.first()
        val last = readings.last()
        val deltaTime = (last.timestampMs - first.timestampMs) / 1000f  // seconds
        if (deltaTime <= 0) return SourceProximityResult.UNKNOWN
        
        val deltaRate = last.value - first.value
        val rateOfChange = deltaRate / deltaTime  // µSv/h per second
        
        // Subtract background to get net dose from source
        val netFirst = (first.value - backgroundRate).coerceAtLeast(0.001f)
        val netLast = (last.value - backgroundRate).coerceAtLeast(0.001f)
        
        // Using inverse-square law: I1/I2 = (r2/r1)²
        // So: r1/r2 = sqrt(I2/I1)
        // If we assume walking speed ~1 m/s, we can estimate distances
        val intensityRatio = netLast / netFirst
        
        var estimatedDistance = Float.NaN
        var approachRate = 0f
        var predictedPeak = 0f
        var timeToClosest = Float.NaN
        var confidence = SourceProximityResult.Confidence.NONE
        var recommendation = "Insufficient data for proximity estimation"
        
        if (isApproaching && intensityRatio > 1.0f) {
            // We're approaching - dose is increasing
            val distanceRatio = 1f / sqrt(intensityRatio)  // r_now / r_before
            
            // Assuming average walking speed of 1 m/s and given time interval
            val assumedDistanceTraversed = deltaTime * 1.0f  // meters (assuming 1 m/s)
            approachRate = assumedDistanceTraversed / deltaTime
            
            // Estimate current distance based on rate of increase
            // Higher rate of change = closer to source
            // This is a rough estimate - real-world factors vary greatly
            if (rateOfChange > 0.01f) {
                // Very rough heuristic: distance ∝ 1/sqrt(rate_of_change)
                // Calibrated so 0.1 µSv/h/s change suggests ~1m distance
                estimatedDistance = (0.1f / rateOfChange) * 3.0f  // Very rough estimate
                estimatedDistance = estimatedDistance.coerceIn(0.1f, 100f)
            }
            
            // Predict peak based on trajectory
            // If intensity is rising by R per second, and using inverse square...
            // Peak will be much higher at closest approach
            if (estimatedDistance > 0.1f) {
                // Assume closest approach might be 0.5m (arm's length)
                val closestApproach = 0.5f
                val peakFactor = (estimatedDistance / closestApproach).let { it * it }  // inverse square
                predictedPeak = netLast * peakFactor + backgroundRate
                predictedPeak = predictedPeak.coerceAtMost(netLast * 100f)  // Cap at 100x current
                
                // Time to closest
                timeToClosest = (estimatedDistance - closestApproach) / approachRate
                timeToClosest = timeToClosest.coerceAtLeast(0f)
            }
            
            // Set confidence based on data quality
            confidence = when {
                readings.size >= 8 && approachingCount >= 6 -> SourceProximityResult.Confidence.HIGH
                readings.size >= 5 && approachingCount >= 3 -> SourceProximityResult.Confidence.MEDIUM
                else -> SourceProximityResult.Confidence.LOW
            }
            
            recommendation = when {
                predictedPeak > 10f -> "⚠️ High source activity. Exercise caution."
                predictedPeak > 1f -> "Source detected ahead. Current trajectory is safe."
                else -> "Approaching weak source. Continue monitoring."
            }
            
        } else if (!isApproaching && intensityRatio < 1.0f) {
            // Moving away from source
            confidence = SourceProximityResult.Confidence.LOW
            recommendation = "Moving away from source. Dose rate decreasing."
        }
        
        return SourceProximityResult(
            isApproaching = isApproaching,
            estimatedDistanceMeters = estimatedDistance,
            confidenceLevel = confidence,
            approachRateMetersPerSec = approachRate,
            predictedPeakDoseRate = predictedPeak,
            predictedTimeToClosest = timeToClosest,
            recommendation = recommendation
        )
    }

    /**
     * Evaluate statistical triggers against configured thresholds.
     * Returns list of triggers that should fire alerts.
     */
    fun evaluateTriggers(
        doseSnapshot: AnalysisSnapshot,
        cpsSnapshot: AnalysisSnapshot,
        config: StatisticalAlertConfig
    ): List<StatisticalTrigger> {
        val triggers = mutableListOf<StatisticalTrigger>()
        
        // Z-Score triggers
        if (config.zScoreEnabled) {
            if (doseSnapshot.zScore.isAnomaly && doseSnapshot.zScore.sigmaLevel >= config.zScoreSigmaThreshold) {
                triggers.add(StatisticalTrigger(
                    type = StatisticalTrigger.TriggerType.ZSCORE_ANOMALY,
                    severity = sigmaToSeverity(doseSnapshot.zScore.sigmaLevel),
                    message = "Dose rate ${doseSnapshot.zScore.anomalyDirection.name.lowercase()} normal by ${doseSnapshot.zScore.sigmaLevel}σ",
                    confidence = doseSnapshot.zScore.confidencePercent / 100f,
                    detectedValue = doseSnapshot.currentValue,
                    thresholdValue = doseSnapshot.baseline.mean + (config.zScoreSigmaThreshold * doseSnapshot.baseline.standardDeviation),
                    timestampMs = doseSnapshot.timestampMs
                ))
            }
            if (cpsSnapshot.zScore.isAnomaly && cpsSnapshot.zScore.sigmaLevel >= config.zScoreSigmaThreshold) {
                triggers.add(StatisticalTrigger(
                    type = StatisticalTrigger.TriggerType.ZSCORE_ANOMALY,
                    severity = sigmaToSeverity(cpsSnapshot.zScore.sigmaLevel),
                    message = "Count rate ${cpsSnapshot.zScore.anomalyDirection.name.lowercase()} normal by ${cpsSnapshot.zScore.sigmaLevel}σ",
                    confidence = cpsSnapshot.zScore.confidencePercent / 100f,
                    detectedValue = cpsSnapshot.currentValue,
                    thresholdValue = cpsSnapshot.baseline.mean + (config.zScoreSigmaThreshold * cpsSnapshot.baseline.standardDeviation),
                    timestampMs = cpsSnapshot.timestampMs
                ))
            }
        }
        
        // Rate-of-change triggers
        if (config.rocEnabled) {
            if (doseSnapshot.rateOfChange.isSignificant && 
                abs(doseSnapshot.rateOfChange.ratePercentPerSecond) >= config.rocThresholdPercent) {
                triggers.add(StatisticalTrigger(
                    type = StatisticalTrigger.TriggerType.RATE_OF_CHANGE,
                    severity = if (doseSnapshot.rateOfChange.trend == RateOfChangeResult.TrendDirection.RISING)
                        StatisticalTrigger.TriggerSeverity.WARNING else StatisticalTrigger.TriggerSeverity.INFO,
                    message = "Dose rate ${doseSnapshot.rateOfChange.trend.name.lowercase()} at ${String.format("%.1f", doseSnapshot.rateOfChange.ratePercentPerSecond)}%/s",
                    confidence = min(1f, abs(doseSnapshot.rateOfChange.ratePercentPerSecond) / (config.rocThresholdPercent * 2)),
                    detectedValue = doseSnapshot.rateOfChange.ratePercentPerSecond,
                    thresholdValue = config.rocThresholdPercent,
                    timestampMs = doseSnapshot.timestampMs
                ))
            }
        }
        
        // CUSUM triggers
        if (config.cusumEnabled) {
            if (doseSnapshot.cusum.changeDetected) {
                triggers.add(StatisticalTrigger(
                    type = StatisticalTrigger.TriggerType.CUSUM_CHANGE,
                    severity = StatisticalTrigger.TriggerSeverity.ALERT,
                    message = "Persistent ${doseSnapshot.cusum.changeDirection.name.lowercase()} detected in dose rate",
                    confidence = doseSnapshot.cusum.changeConfidence,
                    detectedValue = doseSnapshot.currentValue,
                    thresholdValue = doseSnapshot.baseline.mean,
                    timestampMs = doseSnapshot.timestampMs
                ))
            }
        }
        
        // Forecast threshold triggers
        if (config.forecastEnabled && config.forecastThreshold > 0) {
            val forecast = doseSnapshot.forecast60s  // 60-second forecast
            if (forecast.predictedValue > config.forecastThreshold) {
                triggers.add(StatisticalTrigger(
                    type = StatisticalTrigger.TriggerType.FORECAST_THRESHOLD,
                    severity = StatisticalTrigger.TriggerSeverity.WARNING,
                    message = "Dose rate predicted to reach ${String.format("%.3f", forecast.predictedValue)} µSv/h in 60s",
                    confidence = forecast.confidenceLevel,
                    detectedValue = forecast.predictedValue,
                    thresholdValue = config.forecastThreshold,
                    timestampMs = doseSnapshot.timestampMs
                ))
            }
        }
        
        // Predictive threshold crossing - alert BEFORE user thresholds are hit
        if (config.predictiveCrossingEnabled && config.userAlertThresholds.isNotEmpty()) {
            val roc = doseSnapshot.rateOfChange
            // Only predict if we're rising
            if (roc.trend == RateOfChangeResult.TrendDirection.RISING && roc.ratePerSecond > 0) {
                val current = doseSnapshot.currentValue
                
                for (threshold in config.userAlertThresholds) {
                    // Skip if we're already above this threshold
                    if (current >= threshold) continue
                    
                    // Calculate time to reach threshold using current rate
                    val timeToThresholdSeconds = (threshold - current) / roc.ratePerSecond
                    
                    // Warn if we'll cross within the warning window
                    if (timeToThresholdSeconds > 0 && timeToThresholdSeconds <= config.predictiveCrossingWarningSeconds) {
                        val minutes = (timeToThresholdSeconds / 60).toInt()
                        val seconds = (timeToThresholdSeconds % 60).toInt()
                        val timeStr = if (minutes > 0) {
                            "$minutes minute${if (minutes > 1) "s" else ""} ${seconds} second${if (seconds != 1) "s" else ""}"
                        } else {
                            "$seconds second${if (seconds != 1) "s" else ""}"
                        }
                        
                        triggers.add(StatisticalTrigger(
                            type = StatisticalTrigger.TriggerType.PREDICTIVE_CROSSING,
                            severity = if (timeToThresholdSeconds < 30) 
                                StatisticalTrigger.TriggerSeverity.ALERT 
                            else 
                                StatisticalTrigger.TriggerSeverity.WARNING,
                            message = "At current rate, you will exceed ${String.format("%.2f", threshold)} µSv/h in approximately $timeStr",
                            confidence = min(1f, (config.predictiveCrossingWarningSeconds.toFloat() - timeToThresholdSeconds) / config.predictiveCrossingWarningSeconds),
                            detectedValue = current,
                            thresholdValue = threshold,
                            timestampMs = doseSnapshot.timestampMs
                        ))
                        break  // Only alert for the nearest threshold
                    }
                }
            }
        }
        
        return triggers
    }

    /**
     * Reset all state (call when switching devices or clearing history).
     */
    @Synchronized
    fun reset() {
        doseReadings.clear()
        cpsReadings.clear()
        doseBaseline = BaselineStats.EMPTY
        cpsBaseline = BaselineStats.EMPTY
        doseCusumHigh = 0f
        doseCusumLow = 0f
        cpsCusumHigh = 0f
        cpsCusumLow = 0f
        doseLevel = 0f
        doseTrend = 0f
        cpsLevel = 0f
        cpsTrend = 0f
        doseHWInitialized = false
        cpsHWInitialized = false
        lastDoseReading = null
        lastCpsReading = null
        doseRocDirection = 0
        cpsRocDirection = 0
        proximityReadings.clear()
        // Note: We intentionally DON'T reset cumulative dose on engine reset
        // Use resetCumulativeDose() explicitly if needed
        Log.d(TAG, "Statistical engine reset")
    }

    /**
     * Full reset including cumulative dose (call for complete fresh start).
     */
    @Synchronized
    fun fullReset() {
        reset()
        resetCumulativeDose()
        Log.d(TAG, "Statistical engine full reset (including cumulative dose)")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update baseline using Exponentially Weighted Moving Average (EWMA).
     * This provides smooth adaptation to changing conditions.
     */
    private fun updateBaseline(current: BaselineStats, newValue: Float, timestampMs: Long): BaselineStats {
        val alpha = DEFAULT_ALPHA
        
        if (current.sampleCount == 0L) {
            // First reading - initialize
            return BaselineStats(
                mean = newValue,
                variance = 0f,
                standardDeviation = 0f,
                sampleCount = 1,
                lastUpdateMs = timestampMs,
                minValue = newValue,
                maxValue = newValue
            )
        }
        
        // EWMA for mean
        val newMean = alpha * newValue + (1 - alpha) * current.mean
        
        // EWMA for variance (using squared deviation from new mean)
        val deviation = newValue - newMean
        val newVariance = alpha * (deviation * deviation) + (1 - alpha) * current.variance
        val newStdDev = sqrt(newVariance)
        
        return BaselineStats(
            mean = newMean,
            variance = newVariance,
            standardDeviation = newStdDev,
            sampleCount = current.sampleCount + 1,
            lastUpdateMs = timestampMs,
            minValue = min(current.minValue, newValue),
            maxValue = max(current.maxValue, newValue)
        )
    }

    /**
     * Calculate Z-score and anomaly status.
     */
    private fun calculateZScore(value: Float, baseline: BaselineStats): ZScoreResult {
        if (!baseline.isValid()) {
            return ZScoreResult.NORMAL
        }
        
        val zScore = (value - baseline.mean) / baseline.standardDeviation
        val absZ = abs(zScore)
        
        // Determine sigma level
        val sigmaLevel = when {
            absZ >= 4 -> 4
            absZ >= 3 -> 3
            absZ >= 2 -> 2
            absZ >= 1 -> 1
            else -> 0
        }
        
        // Calculate confidence percentage based on z-score
        // Using standard normal distribution cumulative probabilities
        val confidencePercent = when {
            absZ >= 4 -> 99.99f
            absZ >= 3 -> 99.73f
            absZ >= 2 -> 95.45f
            absZ >= 1 -> 68.27f
            else -> 0f
        }
        
        val direction = when {
            zScore > 1 -> ZScoreResult.AnomalyDirection.ABOVE
            zScore < -1 -> ZScoreResult.AnomalyDirection.BELOW
            else -> ZScoreResult.AnomalyDirection.NORMAL
        }
        
        return ZScoreResult(
            zScore = zScore,
            isAnomaly = sigmaLevel >= 2,  // Consider 2σ+ as anomaly
            anomalyDirection = direction,
            confidencePercent = confidencePercent,
            sigmaLevel = sigmaLevel
        )
    }

    /**
     * Calculate rate of change with trend detection.
     */
    private fun calculateRateOfChange(
        value: Float,
        timestampMs: Long,
        lastReading: TimestampedValue?,
        currentDirectionCount: Int,
        baseline: BaselineStats
    ): RateOfChangeResult {
        if (lastReading == null) {
            return RateOfChangeResult.STABLE
        }
        
        val deltaTime = (timestampMs - lastReading.timestampMs) / 1000f  // seconds
        if (deltaTime <= 0) {
            return RateOfChangeResult.STABLE
        }
        
        val deltaValue = value - lastReading.value
        val ratePerSecond = deltaValue / deltaTime
        
        // Calculate percentage rate (relative to baseline mean to avoid division by zero)
        val refValue = if (baseline.mean > 0.0001f) baseline.mean else lastReading.value
        val ratePercentPerSecond = if (refValue > 0.0001f) {
            (ratePerSecond / refValue) * 100f
        } else 0f
        
        // Determine trend direction
        val trend = when {
            ratePerSecond > 0.0001f -> RateOfChangeResult.TrendDirection.RISING
            ratePerSecond < -0.0001f -> RateOfChangeResult.TrendDirection.FALLING
            else -> RateOfChangeResult.TrendDirection.STABLE
        }
        
        // Calculate acceleration (would need more history for proper second derivative)
        val acceleration = 0f  // TODO: Implement with more readings
        
        // Consider significant if:
        // 1. Rate of change exceeds 1% per second, OR
        // 2. Rate of change exceeds 0.5 standard deviations per second
        val isSignificant = abs(ratePercentPerSecond) > 1f ||
                (baseline.isValid() && abs(ratePerSecond) > 0.5f * baseline.standardDeviation)
        
        return RateOfChangeResult(
            ratePerSecond = ratePerSecond,
            ratePercentPerSecond = ratePercentPerSecond,
            acceleration = acceleration,
            trend = trend,
            isSignificant = isSignificant,
            consecutiveDirection = currentDirectionCount
        )
    }

    /**
     * Update direction counter for consecutive trend tracking.
     */
    private fun updateDirectionCounter(current: Int, trend: RateOfChangeResult.TrendDirection): Int {
        return when (trend) {
            RateOfChangeResult.TrendDirection.RISING -> if (current > 0) current + 1 else 1
            RateOfChangeResult.TrendDirection.FALLING -> if (current < 0) current - 1 else -1
            RateOfChangeResult.TrendDirection.STABLE -> 0
        }
    }

    /**
     * Update CUSUM (Cumulative Sum) for change-point detection.
     * CUSUM is excellent at detecting small, persistent shifts that static thresholds miss.
     */
    private fun updateCusum(
        value: Float,
        baseline: BaselineStats,
        currentHigh: Float,
        currentLow: Float
    ): CusumResult {
        if (!baseline.isValid()) {
            return CusumResult.NONE
        }
        
        val k = DEFAULT_CUSUM_K * baseline.standardDeviation  // Slack
        val h = DEFAULT_CUSUM_H * baseline.standardDeviation  // Threshold
        
        val deviation = value - baseline.mean
        
        // Update cumulative sums
        val newHigh = max(0f, currentHigh + deviation - k)
        val newLow = min(0f, currentLow + deviation + k)
        
        // Check for change detection
        val changeDirection = when {
            newHigh > h -> CusumResult.ChangeDirection.INCREASE
            newLow < -h -> CusumResult.ChangeDirection.DECREASE
            else -> CusumResult.ChangeDirection.NONE
        }
        
        val changeDetected = changeDirection != CusumResult.ChangeDirection.NONE
        
        // Calculate confidence based on how far past threshold
        val changeConfidence = when (changeDirection) {
            CusumResult.ChangeDirection.INCREASE -> min(1f, newHigh / (h * 2))
            CusumResult.ChangeDirection.DECREASE -> min(1f, abs(newLow) / (h * 2))
            CusumResult.ChangeDirection.NONE -> 0f
        }
        
        return CusumResult(
            cusumHigh = newHigh,
            cusumLow = newLow,
            changeDetected = changeDetected,
            changeDirection = changeDirection,
            changeConfidence = changeConfidence
        )
    }

    /**
     * Update Holt-Winters double exponential smoothing state.
     */
    private fun updateHoltWinters(value: Float, isDose: Boolean) {
        val alpha = DEFAULT_ALPHA
        val beta = DEFAULT_BETA
        
        if (isDose) {
            if (!doseHWInitialized) {
                doseLevel = value
                doseTrend = 0f
                doseHWInitialized = true
                return
            }
            
            val prevLevel = doseLevel
            doseLevel = alpha * value + (1 - alpha) * (doseLevel + doseTrend)
            doseTrend = beta * (doseLevel - prevLevel) + (1 - beta) * doseTrend
        } else {
            if (!cpsHWInitialized) {
                cpsLevel = value
                cpsTrend = 0f
                cpsHWInitialized = true
                return
            }
            
            val prevLevel = cpsLevel
            cpsLevel = alpha * value + (1 - alpha) * (cpsLevel + cpsTrend)
            cpsTrend = beta * (cpsLevel - prevLevel) + (1 - beta) * cpsTrend
        }
    }

    /**
     * Generate forecast using Holt-Winters double exponential smoothing.
     */
    private fun forecast(horizonSeconds: Int, isDose: Boolean): ForecastResult {
        val level: Float
        val trend: Float
        val baseline: BaselineStats
        
        if (isDose) {
            if (!doseHWInitialized) return ForecastResult.UNKNOWN
            level = doseLevel
            trend = doseTrend
            baseline = doseBaseline
        } else {
            if (!cpsHWInitialized) return ForecastResult.UNKNOWN
            level = cpsLevel
            trend = cpsTrend
            baseline = cpsBaseline
        }
        
        // Forecast value: level + horizon * trend
        val predicted = level + horizonSeconds * trend
        
        // Confidence interval widens with horizon
        // Use baseline stdDev * sqrt(horizon) as rough approximation
        val uncertainty = if (baseline.isValid()) {
            baseline.standardDeviation * sqrt(horizonSeconds.toFloat()) * 0.5f
        } else {
            abs(predicted) * 0.1f  // 10% uncertainty if no baseline
        }
        
        val trendDirection = when {
            trend > 0.0001f -> ForecastResult.TrendDirection.RISING
            trend < -0.0001f -> ForecastResult.TrendDirection.FALLING
            else -> ForecastResult.TrendDirection.STABLE
        }
        
        return ForecastResult(
            predictedValue = max(0f, predicted),  // Can't go negative
            lowerBound = max(0f, predicted - 1.96f * uncertainty),
            upperBound = predicted + 1.96f * uncertainty,
            confidenceLevel = 0.95f,
            horizonSeconds = horizonSeconds,
            trend = trendDirection
        )
    }

    /**
     * Convert sigma level to trigger severity.
     */
    private fun sigmaToSeverity(sigmaLevel: Int): StatisticalTrigger.TriggerSeverity {
        return when (sigmaLevel) {
            1 -> StatisticalTrigger.TriggerSeverity.INFO
            2 -> StatisticalTrigger.TriggerSeverity.WARNING
            3 -> StatisticalTrigger.TriggerSeverity.ALERT
            else -> StatisticalTrigger.TriggerSeverity.CRITICAL
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Timestamped value for rolling buffer.
     */
    data class TimestampedValue(val value: Float, val timestampMs: Long)

    /**
     * Forecast point for chart visualization.
     */
    data class ForecastPoint(
        val secondsAhead: Int,
        val predicted: Float,
        val lowerBound: Float,
        val upperBound: Float
    )

    /**
     * Configuration for statistical alerts.
     */
    data class StatisticalAlertConfig(
        val zScoreEnabled: Boolean = true,
        val zScoreSigmaThreshold: Int = 2,           // Alert at 2σ, 3σ, etc.
        val rocEnabled: Boolean = true,
        val rocThresholdPercent: Float = 5f,         // Alert at 5%/second change
        val cusumEnabled: Boolean = true,
        val forecastEnabled: Boolean = false,
        val forecastThreshold: Float = 0f,           // µSv/h threshold for forecast alert
        // Predictive threshold crossing: warn before hitting user-configured alert thresholds
        val predictiveCrossingEnabled: Boolean = true,
        val predictiveCrossingWarningSeconds: Int = 60,  // Warn this many seconds before crossing
        val userAlertThresholds: List<Float> = emptyList()  // User's alert thresholds (µSv/h)
    )

    /**
     * Thread-safe rolling buffer for time-series data.
     */
    private class RollingBuffer(private val maxSize: Int) {
        private val buffer = ArrayDeque<TimestampedValue>(maxSize)
        
        @Synchronized
        fun add(value: TimestampedValue) {
            if (buffer.size >= maxSize) {
                buffer.removeFirst()
            }
            buffer.addLast(value)
        }
        
        @Synchronized
        fun getRecent(count: Int): List<TimestampedValue> {
            val n = min(count, buffer.size)
            return buffer.toList().takeLast(n)
        }
        
        @Synchronized
        fun clear() {
            buffer.clear()
        }
        
        @Synchronized
        fun size(): Int = buffer.size
    }
}
