package com.radiacode.ble

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Statistics Calculator for Radiation Data Analysis
 * 
 * Provides comprehensive statistical analysis including:
 * - Descriptive statistics (mean, median, std dev, etc.)
 * - Trend analysis (linear regression)
 * - Anomaly detection (z-score, IQR methods)
 * - Moving averages
 * - Rate of change calculations
 */
object StatisticsCalculator {

    /**
     * Calculate comprehensive statistics for a list of values.
     */
    fun calculateStatistics(values: List<Float>): Statistics {
        if (values.isEmpty()) {
            return Statistics.EMPTY
        }
        
        val n = values.size
        val sum = values.sum()
        val mean = sum / n
        
        // Standard deviation
        val variance = values.map { (it - mean).pow(2) }.sum() / n
        val stdDev = sqrt(variance)
        
        // Sorted for percentiles
        val sorted = values.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val median = if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        } else {
            sorted[n / 2]
        }
        
        // Quartiles for IQR
        val q1Index = n / 4
        val q3Index = 3 * n / 4
        val q1 = sorted.getOrElse(q1Index) { min }
        val q3 = sorted.getOrElse(q3Index) { max }
        val iqr = q3 - q1
        
        // Coefficient of variation (CV)
        val cv = if (mean > 0.0001f) stdDev / mean * 100 else 0f
        
