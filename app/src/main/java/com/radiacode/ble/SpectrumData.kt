package com.radiacode.ble

/**
 * Represents raw gamma spectrum data from the RadiaCode device.
 * Contains calibration coefficients and channel counts.
 */
data class SpectrumData(
    /** Accumulation time in seconds */
    val durationSeconds: Int,
    /** Energy calibration: offset (keV) */
    val a0: Float,
    /** Energy calibration: linear coefficient (keV/channel) */
    val a1: Float,
    /** Energy calibration: quadratic coefficient (keV/channel²) */
    val a2: Float,
    /** Raw counts per channel (typically 1024 channels) */
    val counts: IntArray,
    /** Timestamp when this spectrum was read */
    val timestampMs: Long
) {
    /** Number of channels in this spectrum */
    val numChannels: Int get() = counts.size
    
    /** Total counts across all channels */
    val totalCounts: Long get() = counts.sumOf { it.toLong() }
    
    /**
     * Convert channel number to energy in keV using calibration coefficients.
     * Energy(keV) = a0 + a1 * channel + a2 * channel²
     */
    fun channelToEnergy(channel: Int): Float {
        return a0 + a1 * channel + a2 * channel * channel
    }
    
    /**
     * Convert energy in keV to channel number (inverse of channelToEnergy).
     * Uses quadratic formula; returns the positive root.
     */
    fun energyToChannel(energyKeV: Float): Int {
        // Solve: a2*x² + a1*x + (a0 - E) = 0
        // For small a2, this is approximately: channel ≈ (E - a0) / a1
        return if (kotlin.math.abs(a2) < 1e-9f) {
            // Linear approximation
            ((energyKeV - a0) / a1).toInt().coerceIn(0, numChannels - 1)
        } else {
            // Quadratic formula: x = (-a1 + sqrt(a1² - 4*a2*(a0-E))) / (2*a2)
            val discriminant = a1 * a1 - 4f * a2 * (a0 - energyKeV)
            if (discriminant < 0) {
                // Fallback to linear
                ((energyKeV - a0) / a1).toInt().coerceIn(0, numChannels - 1)
            } else {
                val channel = (-a1 + kotlin.math.sqrt(discriminant)) / (2f * a2)
                channel.toInt().coerceIn(0, numChannels - 1)
            }
        }
    }
    
    /**
     * Get counts in an energy window (in keV).
     * Returns the sum of counts in channels corresponding to the energy range.
     */
    fun getCountsInEnergyWindow(lowKeV: Float, highKeV: Float): Long {
        val lowChannel = energyToChannel(lowKeV)
        val highChannel = energyToChannel(highKeV)
        val startCh = minOf(lowChannel, highChannel)
        val endCh = maxOf(lowChannel, highChannel)
        
        var sum = 0L
        for (ch in startCh..endCh.coerceAtMost(numChannels - 1)) {
            sum += counts[ch]
        }
        return sum
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SpectrumData
        return durationSeconds == other.durationSeconds &&
               a0 == other.a0 && a1 == other.a1 && a2 == other.a2 &&
               counts.contentEquals(other.counts) &&
               timestampMs == other.timestampMs
    }
    
    override fun hashCode(): Int {
        var result = durationSeconds
        result = 31 * result + a0.hashCode()
        result = 31 * result + a1.hashCode()
        result = 31 * result + a2.hashCode()
        result = 31 * result + counts.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
    
    /**
     * Add counts from another spectrum to this one.
     * Used for accumulating differential spectra over time.
     */
    operator fun plus(other: SpectrumData): SpectrumData {
        val newCounts = IntArray(counts.size) { i ->
            counts[i] + (other.counts.getOrNull(i) ?: 0)
        }
        return copy(
            durationSeconds = durationSeconds + other.durationSeconds,
            counts = newCounts,
            timestampMs = System.currentTimeMillis()
        )
    }
    
    companion object {
        /**
         * Create an empty spectrum with the given calibration.
         */
        fun empty(a0: Float, a1: Float, a2: Float, numChannels: Int = 1024): SpectrumData {
            return SpectrumData(
                durationSeconds = 0,
                a0 = a0,
                a1 = a1,
                a2 = a2,
                counts = IntArray(numChannels),
                timestampMs = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Energy calibration coefficients for channel-to-energy conversion.
 */
data class EnergyCalibration(
    /** Offset in keV */
    val a0: Float,
    /** Linear coefficient (keV per channel) */
    val a1: Float,
    /** Quadratic coefficient (keV per channel²) */
    val a2: Float
) {
    /**
     * Convert channel number to energy in keV.
     */
    fun channelToEnergy(channel: Int): Float {
        return a0 + a1 * channel + a2 * channel * channel
    }
    
    /**
     * Convert energy in keV to channel number.
     */
    fun energyToChannel(energyKeV: Float, maxChannel: Int = 1023): Int {
        return if (kotlin.math.abs(a2) < 1e-9f) {
            ((energyKeV - a0) / a1).toInt().coerceIn(0, maxChannel)
        } else {
            val discriminant = a1 * a1 - 4f * a2 * (a0 - energyKeV)
            if (discriminant < 0) {
                ((energyKeV - a0) / a1).toInt().coerceIn(0, maxChannel)
            } else {
                val channel = (-a1 + kotlin.math.sqrt(discriminant)) / (2f * a2)
                channel.toInt().coerceIn(0, maxChannel)
            }
        }
    }
}
