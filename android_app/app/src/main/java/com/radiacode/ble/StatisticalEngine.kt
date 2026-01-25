package com.radiacode.ble

import android.content.Context
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
        val forecast300s: ForecastResult,
        // Tier 3 additions
        val poissonUncertainty: PoissonUncertaintyResult = PoissonUncertaintyResult.UNKNOWN,
        val maCrossover: MACrossoverResult = MACrossoverResult.NONE,
        val bayesianChangepoint: BayesianChangepointResult = BayesianChangepointResult.NONE,
        val autocorrelation: AutocorrelationResult = AutocorrelationResult.NONE,
        // Tier 4 additions
        val locationAnomaly: LocationAnomalyResult = LocationAnomalyResult.NONE,
        val spatialGradient: SpatialGradientResult = SpatialGradientResult.NONE,
        val hotspotPrediction: HotspotPredictionResult = HotspotPredictionResult.NONE
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 3: ADVANCED STATISTICAL ANALYSIS DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Poisson uncertainty quantification result (Feature #9).
     * Radioactive decay follows Poisson statistics - uncertainty = sqrt(N).
     */
    data class PoissonUncertaintyResult(
        val countRate: Float,                    // CPS (counts per second)
        val countsInWindow: Int,                 // Total counts in measurement window
        val uncertainty: Float,                  // Absolute uncertainty (sqrt(N))
        val relativeUncertaintyPercent: Float,   // Relative uncertainty (100 / sqrt(N))
        val lowerBound: Float,                   // 1σ lower bound
        val upperBound: Float,                   // 1σ upper bound
        val isStatisticallySignificant: Boolean, // Is change > statistical uncertainty?
        val significanceMultiple: Float          // How many σ above/below expected?
    ) {
        companion object {
            val UNKNOWN = PoissonUncertaintyResult(0f, 0, 0f, 100f, 0f, 0f, false, 0f)
        }
        
        /**
         * Human-readable uncertainty string.
         */
        fun getUncertaintyString(): String {
            return String.format("%.1f +/- %.1f (%.1f%%)", countRate, uncertainty, relativeUncertaintyPercent)
        }
    }

    /**
     * Moving Average Crossover result (Feature #10).
     * Detects trend reversals when short-term MA crosses long-term MA.
     */
    data class MACrossoverResult(
        val shortMA: Float,                      // Short-term moving average (e.g., 10s)
        val longMA: Float,                       // Long-term moving average (e.g., 60s)
        val crossoverType: CrossoverType,
        val crossoverDetected: Boolean,
        val timeSinceCrossoverMs: Long,          // How long ago crossover occurred
        val crossoverStrength: Float             // Magnitude of divergence after cross (0-1)
    ) {
        enum class CrossoverType {
            NONE,
            GOLDEN_CROSS,    // Short crosses above long (bullish/rising)
            DEATH_CROSS      // Short crosses below long (bearish/falling)
        }
        
        companion object {
            val NONE = MACrossoverResult(0f, 0f, CrossoverType.NONE, false, 0L, 0f)
        }
        
        /**
         * Get human-readable crossover description.
         */
        fun getDescription(): String {
            return when (crossoverType) {
                CrossoverType.GOLDEN_CROSS -> "Trend reversal: Rising"
                CrossoverType.DEATH_CROSS -> "Trend reversal: Falling"
                CrossoverType.NONE -> "No crossover"
            }
        }
    }

    /**
     * Bayesian changepoint detection result (Feature #11).
     * Probabilistic detection of regime changes.
     */
    data class BayesianChangepointResult(
        val changepointDetected: Boolean,
        val changepointProbability: Float,       // Probability that a change occurred (0-1)
        val estimatedChangepointAgoMs: Long,     // When the change likely occurred
        val preChangeMean: Float,                // Mean before the changepoint
        val postChangeMean: Float,               // Mean after the changepoint
        val changeMagnitude: Float,              // Absolute change magnitude
        val runLength: Int                       // Current run length (readings since last change)
    ) {
        companion object {
            val NONE = BayesianChangepointResult(false, 0f, 0L, 0f, 0f, 0f, 0)
        }
        
        /**
         * Get human-readable changepoint description.
         */
        fun getDescription(): String {
            return if (changepointDetected) {
                val secondsAgo = estimatedChangepointAgoMs / 1000
                String.format("Change detected %ds ago (%.0f%% confident)", 
                    secondsAgo, changepointProbability * 100)
            } else {
                "No regime change detected"
            }
        }
    }

    /**
     * Autocorrelation pattern recognition result (Feature #12).
     * Detects periodic patterns in readings (e.g., HVAC cycles, machinery).
     */
    data class AutocorrelationResult(
        val patternDetected: Boolean,
        val dominantPeriodSeconds: Float,        // Primary periodic component
        val periodConfidence: Float,             // How confident in the period (0-1)
        val amplitude: Float,                    // Oscillation amplitude
        val secondaryPeriodSeconds: Float,       // Secondary periodic component (if any)
        val patternType: PatternType,
        val possibleSource: String               // Inferred source (HVAC, equipment, etc.)
    ) {
        enum class PatternType {
            NONE,
            PERIODIC,        // Regular repeating pattern
            QUASI_PERIODIC,  // Nearly periodic with variation
            RANDOM_WALK      // No pattern, random variation
        }
        
        companion object {
            val NONE = AutocorrelationResult(false, 0f, 0f, 0f, 0f, PatternType.NONE, "")
        }
        
        /**
         * Get human-readable pattern description.
         */
        fun getDescription(): String {
            return if (patternDetected && dominantPeriodSeconds > 0) {
                String.format("%.1fs cycle detected (%.0f%% confident)%s", 
                    dominantPeriodSeconds, 
                    periodConfidence * 100,
                    if (possibleSource.isNotEmpty()) " - $possibleSource" else "")
            } else {
                "No periodic pattern detected"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 4: GEOSPATIAL INTELLIGENCE DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Location-aware anomaly detection result (Feature #13).
     * Compares current reading to historical baseline at THIS location.
     */
    data class LocationAnomalyResult(
        val hexId: String,                       // Current hex cell ID
        val currentReading: Float,               // Current µSv/h
        val locationBaseline: Float,             // Historical mean for this location
        val locationStdDev: Float,               // Historical std dev for this location
        val zScoreAtLocation: Float,             // Z-score relative to location history
        val sampleCount: Int,                    // Number of historical samples at location
        val isAnomaly: Boolean,                  // Whether reading is anomalous for location
        val anomalyType: LocationAnomalyType,
        val confidence: Float                    // Confidence in the assessment (0-1)
    ) {
        enum class LocationAnomalyType {
            NONE,
            ELEVATED,        // Higher than normal for this location
            DEPRESSED,       // Lower than normal for this location
            FIRST_VISIT,     // No history at this location
            INSUFFICIENT_DATA // Not enough samples for reliable baseline
        }
        
        companion object {
            val NONE = LocationAnomalyResult("", 0f, 0f, 0f, 0f, 0, false, LocationAnomalyType.NONE, 0f)
        }
        
        fun getDescription(): String {
            return when (anomalyType) {
                LocationAnomalyType.ELEVATED -> 
                    String.format("%.1fσ above location normal (%.3f vs %.3f µSv/h)", 
                        zScoreAtLocation, currentReading, locationBaseline)
                LocationAnomalyType.DEPRESSED -> 
                    String.format("%.1fσ below location normal (%.3f vs %.3f µSv/h)", 
                        -zScoreAtLocation, currentReading, locationBaseline)
                LocationAnomalyType.FIRST_VISIT -> "First visit to this location"
                LocationAnomalyType.INSUFFICIENT_DATA -> "Building location baseline..."
                LocationAnomalyType.NONE -> "Normal for this location"
            }
        }
    }

    /**
     * Spatial gradient analysis result (Feature #14).
     * Calculates direction and magnitude of radiation increase.
     */
    data class SpatialGradientResult(
        val gradientMagnitude: Float,            // µSv/h per meter
        val gradientBearing: Float,              // Degrees (0-360), direction of INCREASING radiation
        val confidence: Float,                   // Confidence in gradient estimate (0-1)
        val samplesUsed: Int,                    // Number of position samples used
        val distanceCovered: Float,              // Total distance moved (meters)
        val hasSignificantGradient: Boolean,
        val sourceDirection: String              // Human-readable direction (N, NE, E, etc.)
    ) {
        companion object {
            val NONE = SpatialGradientResult(0f, 0f, 0f, 0, 0f, false, "")
            
            /**
             * Convert bearing to compass direction.
             */
            fun bearingToDirection(bearing: Float): String {
                val normalized = ((bearing % 360) + 360) % 360
                return when {
                    normalized < 22.5 -> "N"
                    normalized < 67.5 -> "NE"
                    normalized < 112.5 -> "E"
                    normalized < 157.5 -> "SE"
                    normalized < 202.5 -> "S"
                    normalized < 247.5 -> "SW"
                    normalized < 292.5 -> "W"
                    normalized < 337.5 -> "NW"
                    else -> "N"
                }
            }
        }
        
        fun getDescription(): String {
            return if (hasSignificantGradient) {
                String.format("Gradient: +%.4f µSv/h/m toward %s (%.0f°)", 
                    gradientMagnitude, sourceDirection, gradientBearing)
            } else {
                "No significant spatial gradient detected"
            }
        }
    }

    /**
     * Hotspot prediction result (Feature #15).
     * Predicts radiation levels at nearby unvisited locations.
     */
    data class HotspotPredictionResult(
        val predictedHotspots: List<PredictedPoint>,
        val interpolatedGrid: Map<String, Float>,    // hexId -> predicted µSv/h
        val highestPrediction: Float,
        val highestPredictionBearing: Float,         // Direction to highest predicted point
        val highestPredictionDistance: Float,        // Distance in meters
        val confidence: Float
    ) {
        data class PredictedPoint(
            val hexId: String,
            val predictedValue: Float,
            val uncertainty: Float,
            val bearing: Float,
            val distance: Float
        )
        
        companion object {
            val NONE = HotspotPredictionResult(emptyList(), emptyMap(), 0f, 0f, 0f, 0f)
        }
        
        fun getDescription(): String {
            return if (predictedHotspots.isNotEmpty()) {
                val highest = predictedHotspots.maxByOrNull { it.predictedValue }
                if (highest != null) {
                    String.format("Predicted hotspot: %.3f µSv/h at %.0fm %s", 
                        highest.predictedValue, 
                        highest.distance,
                        SpatialGradientResult.bearingToDirection(highest.bearing))
                } else {
                    "No hotspots predicted"
                }
            } else {
                "Insufficient data for prediction"
            }
        }
    }

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
    // TIER 3 STATE - Advanced Statistical Analysis
    // ═══════════════════════════════════════════════════════════════════════════

    // Moving average crossover (Feature #10)
    private val shortMAWindow = 10      // 10 seconds
    private val longMAWindow = 60       // 60 seconds  
    private var lastShortMA: Float = 0f
    private var lastLongMA: Float = 0f
    private var lastCrossoverMs: Long = 0L
    private var lastCrossoverType: MACrossoverResult.CrossoverType = MACrossoverResult.CrossoverType.NONE
    
    // Bayesian changepoint detection (Feature #11)
    private var bayesianRunLength: Int = 0
    private var bayesianMean: Float = 0f
    private var bayesianVariance: Float = 0f
    private var lastBayesianChangepointMs: Long = 0L
    private var preChangeMean: Float = 0f
    
    // Autocorrelation state (Feature #12)
    private val autocorrBuffer = RollingBuffer(MEDIUM_WINDOW_SIZE)  // 5 min for pattern detection
    private var lastAutocorrAnalysisMs: Long = 0L
    private var cachedAutocorrResult: AutocorrelationResult = AutocorrelationResult.NONE
    private val AUTOCORR_ANALYSIS_INTERVAL_MS = 5000L  // Analyze every 5 seconds (expensive)

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 4 STATE - Geospatial Intelligence
    // ═══════════════════════════════════════════════════════════════════════════

    // Location-aware anomaly detection (Feature #13)
    // Maps hexId -> list of (reading, timestamp) pairs for location-specific baseline
    private val locationBaselines = mutableMapOf<String, MutableList<Pair<Float, Long>>>()
    private var currentHexId: String = ""
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private val LOCATION_BASELINE_MIN_SAMPLES = 5  // Minimum samples for reliable baseline
    private val LOCATION_BASELINE_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000L  // 30 days
    
    // Spatial gradient analysis (Feature #14)
    data class GeoReading(val lat: Double, val lng: Double, val reading: Float, val timestampMs: Long)
    private val geoReadings = ArrayDeque<GeoReading>(100)  // Recent geo-tagged readings
    private val GRADIENT_MIN_DISTANCE_METERS = 5.0  // Minimum travel for gradient calc
    private val GRADIENT_MIN_SAMPLES = 3
    private var lastGradientResult: SpatialGradientResult = SpatialGradientResult.NONE
    
    // Hotspot prediction (Feature #15)
    private val HOTSPOT_PREDICTION_RADIUS_METERS = 50.0  // Predict within 50m radius
    private val HOTSPOT_GRID_SIZE_METERS = 10.0  // Prediction grid resolution

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
        
        // ═══════════════════════════════════════════════════════════════════════
        // TIER 3: ADVANCED STATISTICAL ANALYSIS
        // ═══════════════════════════════════════════════════════════════════════
        
        // Add to autocorrelation buffer
        autocorrBuffer.add(TimestampedValue(doseRate, timestampMs))
        
        // Calculate Poisson uncertainty (Feature #9) - CPS is count data
        val poissonResult = calculatePoissonUncertainty(cps, cpsBaseline, timestampMs)
        
        // Calculate Moving Average Crossover (Feature #10)
        val maCrossoverResult = calculateMACrossover(doseRate, timestampMs)
        
        // Calculate Bayesian Changepoint Detection (Feature #11)
        val bayesianResult = calculateBayesianChangepoint(doseRate, timestampMs)
        
        // Calculate Autocorrelation (Feature #12) - less frequent due to cost
        val autocorrResult = calculateAutocorrelation(timestampMs)
        
        // ═══════════════════════════════════════════════════════════════════════
        // TIER 4: GEOSPATIAL INTELLIGENCE
        // ═══════════════════════════════════════════════════════════════════════
        
        // Calculate Location-Aware Anomaly (Feature #13)
        val locationAnomalyResult = calculateLocationAnomaly(doseRate, timestampMs)
        
        // Calculate Spatial Gradient (Feature #14)
        val spatialGradientResult = calculateSpatialGradient(doseRate, timestampMs)
        
        // Calculate Hotspot Prediction (Feature #15)
        val hotspotResult = calculateHotspotPrediction()
        
        val doseSnapshot = AnalysisSnapshot(
            timestampMs = timestampMs,
            currentValue = doseRate,
            baseline = doseBaseline,
            zScore = doseZScore,
            rateOfChange = doseRoc,
            cusum = doseCusum,
            forecast30s = doseForecast30,
            forecast60s = doseForecast60,
            forecast300s = doseForecast300,
            poissonUncertainty = poissonResult,
            maCrossover = maCrossoverResult,
            bayesianChangepoint = bayesianResult,
            autocorrelation = autocorrResult,
            locationAnomaly = locationAnomalyResult,
            spatialGradient = spatialGradientResult,
            hotspotPrediction = hotspotResult
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
            forecast300s = cpsForecast300,
            poissonUncertainty = poissonResult,
            maCrossover = maCrossoverResult,
            bayesianChangepoint = bayesianResult,
            autocorrelation = autocorrResult,
            locationAnomaly = locationAnomalyResult,
            spatialGradient = spatialGradientResult,
            hotspotPrediction = hotspotResult
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
    // TIER 3: ADVANCED STATISTICAL ANALYSIS CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Feature #9: Poisson Uncertainty Quantification
     * 
     * Radioactive decay follows Poisson statistics where uncertainty = sqrt(N).
     * This properly quantifies measurement uncertainty and only flags changes
     * that exceed statistical uncertainty.
     */
    private fun calculatePoissonUncertainty(
        cps: Float, 
        baseline: BaselineStats, 
        timestampMs: Long
    ): PoissonUncertaintyResult {
        if (cps < 0.001f) return PoissonUncertaintyResult.UNKNOWN
        
        // Assume 1 second measurement window (typical for RadiaCode)
        val measurementWindowSeconds = 1.0f
        val countsInWindow = (cps * measurementWindowSeconds).toInt().coerceAtLeast(1)
        
        // Poisson uncertainty: σ = sqrt(N)
        val uncertainty = sqrt(countsInWindow.toFloat())
        
        // Relative uncertainty (percentage)
        val relativeUncertainty = (uncertainty / countsInWindow) * 100f
        
        // 1σ bounds
        val lowerBound = (countsInWindow - uncertainty).coerceAtLeast(0f)
        val upperBound = countsInWindow + uncertainty
        
        // Compare to expected baseline to see if change is statistically significant
        var isSignificant = false
        var significanceMultiple = 0f
        
        if (baseline.isValid()) {
            val expectedCps = baseline.mean
            val expectedCounts = expectedCps * measurementWindowSeconds
            val expectedUncertainty = sqrt(expectedCounts.coerceAtLeast(1f))
            
            // Combined uncertainty (quadrature sum)
            val combinedUncertainty = sqrt(uncertainty * uncertainty + expectedUncertainty * expectedUncertainty)
            
            // Calculate significance (how many σ away from expected)
            val difference = abs(cps - expectedCps)
            significanceMultiple = if (combinedUncertainty > 0.001f) {
                difference / combinedUncertainty
            } else 0f
            
            // Significant if > 2σ away from expected
            isSignificant = significanceMultiple > 2.0f
        }
        
        return PoissonUncertaintyResult(
            countRate = cps,
            countsInWindow = countsInWindow,
            uncertainty = uncertainty,
            relativeUncertaintyPercent = relativeUncertainty,
            lowerBound = lowerBound,
            upperBound = upperBound,
            isStatisticallySignificant = isSignificant,
            significanceMultiple = significanceMultiple
        )
    }

    /**
     * Feature #10: Moving Average Crossover Detection
     * 
     * Detects trend reversals when a short-term moving average crosses
     * above or below a long-term moving average.
     * - "Golden Cross": Short MA crosses above Long MA (trending up)
     * - "Death Cross": Short MA crosses below Long MA (trending down)
     */
    private fun calculateMACrossover(value: Float, timestampMs: Long): MACrossoverResult {
        val recentReadings = doseReadings.getRecent(longMAWindow)
        if (recentReadings.size < longMAWindow / 2) {
            return MACrossoverResult.NONE  // Not enough data
        }
        
        // Calculate short-term MA
        val shortReadings = recentReadings.takeLast(shortMAWindow.coerceAtMost(recentReadings.size))
        val currentShortMA = if (shortReadings.isNotEmpty()) {
            shortReadings.map { it.value }.average().toFloat()
        } else return MACrossoverResult.NONE
        
        // Calculate long-term MA
        val currentLongMA = recentReadings.map { it.value }.average().toFloat()
        
        // Detect crossover
        var crossoverType = MACrossoverResult.CrossoverType.NONE
        var crossoverDetected = false
        
        if (lastShortMA != 0f && lastLongMA != 0f) {
            // Golden Cross: short crosses above long
            if (lastShortMA <= lastLongMA && currentShortMA > currentLongMA) {
                crossoverType = MACrossoverResult.CrossoverType.GOLDEN_CROSS
                crossoverDetected = true
                lastCrossoverMs = timestampMs
                lastCrossoverType = crossoverType
            }
            // Death Cross: short crosses below long
            else if (lastShortMA >= lastLongMA && currentShortMA < currentLongMA) {
                crossoverType = MACrossoverResult.CrossoverType.DEATH_CROSS
                crossoverDetected = true
                lastCrossoverMs = timestampMs
                lastCrossoverType = crossoverType
            }
            // Maintain previous crossover type if within recency window
            else if (lastCrossoverType != MACrossoverResult.CrossoverType.NONE &&
                     timestampMs - lastCrossoverMs < 60000) { // 1 minute recency
                crossoverType = lastCrossoverType
            }
        }
        
        // Update state for next iteration
        lastShortMA = currentShortMA
        lastLongMA = currentLongMA
        
        // Calculate crossover strength (divergence)
        val divergence = if (currentLongMA > 0.001f) {
            abs(currentShortMA - currentLongMA) / currentLongMA
        } else 0f
        
        val timeSinceCrossover = if (lastCrossoverMs > 0) timestampMs - lastCrossoverMs else 0L
        
        return MACrossoverResult(
            shortMA = currentShortMA,
            longMA = currentLongMA,
            crossoverType = crossoverType,
            crossoverDetected = crossoverDetected,
            timeSinceCrossoverMs = timeSinceCrossover,
            crossoverStrength = divergence.coerceIn(0f, 1f)
        )
    }

    /**
     * Feature #11: Bayesian Changepoint Detection
     * 
     * Online Bayesian changepoint detection using a simplified Adams & MacKay approach.
     * Detects when the underlying generating process has changed (regime change).
     */
    private fun calculateBayesianChangepoint(value: Float, timestampMs: Long): BayesianChangepointResult {
        // Simplified Bayesian changepoint using online parameter estimation
        val hazardRate = 0.01f  // Prior probability of changepoint at any step
        val priorScale = 1.0f   // Prior variance scale
        
        // Update run length and statistics
        bayesianRunLength++
        
        // Online mean and variance update (Welford's algorithm)
        if (bayesianRunLength == 1) {
            bayesianMean = value
            bayesianVariance = 0f
            preChangeMean = doseBaseline.mean
        } else {
            val delta = value - bayesianMean
            bayesianMean += delta / bayesianRunLength
            val delta2 = value - bayesianMean
            bayesianVariance += delta * delta2
        }
        
        val currentVariance = if (bayesianRunLength > 2) {
            bayesianVariance / (bayesianRunLength - 1)
        } else priorScale
        
        // Calculate probability of changepoint using predictive likelihood
        // Higher deviation from run's mean suggests changepoint
        val runStdDev = sqrt(currentVariance.coerceAtLeast(0.0001f))
        val deviationFromRunMean = abs(value - bayesianMean)
        val zFromRun = deviationFromRunMean / runStdDev
        
        // Also compare to overall baseline
        var changepointProbability = 0f
        var changepointDetected = false
        
        if (doseBaseline.isValid()) {
            val deviationFromBaseline = abs(bayesianMean - doseBaseline.mean)
            val baselineZ = deviationFromBaseline / doseBaseline.standardDeviation.coerceAtLeast(0.0001f)
            
            // Combine evidence: high z from baseline suggests the run is in a new regime
            // This is a simplified approximation of proper Bayesian inference
            changepointProbability = when {
                baselineZ > 3.0f -> 0.95f
                baselineZ > 2.5f -> 0.85f
                baselineZ > 2.0f -> 0.70f
                baselineZ > 1.5f -> 0.50f
                baselineZ > 1.0f -> 0.30f
                else -> 0.10f
            }
            
            // Detect changepoint if probability high and run length is short
            // (short run at high probability = recent change)
            if (changepointProbability > 0.75f && bayesianRunLength < 30) {
                changepointDetected = true
                if (lastBayesianChangepointMs == 0L || 
                    timestampMs - lastBayesianChangepointMs > 30000) { // At least 30s between detections
                    lastBayesianChangepointMs = timestampMs - (bayesianRunLength * 1000L)
                    preChangeMean = doseBaseline.mean
                }
            }
            
            // Reset run if we've detected a clear changepoint
            if (changepointDetected && zFromRun > 3.0f) {
                // Start a new run
                bayesianRunLength = 1
                bayesianMean = value
                bayesianVariance = 0f
            }
        }
        
        val timeSinceChange = if (lastBayesianChangepointMs > 0) {
            timestampMs - lastBayesianChangepointMs
        } else 0L
        
        return BayesianChangepointResult(
            changepointDetected = changepointDetected,
            changepointProbability = changepointProbability,
            estimatedChangepointAgoMs = timeSinceChange,
            preChangeMean = preChangeMean,
            postChangeMean = bayesianMean,
            changeMagnitude = abs(bayesianMean - preChangeMean),
            runLength = bayesianRunLength
        )
    }

    /**
     * Feature #12: Autocorrelation Pattern Recognition
     * 
     * Detects periodic patterns in readings that might indicate
     * HVAC systems, industrial equipment, or other cyclic sources.
     */
    private fun calculateAutocorrelation(timestampMs: Long): AutocorrelationResult {
        // Only recalculate periodically (expensive operation)
        if (timestampMs - lastAutocorrAnalysisMs < AUTOCORR_ANALYSIS_INTERVAL_MS) {
            return cachedAutocorrResult
        }
        lastAutocorrAnalysisMs = timestampMs
        
        val readings = autocorrBuffer.getRecent(MEDIUM_WINDOW_SIZE)
        if (readings.size < 60) {  // Need at least 60 samples
            cachedAutocorrResult = AutocorrelationResult.NONE
            return cachedAutocorrResult
        }
        
        val values = readings.map { it.value }
        val n = values.size
        val mean = values.average().toFloat()
        
        // Remove mean (center the data)
        val centered = values.map { it - mean }
        
        // Calculate variance
        val variance = centered.map { it * it }.average().toFloat()
        if (variance < 0.0001f) {
            cachedAutocorrResult = AutocorrelationResult.NONE
            return cachedAutocorrResult
        }
        
        // Calculate autocorrelation for various lags
        // Look for lags from 2s to 120s (assuming ~1 reading/second)
        val maxLag = min(120, n / 2)
        val autocorrelations = mutableListOf<Pair<Int, Float>>()
        
        for (lag in 2..maxLag) {
            var sum = 0f
            for (i in 0 until (n - lag)) {
                sum += centered[i] * centered[i + lag]
            }
            val acf = sum / ((n - lag) * variance)
            autocorrelations.add(lag to acf)
        }
        
        // Find peaks in autocorrelation (potential periods)
        // A peak is where ACF is higher than neighbors and > 0.3
        val peaks = mutableListOf<Pair<Int, Float>>()
        for (i in 1 until autocorrelations.size - 1) {
            val (lag, acf) = autocorrelations[i]
            val prevAcf = autocorrelations[i - 1].second
            val nextAcf = autocorrelations[i + 1].second
            
            if (acf > 0.3f && acf > prevAcf && acf > nextAcf) {
                peaks.add(lag to acf)
            }
        }
        
        // Sort peaks by strength
        val sortedPeaks = peaks.sortedByDescending { it.second }
        
        if (sortedPeaks.isEmpty()) {
            cachedAutocorrResult = AutocorrelationResult(
                patternDetected = false,
                dominantPeriodSeconds = 0f,
                periodConfidence = 0f,
                amplitude = 0f,
                secondaryPeriodSeconds = 0f,
                patternType = AutocorrelationResult.PatternType.RANDOM_WALK,
                possibleSource = ""
            )
            return cachedAutocorrResult
        }
        
        // Dominant period
        val dominantLag = sortedPeaks[0].first
        val dominantStrength = sortedPeaks[0].second
        
        // Secondary period (if exists)
        val secondaryLag = if (sortedPeaks.size > 1) sortedPeaks[1].first else 0
        
        // Estimate amplitude from variance
        val amplitude = sqrt(variance * 2)  // Rough estimate
        
        // Infer possible source based on period
        val possibleSource = when {
            dominantLag in 15..45 -> "Possible HVAC cycle"
            dominantLag in 55..70 -> "Possible 1-minute equipment cycle"
            dominantLag in 110..130 -> "Possible 2-minute cycle"
            dominantLag < 10 -> "High-frequency oscillation"
            else -> "Periodic equipment"
        }
        
        // Determine pattern type
        val patternType = when {
            dominantStrength > 0.6f -> AutocorrelationResult.PatternType.PERIODIC
            dominantStrength > 0.4f -> AutocorrelationResult.PatternType.QUASI_PERIODIC
            else -> AutocorrelationResult.PatternType.RANDOM_WALK
        }
        
        cachedAutocorrResult = AutocorrelationResult(
            patternDetected = dominantStrength > 0.3f,
            dominantPeriodSeconds = dominantLag.toFloat(),
            periodConfidence = dominantStrength,
            amplitude = amplitude,
            secondaryPeriodSeconds = secondaryLag.toFloat(),
            patternType = patternType,
            possibleSource = if (dominantStrength > 0.4f) possibleSource else ""
        )
        
        return cachedAutocorrResult
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 4: GEOSPATIAL INTELLIGENCE CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update current location (call this from RadiaCodeForegroundService when location changes).
     */
    @Synchronized
    fun setCurrentLocation(lat: Double, lng: Double, hexId: String) {
        currentHexId = hexId
        currentLatitude = lat
        currentLongitude = lng
    }
    
    /**
     * Feature #13: Location-Aware Anomaly Detection
     * 
     * Compares current reading to the historical baseline for THIS specific location (hexagon).
     * A reading might be "normal" globally but ANOMALOUS for this particular spot.
     */
    private fun calculateLocationAnomaly(
        doseRate: Float, 
        timestampMs: Long
    ): LocationAnomalyResult {
        val hexId = currentHexId
        if (hexId.isEmpty()) {
            return LocationAnomalyResult.NONE
        }
        
        // Get or create baseline list for this hexagon
        val baseline = locationBaselines.getOrPut(hexId) { mutableListOf() }
        
        // Add current reading to location history
        baseline.add(Pair(doseRate, timestampMs))
        
        // Prune old readings (older than 30 days)
        val cutoff = timestampMs - LOCATION_BASELINE_MAX_AGE_MS
        baseline.removeAll { it.second < cutoff }
        
        // Need minimum samples for meaningful analysis  
        if (baseline.size < LOCATION_BASELINE_MIN_SAMPLES) {
            return LocationAnomalyResult(
                hexId = hexId,
                currentReading = doseRate,
                locationBaseline = if (baseline.isNotEmpty()) baseline.map { it.first }.average().toFloat() else doseRate,
                locationStdDev = 0f,
                zScoreAtLocation = 0f,
                sampleCount = baseline.size,
                isAnomaly = false,
                anomalyType = if (baseline.size == 1) 
                    LocationAnomalyResult.LocationAnomalyType.FIRST_VISIT 
                else 
                    LocationAnomalyResult.LocationAnomalyType.INSUFFICIENT_DATA,
                confidence = 0f
            )
        }
        
        // Calculate statistics for this location
        val readings = baseline.map { it.first }
        val mean = readings.average().toFloat()
        val variance = readings.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)
        
        // Calculate z-score at this location
        val zScore = if (stdDev > 0.0001f) {
            (doseRate - mean) / stdDev
        } else 0f
        
        val absZ = abs(zScore)
        val isAnomaly = absZ >= 2.0f
        
        // Determine anomaly type
        val anomalyType = when {
            absZ < 2.0f -> LocationAnomalyResult.LocationAnomalyType.NONE
            zScore > 0 -> LocationAnomalyResult.LocationAnomalyType.ELEVATED
            else -> LocationAnomalyResult.LocationAnomalyType.DEPRESSED
        }
        
        // Calculate confidence based on sample count and z-score significance
        val confidence = when {
            absZ >= 4 -> 0.9999f
            absZ >= 3 -> 0.9973f
            absZ >= 2 -> 0.9545f
            else -> absZ / 2f  // Linear scaling below 2σ
        }.coerceAtMost((baseline.size / 20f).coerceAtMost(1f))  // Cap by sample count
        
        return LocationAnomalyResult(
            hexId = hexId,
            currentReading = doseRate,
            locationBaseline = mean,
            locationStdDev = stdDev,
            zScoreAtLocation = zScore,
            sampleCount = baseline.size,
            isAnomaly = isAnomaly,
            anomalyType = anomalyType,
            confidence = confidence
        )
    }

    /**
     * Feature #14: Spatial Gradient Analysis
     * 
     * Analyzes how readings change across space to estimate:
     * 1. Direction of increasing radiation (toward source)
     * 2. Gradient magnitude (how fast it's changing)
     */
    private fun calculateSpatialGradient(
        doseRate: Float,
        timestampMs: Long
    ): SpatialGradientResult {
        val lat = currentLatitude
        val lng = currentLongitude
        
        // Add current reading to geo buffer if we have valid coordinates
        if (lat != 0.0 && lng != 0.0) {
            if (geoReadings.size >= 100) {
                geoReadings.removeFirst()
            }
            geoReadings.addLast(GeoReading(lat, lng, doseRate, timestampMs))
        }
        
        // Need minimum samples for gradient
        if (geoReadings.size < GRADIENT_MIN_SAMPLES) {
            return SpatialGradientResult.NONE
        }
        
        // Get spatially diverse readings (at least 5m apart)
        val spatiallyDiverseReadings = mutableListOf<GeoReading>()
        
        for (reading in geoReadings) {
            if (spatiallyDiverseReadings.isEmpty()) {
                spatiallyDiverseReadings.add(reading)
            } else {
                val lastAdded = spatiallyDiverseReadings.last()
                val dist = haversineDistance(lastAdded.lat, lastAdded.lng, reading.lat, reading.lng)
                if (dist >= GRADIENT_MIN_DISTANCE_METERS) {
                    spatiallyDiverseReadings.add(reading)
                }
            }
        }
        
        if (spatiallyDiverseReadings.size < 3) {
            return SpatialGradientResult.NONE
        }
        
        // Calculate gradient using least squares fit
        val centerLat = spatiallyDiverseReadings.map { it.lat }.average()
        val centerLng = spatiallyDiverseReadings.map { it.lng }.average()
        
        val metersPerDegLat = 111320.0
        val metersPerDegLng = 111320.0 * cos(Math.toRadians(centerLat))
        
        // Convert to local meters and prepare for least squares
        val points = spatiallyDiverseReadings.map { r ->
            Triple(
                (r.lng - centerLng) * metersPerDegLng,
                (r.lat - centerLat) * metersPerDegLat,
                r.reading.toDouble()
            )
        }
        
        val meanZ = points.map { it.third }.average()
        val meanX = points.map { it.first }.average()
        val meanY = points.map { it.second }.average()
        
        var sXX = 0.0; var sYY = 0.0; var sXY = 0.0
        var sXZ = 0.0; var sYZ = 0.0
        
        for ((x, y, z) in points) {
            val dx = x - meanX
            val dy = y - meanY
            val dz = z - meanZ
            sXX += dx * dx
            sYY += dy * dy
            sXY += dx * dy
            sXZ += dx * dz
            sYZ += dy * dz
        }
        
        val det = sXX * sYY - sXY * sXY
        if (abs(det) < 1e-10) {
            return SpatialGradientResult.NONE
        }
        
        val gradX = (sYY * sXZ - sXY * sYZ) / det
        val gradY = (sXX * sYZ - sXY * sXZ) / det
        
        val gradientMagnitude = sqrt(gradX * gradX + gradY * gradY).toFloat()
        var bearingDeg = Math.toDegrees(atan2(gradX, gradY)).toFloat()
        if (bearingDeg < 0) bearingDeg += 360f
        
        val sourceDirection = SpatialGradientResult.bearingToDirection(bearingDeg)
        
        // Calculate total distance covered and confidence
        var totalDistance = 0.0
        for (i in 1 until spatiallyDiverseReadings.size) {
            val prev = spatiallyDiverseReadings[i-1]
            val curr = spatiallyDiverseReadings[i]
            totalDistance += haversineDistance(prev.lat, prev.lng, curr.lat, curr.lng)
        }
        
        // R² calculation
        var ssRes = 0.0; var ssTot = 0.0
        for ((x, y, z) in points) {
            val predicted = meanZ + gradX * (x - meanX) + gradY * (y - meanY)
            ssRes += (z - predicted) * (z - predicted)
            ssTot += (z - meanZ) * (z - meanZ)
        }
        val rSquared = if (ssTot > 1e-10) (1 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0
        
        val hasSignificant = gradientMagnitude > 0.001f && rSquared > 0.3
        val confidence = (
            (spatiallyDiverseReadings.size / 10.0).coerceAtMost(1.0) * 0.3 +
            (totalDistance / 50.0).coerceAtMost(1.0) * 0.3 +
            rSquared * 0.4
        ).toFloat()
        
        return SpatialGradientResult(
            gradientMagnitude = gradientMagnitude,
            gradientBearing = bearingDeg,
            confidence = confidence,
            samplesUsed = spatiallyDiverseReadings.size,
            distanceCovered = totalDistance.toFloat(),
            hasSignificantGradient = hasSignificant,
            sourceDirection = sourceDirection
        )
    }

    /**
     * Feature #15: Hotspot Prediction & Interpolation
     */
    private fun calculateHotspotPrediction(): HotspotPredictionResult {
        val readings = geoReadings.toList()
        if (readings.size < 5) {  // Need at least 5 readings
            return HotspotPredictionResult.NONE
        }
        
        // Find highest reading
        val maxReading = readings.maxByOrNull { it.reading } ?: return HotspotPredictionResult.NONE
        
        // Get bounds
        val lats = readings.map { it.lat }
        val lngs = readings.map { it.lng }
        val minLat = lats.minOrNull() ?: return HotspotPredictionResult.NONE
        val maxLat = lats.maxOrNull() ?: return HotspotPredictionResult.NONE
        val minLng = lngs.minOrNull() ?: return HotspotPredictionResult.NONE
        val maxLng = lngs.maxOrNull() ?: return HotspotPredictionResult.NONE
        
        val metersPerDegLat = 111320.0
        val metersPerDegLng = 111320.0 * cos(Math.toRadians((minLat + maxLat) / 2))
        val gridSpacingLat = HOTSPOT_GRID_SIZE_METERS / metersPerDegLat
        val gridSpacingLng = HOTSPOT_GRID_SIZE_METERS / metersPerDegLng
        
        val interpolatedGrid = mutableMapOf<String, Float>()
        val predictedHotspots = mutableListOf<HotspotPredictionResult.PredictedPoint>()
        
        // IDW interpolation across grid
        var gridLat = minLat - gridSpacingLat * 2
        while (gridLat <= maxLat + gridSpacingLat * 2) {
            var gridLng = minLng - gridSpacingLng * 2
            while (gridLng <= maxLng + gridSpacingLng * 2) {
                var sumWeighted = 0.0
                var sumWeights = 0.0
                
                for (r in readings) {
                    val dist = haversineDistance(gridLat, gridLng, r.lat, r.lng)
                    if (dist < 1.0) {
                        sumWeighted = r.reading.toDouble()
                        sumWeights = 1.0
                        break
                    }
                    val weight = 1.0 / (dist * dist)
                    sumWeighted += weight * r.reading
                    sumWeights += weight
                }
                
                if (sumWeights > 0) {
                    val interpolated = (sumWeighted / sumWeights).toFloat()
                    val hexId = latLngToHexId(gridLat, gridLng)
                    interpolatedGrid[hexId] = interpolated
                }
                gridLng += gridSpacingLng
            }
            gridLat += gridSpacingLat
        }
        
        // Find top predicted points
        val sortedGrid = interpolatedGrid.entries.sortedByDescending { it.value }
        val currentLat = currentLatitude
        val currentLng = currentLongitude
        
        for ((idx, entry) in sortedGrid.take(10).withIndex()) {
            // Approximate center of hex
            val parts = entry.key.split(",")
            if (parts.size != 2) continue
            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2
            
            // Check distance from existing predictions
            val tooClose = predictedHotspots.any { p ->
                val pLat = p.bearing // Actually we need to track lat/lng differently
                false // Simplified for now
            }
            
            if (!tooClose && predictedHotspots.size < 3) {
                val dist = if (currentLat != 0.0) 
                    haversineDistance(currentLat, currentLng, centerLat, centerLng).toFloat() 
                else 0f
                    
                var bearing = 0f
                if (currentLat != 0.0) {
                    val dLng = Math.toRadians(centerLng - currentLng)
                    val lat1 = Math.toRadians(currentLat)
                    val lat2 = Math.toRadians(centerLat)
                    val x = sin(dLng) * cos(lat2)
                    val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
                    bearing = Math.toDegrees(atan2(x, y)).toFloat()
                    if (bearing < 0) bearing += 360f
                }
                
                predictedHotspots.add(HotspotPredictionResult.PredictedPoint(
                    hexId = entry.key,
                    predictedValue = entry.value,
                    uncertainty = entry.value * 0.1f,
                    bearing = bearing,
                    distance = dist
                ))
            }
        }
        
        // Calculate bearing and distance to max reading
        var bearingToHighest = 0f
        var distToHighest = 0f
        if (currentLat != 0.0) {
            distToHighest = haversineDistance(currentLat, currentLng, maxReading.lat, maxReading.lng).toFloat()
            val dLng = Math.toRadians(maxReading.lng - currentLng)
            val lat1 = Math.toRadians(currentLat)
            val lat2 = Math.toRadians(maxReading.lat)
            val x = sin(dLng) * cos(lat2)
            val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            bearingToHighest = Math.toDegrees(atan2(x, y)).toFloat()
            if (bearingToHighest < 0) bearingToHighest += 360f
        }
        
        val confidence = (readings.size / 20f).coerceAtMost(1f)
        
        return HotspotPredictionResult(
            predictedHotspots = predictedHotspots,
            interpolatedGrid = interpolatedGrid,
            highestPrediction = maxReading.reading,
            highestPredictionBearing = bearingToHighest,
            highestPredictionDistance = distToHighest,
            confidence = confidence
        )
    }

    /**
     * Haversine distance between two lat/lng points in meters.
     */
    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Convert lat/lng to hex ID.
     */
    private fun latLngToHexId(lat: Double, lng: Double): String {
        val hexSize = 50.0
        val metersPerDegLat = 111320.0
        val metersPerDegLng = 111320.0 * cos(Math.toRadians(lat))
        
        val x = lng * metersPerDegLng
        val y = lat * metersPerDegLat
        
        val q = ((2.0 / 3.0 * x) / hexSize).toInt()
        val r = ((-1.0 / 3.0 * x + sqrt(3.0) / 3.0 * y) / hexSize).toInt()
        
        return "$q,$r"
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