        return Statistics(
            count = n,
            sum = sum,
            mean = mean,
            median = median,
            min = min,
            max = max,
            stdDev = stdDev,
            variance = variance,
            q1 = q1,
            q3 = q3,
            iqr = iqr,
            coefficientOfVariation = cv
        )
    }

    /**
     * Calculate linear regression trend line.
     * Returns slope (rate of change) and intercept.
     */
    fun calculateTrend(values: List<Float>): TrendResult {
        if (values.size < 2) {
            return TrendResult(slope = 0f, intercept = values.firstOrNull() ?: 0f, rSquared = 0f)
        }
        
        val n = values.size
        val xValues = (0 until n).map { it.toFloat() }
        
        val xMean = xValues.average().toFloat()
        val yMean = values.average().toFloat()
        
        var numerator = 0f
        var denominator = 0f
        
        for (i in values.indices) {
            numerator += (xValues[i] - xMean) * (values[i] - yMean)
            denominator += (xValues[i] - xMean).pow(2)
        }
        
        val slope = if (denominator > 0.0001f) numerator / denominator else 0f
        val intercept = yMean - slope * xMean
        
        // Calculate R-squared
        val ssRes = values.mapIndexed { i, y -> 
            (y - (slope * i + intercept)).pow(2) 
        }.sum()
        val ssTot = values.map { (it - yMean).pow(2) }.sum()
        val rSquared = if (ssTot > 0.0001f) 1 - (ssRes / ssTot) else 0f
        
        return TrendResult(slope = slope, intercept = intercept, rSquared = rSquared)
    }

    /**
     * Detect anomalies using z-score method.
     * Returns indices of anomalous values.
     */
    fun detectAnomaliesZScore(values: List<Float>, threshold: Float = 2.0f): List<Int> {
        if (values.size < 3) return emptyList()
        
        val stats = calculateStatistics(values)
        if (stats.stdDev < 0.0001f) return emptyList()
        
        return values.mapIndexedNotNull { index, value ->
            val zScore = (value - stats.mean) / stats.stdDev
            if (kotlin.math.abs(zScore) > threshold) index else null
        }
    }

    /**
     * Detect anomalies using IQR method (more robust to outliers).
     * Returns indices of anomalous values.
     */
    fun detectAnomaliesIQR(values: List<Float>, multiplier: Float = 1.5f): List<Int> {
        if (values.size < 4) return emptyList()
        
        val stats = calculateStatistics(values)
        val lowerBound = stats.q1 - multiplier * stats.iqr
        val upperBound = stats.q3 + multiplier * stats.iqr
        
        return values.mapIndexedNotNull { index, value ->
            if (value < lowerBound || value > upperBound) index else null
        }
    }

    /**
     * Calculate simple moving average.
     */
    fun simpleMovingAverage(values: List<Float>, windowSize: Int): List<Float> {
        if (values.isEmpty() || windowSize <= 0) return emptyList()
        
        return values.windowed(windowSize, 1, partialWindows = true) { window ->
            window.average().toFloat()
        }
    }

    /**
     * Calculate exponential moving average.
     */
    fun exponentialMovingAverage(values: List<Float>, alpha: Float = 0.2f): List<Float> {
        if (values.isEmpty()) return emptyList()
        
        val ema = mutableListOf<Float>()
        var currentEma = values.first()
        ema.add(currentEma)
        
        for (i in 1 until values.size) {
            currentEma = alpha * values[i] + (1 - alpha) * currentEma
            ema.add(currentEma)
        }
        
        return ema
    }

    /**
     * Calculate rate of change (percentage).
     */
    fun rateOfChange(values: List<Float>, period: Int = 1): List<Float> {
        if (values.size <= period) return emptyList()
        
        return (period until values.size).map { i ->
            val previous = values[i - period]
            val current = values[i]
            if (previous > 0.0001f) {
                (current - previous) / previous * 100
            } else {
                0f
            }
        }
    }
    
    /**
     * Calculate rate of change between first and last values in a list.
     * Returns the absolute change per sample (useful for trend indicators).
     */
    fun calculateRateOfChange(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val first = values.first()
        val last = values.last()
        return (last - first) / values.size
    }

    /**
     * Predict next value using linear regression.
     */
    fun predictNextValue(values: List<Float>): Float {
        val trend = calculateTrend(values)
        return trend.slope * values.size + trend.intercept
    }
    
    /**
     * Calculate Pearson correlation coefficient between two series.
     * Returns value from -1 (perfect negative) to +1 (perfect positive).
     */
    fun calculateCorrelation(xValues: List<Float>, yValues: List<Float>): Float {
        if (xValues.size < 2 || yValues.size < 2) return 0f
        
        val n = minOf(xValues.size, yValues.size)
        if (n < 2) return 0f
        
        val xMean = xValues.take(n).average().toFloat()
        val yMean = yValues.take(n).average().toFloat()
        
        var numerator = 0f
        var xSumSq = 0f
        var ySumSq = 0f
        
        for (i in 0 until n) {
            val xDiff = xValues[i] - xMean
            val yDiff = yValues[i] - yMean
            numerator += xDiff * yDiff
            xSumSq += xDiff * xDiff
            ySumSq += yDiff * yDiff
        }
        
        val denominator = sqrt(xSumSq * ySumSq)
        return if (denominator > 0.0001f) numerator / denominator else 0f
    }
    
    /**
     * Calculate Poisson goodness-of-fit chi-squared statistic.
     * For radiation counting, values should follow Poisson distribution.
     * Returns chi-squared value and p-value approximation.
     */
    fun calculatePoissonGoodnessOfFit(counts: List<Float>): PoissonFitResult {
        if (counts.size < 10) return PoissonFitResult(0f, 1f, FitQuality.INSUFFICIENT_DATA)
        
        val mean = counts.average().toFloat()
        val variance = counts.map { (it - mean).pow(2) }.average().toFloat()
        
        // For Poisson, variance should equal mean
        // Chi-squared = (n-1) * variance / mean
        val n = counts.size
        val chiSquared = (n - 1) * variance / mean
        
        // Degrees of freedom = n - 1
        val df = n - 1
        
        // Simplified p-value approximation using normal approximation to chi-squared
        // For large df, chi-squared is approximately normal with mean=df, variance=2*df
        val zScore = (chiSquared - df) / sqrt(2.0 * df).toFloat()
        
        // Determine fit quality
        val fitQuality = when {
            kotlin.math.abs(zScore) < 1.0f -> FitQuality.EXCELLENT  // Within 1 sigma
            kotlin.math.abs(zScore) < 2.0f -> FitQuality.GOOD      // Within 2 sigma
            kotlin.math.abs(zScore) < 3.0f -> FitQuality.FAIR      // Within 3 sigma
            else -> FitQuality.POOR                                  // Beyond 3 sigma
        }
        
        // Variance-to-mean ratio (should be ~1 for Poisson)
        val dispersionIndex = variance / mean
        
        return PoissonFitResult(chiSquared, dispersionIndex, fitQuality, zScore)
    }
    
    /**
     * Calculate confidence interval for the mean.
     * Uses t-distribution approximation for small samples.
     * @param values Data points
     * @param confidenceLevel 0.90, 0.95, or 0.99
     * @return ConfidenceInterval with lower, upper bounds and margin of error
     */
    fun calculateConfidenceInterval(values: List<Float>, confidenceLevel: Float = 0.95f): ConfidenceInterval {
        if (values.size < 2) {
            val value = values.firstOrNull() ?: 0f
            return ConfidenceInterval(value, value, 0f, confidenceLevel)
        }
        
        val stats = calculateStatistics(values)
        val n = values.size
        
        // t-values for common confidence levels (approximations for n > 30)
        // For smaller samples, these are conservative estimates
        val tValue = when {
            confidenceLevel >= 0.99f -> if (n > 30) 2.576f else 2.8f + 0.5f / n
            confidenceLevel >= 0.95f -> if (n > 30) 1.96f else 2.0f + 0.3f / n
            else -> if (n > 30) 1.645f else 1.7f + 0.2f / n  // 90%
        }
        
        // Standard error of the mean
        val standardError = stats.stdDev / sqrt(n.toFloat())
        
        // Margin of error
        val marginOfError = tValue * standardError
        
        return ConfidenceInterval(
            lower = stats.mean - marginOfError,
            upper = stats.mean + marginOfError,
            marginOfError = marginOfError,
            confidenceLevel = confidenceLevel
        )
    }
    
    /**
     * Calculate confidence interval for count rate (Poisson-based).
     * For radiation counting, this uses the Poisson distribution.
     */
    fun calculateCountRateCI(counts: List<Float>, confidenceLevel: Float = 0.95f): ConfidenceInterval {
        if (counts.isEmpty()) return ConfidenceInterval(0f, 0f, 0f, confidenceLevel)
        
        val totalCounts = counts.sum()
        val n = counts.size
        val meanRate = totalCounts / n
        
        // For Poisson, CI based on chi-squared distribution
        // Simplified using normal approximation for sqrt(counts)
        val sqrtCounts = sqrt(totalCounts)
        
        val zValue = when {
            confidenceLevel >= 0.99f -> 2.576f
            confidenceLevel >= 0.95f -> 1.96f
            else -> 1.645f
        }
        
        // Variance of mean is lambda/n for Poisson
        val standardError = sqrt(meanRate / n)
        val marginOfError = zValue * standardError
        
        return ConfidenceInterval(
            lower = maxOf(0f, meanRate - marginOfError),
            upper = meanRate + marginOfError,
            marginOfError = marginOfError,
            confidenceLevel = confidenceLevel
        )
    }

    /**
     * Calculate Bollinger Bands.
     * Returns middle band (SMA), upper band (SMA + k*stdDev), lower band (SMA - k*stdDev)
     */
    fun calculateBollingerBands(
        values: List<Float>,
        windowSize: Int = 20,
        numStdDev: Float = 2.0f
    ): BollingerBands {
        if (values.size < windowSize) {
            return BollingerBands(emptyList(), emptyList(), emptyList())
        }
        
        val middleBand = mutableListOf<Float>()
        val upperBand = mutableListOf<Float>()
        val lowerBand = mutableListOf<Float>()
        
        for (i in (windowSize - 1) until values.size) {
            val window = values.subList(i - windowSize + 1, i + 1)
            val mean = window.average().toFloat()
            val stdDev = sqrt(window.map { (it - mean).pow(2) }.average().toFloat())
            
            middleBand.add(mean)
            upperBand.add(mean + numStdDev * stdDev)
            lowerBand.add(mean - numStdDev * stdDev)
        }
        
        return BollingerBands(middleBand, upperBand, lowerBand)
    }

    /**
     * Calculate z-score for a single value against a dataset.
     */
    fun calculateZScore(value: Float, mean: Float, stdDev: Float): Float {
        return if (stdDev > 0.0001f) (value - mean) / stdDev else 0f
    }

    /**
     * Get trend direction and strength description.
     */
    fun getTrendDescription(slope: Float, stats: Statistics): TrendDescription {
        val percentChangePerUnit = if (stats.mean > 0.0001f) slope / stats.mean * 100 else 0f
        
        val direction = when {
            slope > 0.0001f -> TrendDirection.INCREASING
            slope < -0.0001f -> TrendDirection.DECREASING
            else -> TrendDirection.STABLE
        }
        
        val strength = when {
            kotlin.math.abs(percentChangePerUnit) < 1 -> TrendStrength.VERY_WEAK
            kotlin.math.abs(percentChangePerUnit) < 5 -> TrendStrength.WEAK
            kotlin.math.abs(percentChangePerUnit) < 15 -> TrendStrength.MODERATE
            kotlin.math.abs(percentChangePerUnit) < 30 -> TrendStrength.STRONG
            else -> TrendStrength.VERY_STRONG
        }
        
        return TrendDescription(direction, strength, percentChangePerUnit)
    }
}

