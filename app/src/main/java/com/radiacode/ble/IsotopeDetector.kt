package com.radiacode.ble

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Isotope identification engine using Region of Interest (ROI) analysis.
 * Analyzes gamma spectra to identify probable isotopes based on characteristic peaks.
 * 
 * Algorithm overview:
 * 1. For each enabled isotope, define energy windows around known gamma lines
 * 2. Sum counts in each ROI and compare to local background
 * 3. Calculate probability scores using sigmoid activation
 * 4. Compute fractional contributions (normalized to sum ~1)
 */
class IsotopeDetector(
    private val enabledIsotopes: Set<String> = IsotopeLibrary.DEFAULT_ENABLED
) {
    companion object {
        private const val TAG = "IsotopeDetector"
        
        // ROI window parameters
        private const val PEAK_WINDOW_KEV = 25f       // ± keV around peak center
        private const val BACKGROUND_MARGIN_KEV = 15f // Margin between peak and background regions
        private const val BACKGROUND_WIDTH_KEV = 30f  // Width of background regions
        
        // Probability calculation parameters
        private const val SIGMOID_SCALE = 35f         // Steepness of sigmoid
        private const val SIGMOID_OFFSET = 0.8f       // Threshold offset
        
        // Minimum counts threshold
        private const val MIN_PEAK_COUNTS = 10
    }
    
    /**
     * Result of isotope identification.
     */
    data class Prediction(
        /** Isotope identifier */
        val isotopeId: String,
        /** Display name */
        val name: String,
        /** Category */
        val category: IsotopeLibrary.Category,
        /** Probability score (0-1) */
        val probability: Float,
        /** Fractional contribution to spectrum (0-1) */
        val fraction: Float,
        /** Raw evidence score (counts above background) */
        val evidence: Float,
        /** Primary peak energy (keV) */
        val primaryPeakKeV: Float,
        /** Whether this is a decay chain (detected via daughters) */
        val isDecayChain: Boolean
    )
    
    /**
     * Full analysis result.
     */
    data class AnalysisResult(
        /** All predictions, sorted by probability */
        val predictions: List<Prediction>,
        /** Top 5 predictions */
        val topFive: List<Prediction>,
        /** Timestamp of analysis */
        val timestampMs: Long,
        /** Total spectrum counts */
        val totalCounts: Long,
        /** Spectrum duration (seconds) */
        val durationSeconds: Int
    ) {
        /** Get prediction for specific isotope */
        fun getPrediction(isotopeId: String): Prediction? = 
            predictions.find { it.isotopeId == isotopeId }
    }
    
    /**
     * Analyze a spectrum and identify probable isotopes.
     */
    fun analyze(spectrum: SpectrumData): AnalysisResult {
        val results = mutableListOf<Prediction>()
        val evidenceMap = mutableMapOf<String, Float>()
        
        // Calculate evidence for each enabled isotope
        for (isotopeId in enabledIsotopes) {
            val isotope = IsotopeLibrary.get(isotopeId) ?: continue
            val evidence = calculateEvidence(spectrum, isotope)
            evidenceMap[isotopeId] = evidence
        }
        
        // Normalize evidence to get fractions
        val totalEvidence = evidenceMap.values.sum() + 0.001f // Avoid division by zero
        
        // Unknown/Background fraction
        val knownFraction = evidenceMap.values.sum() / max(totalEvidence * 1.25f, 1f)
        val unknownFraction = max(0f, 1f - knownFraction)
        
        // Calculate probabilities and fractions
        for (isotopeId in enabledIsotopes) {
            val isotope = IsotopeLibrary.get(isotopeId) ?: continue
            val evidence = evidenceMap[isotopeId] ?: 0f
            
            // Sigmoid probability: converts evidence to 0-1 probability
            val probability = sigmoid(evidence * SIGMOID_SCALE - SIGMOID_OFFSET)
            
            // Fraction: normalized evidence
            val fraction = evidence / totalEvidence
            
            results.add(Prediction(
                isotopeId = isotope.id,
                name = isotope.name,
                category = isotope.category,
                probability = probability,
                fraction = fraction,
                evidence = evidence,
                primaryPeakKeV = isotope.primaryLine.energyKeV,
                isDecayChain = isotope.parentChain != null || 
                               isotope.id.contains("Chain") || 
                               isotope.gammaLines.size > 2
            ))
        }
        
        // Add unknown/background
        results.add(Prediction(
            isotopeId = "Unknown",
            name = "Background/Unknown",
            category = IsotopeLibrary.Category.NATURAL,
            probability = max(0f, 1f - (results.maxOfOrNull { it.probability } ?: 0f)),
            fraction = unknownFraction,
            evidence = 0f,
            primaryPeakKeV = 0f,
            isDecayChain = false
        ))
        
        // Sort by probability descending
        val sorted = results.sortedByDescending { it.probability }
        
        return AnalysisResult(
            predictions = sorted,
            topFive = sorted.take(5),
            timestampMs = System.currentTimeMillis(),
            totalCounts = spectrum.totalCounts,
            durationSeconds = spectrum.durationSeconds
        )
    }
    
    /**
     * Calculate evidence score for an isotope from the spectrum.
     * Uses ROI analysis: compare peak region counts to local background.
     */
    private fun calculateEvidence(spectrum: SpectrumData, isotope: IsotopeLibrary.Isotope): Float {
        var totalEvidence = 0f
        var weightSum = 0f
        
        for (line in isotope.gammaLines) {
            val peakEnergyKeV = line.energyKeV
            val weight = line.intensity * (if (line.isPrimary) 2f else 1f)
            
            // Skip if peak is outside spectrum range
            val maxEnergy = spectrum.channelToEnergy(spectrum.numChannels - 1)
            if (peakEnergyKeV > maxEnergy || peakEnergyKeV < 0) continue
            
            // Get ROI counts
            val peakLowKeV = peakEnergyKeV - PEAK_WINDOW_KEV
            val peakHighKeV = peakEnergyKeV + PEAK_WINDOW_KEV
            val peakCounts = spectrum.getCountsInEnergyWindow(peakLowKeV, peakHighKeV)
            
            // Get background regions (on either side of peak)
            val bgLowStart = peakLowKeV - BACKGROUND_MARGIN_KEV - BACKGROUND_WIDTH_KEV
            val bgLowEnd = peakLowKeV - BACKGROUND_MARGIN_KEV
            val bgHighStart = peakHighKeV + BACKGROUND_MARGIN_KEV
            val bgHighEnd = peakHighKeV + BACKGROUND_MARGIN_KEV + BACKGROUND_WIDTH_KEV
            
            val bgLowCounts = spectrum.getCountsInEnergyWindow(bgLowStart, bgLowEnd)
            val bgHighCounts = spectrum.getCountsInEnergyWindow(bgHighStart, bgHighEnd)
            
            // Estimate background in peak region (interpolate)
            val peakWidth = (spectrum.energyToChannel(peakHighKeV) - spectrum.energyToChannel(peakLowKeV)).toFloat()
            val bgWidth = (spectrum.energyToChannel(bgLowEnd) - spectrum.energyToChannel(bgLowStart) +
                          spectrum.energyToChannel(bgHighEnd) - spectrum.energyToChannel(bgHighStart)).toFloat()
            
            val bgDensity = if (bgWidth > 0) (bgLowCounts + bgHighCounts).toFloat() / bgWidth else 0f
            val estimatedBackground = bgDensity * peakWidth
            
            // Net counts (peak above background)
            val netCounts = max(0f, peakCounts - estimatedBackground)
            
            // Evidence: normalized net counts
            val totalCounts = spectrum.totalCounts.toFloat() + 1f
            val lineEvidence = netCounts / totalCounts
            
            totalEvidence += lineEvidence * weight
            weightSum += weight
        }
        
        return if (weightSum > 0) totalEvidence / weightSum else 0f
    }
    
    /**
     * Sigmoid activation function.
     */
    private fun sigmoid(x: Float): Float {
        return (1f / (1f + exp(-x))).coerceIn(0f, 1f)
    }
    
    /**
     * Quick single-isotope check (e.g., for specific alerting).
     */
    fun checkIsotope(spectrum: SpectrumData, isotopeId: String): Prediction? {
        val isotope = IsotopeLibrary.get(isotopeId) ?: return null
        val evidence = calculateEvidence(spectrum, isotope)
        val probability = sigmoid(evidence * SIGMOID_SCALE - SIGMOID_OFFSET)
        
        return Prediction(
            isotopeId = isotope.id,
            name = isotope.name,
            category = isotope.category,
            probability = probability,
            fraction = evidence,
            evidence = evidence,
            primaryPeakKeV = isotope.primaryLine.energyKeV,
            isDecayChain = isotope.parentChain != null
        )
    }
}