/**
 * Comprehensive statistics result.
 */
data class Statistics(
    val count: Int,
    val sum: Float,
    val mean: Float,
    val median: Float,
    val min: Float,
    val max: Float,
    val stdDev: Float,
    val variance: Float,
    val q1: Float,
    val q3: Float,
    val iqr: Float,
    val coefficientOfVariation: Float
) {
    companion object {
        val EMPTY = Statistics(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Linear regression result.
 */
data class TrendResult(
    val slope: Float,
    val intercept: Float,
    val rSquared: Float
)

/**
 * Trend description for UI display.
 */
data class TrendDescription(
    val direction: TrendDirection,
    val strength: TrendStrength,
    val percentChangePerUnit: Float
)

enum class TrendDirection {
    INCREASING, DECREASING, STABLE
}

enum class TrendStrength {
    VERY_WEAK, WEAK, MODERATE, STRONG, VERY_STRONG
}

/**
 * Bollinger Bands result.
 */
data class BollingerBands(
    val middle: List<Float>,  // SMA line
    val upper: List<Float>,   // Upper band (SMA + k*stdDev)
    val lower: List<Float>    // Lower band (SMA - k*stdDev)
) {
    fun isEmpty() = middle.isEmpty()
}

/**
 * Poisson goodness-of-fit test result.
 */
data class PoissonFitResult(
    val chiSquared: Float,
    val dispersionIndex: Float,  // variance/mean, should be ~1 for Poisson
    val fitQuality: FitQuality,
    val zScore: Float = 0f
) {
    fun getDescription(): String = when (fitQuality) {
        FitQuality.EXCELLENT -> "Excellent Poisson fit (dispersion: %.2f)".format(dispersionIndex)
        FitQuality.GOOD -> "Good Poisson fit (dispersion: %.2f)".format(dispersionIndex)
        FitQuality.FAIR -> "Fair fit - some deviation (dispersion: %.2f)".format(dispersionIndex)
        FitQuality.POOR -> "Poor fit - non-Poisson behavior (dispersion: %.2f)".format(dispersionIndex)
        FitQuality.INSUFFICIENT_DATA -> "Insufficient data for test"
    }
    
    fun isPoissonLike(): Boolean = fitQuality in listOf(FitQuality.EXCELLENT, FitQuality.GOOD)
}

enum class FitQuality {
    EXCELLENT, GOOD, FAIR, POOR, INSUFFICIENT_DATA
}

/**
 * Confidence interval result.
 */
data class ConfidenceInterval(
    val lower: Float,
    val upper: Float,
    val marginOfError: Float,
    val confidenceLevel: Float
) {
    fun contains(value: Float) = value in lower..upper
    
    fun getWidth() = upper - lower
    
    fun format(unit: String = ""): String {
        return "%.3f - %.3f %s (±%.3f)".format(lower, upper, unit, marginOfError)
    }
    
    fun formatPercent(): String {
        return "%.0f%% CI: ±%.1f%%".format(confidenceLevel * 100, marginOfError / ((upper + lower) / 2) * 100)
    }
}