/**
 * History of isotope predictions for time-series display.
 */
class IsotopePredictionHistory(
    private val maxSize: Int = 300  // ~5 minutes at 1Hz
) {
    private val history = mutableListOf<IsotopeDetector.AnalysisResult>()
    
    /**
     * Add a new analysis result to history.
     */
    fun add(result: IsotopeDetector.AnalysisResult) {
        synchronized(history) {
            history.add(result)
            while (history.size > maxSize) {
                history.removeAt(0)
            }
        }
    }
    
    /**
     * Clear all history.
     */
    fun clear() {
        synchronized(history) {
            history.clear()
        }
    }
    
    /**
     * Get all history entries.
     */
    fun getAll(): List<IsotopeDetector.AnalysisResult> {
        synchronized(history) {
            return history.toList()
        }
    }
    
    /**
     * Get history within a time window.
     */
    fun getRecent(windowMs: Long): List<IsotopeDetector.AnalysisResult> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history.filter { it.timestampMs >= cutoff }
        }
    }
    
    /**
     * Get time series data for a specific isotope.
     * Returns list of (timestamp, probability) pairs.
     */
    fun getTimeSeries(isotopeId: String, windowMs: Long): List<Pair<Long, Float>> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history
                .filter { it.timestampMs >= cutoff }
                .mapNotNull { result ->
                    result.getPrediction(isotopeId)?.let { pred ->
                        result.timestampMs to pred.probability
                    }
                }
        }
    }
    
    /**
     * Get fraction time series for a specific isotope.
     */
    fun getFractionTimeSeries(isotopeId: String, windowMs: Long): List<Pair<Long, Float>> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history
                .filter { it.timestampMs >= cutoff }
                .mapNotNull { result ->
                    result.getPrediction(isotopeId)?.let { pred ->
                        result.timestampMs to pred.fraction
                    }
                }
        }
    }
    
    /**
     * Get current top N isotopes by probability.
     */
    fun getCurrentTop(n: Int = 5): List<IsotopeDetector.Prediction> {
        synchronized(history) {
            return history.lastOrNull()?.topFive?.take(n) ?: emptyList()
        }
    }
    
    val size: Int get() = synchronized(history) { history.size }
    
    val isEmpty: Boolean get() = synchronized(history) { history.isEmpty() }
}
