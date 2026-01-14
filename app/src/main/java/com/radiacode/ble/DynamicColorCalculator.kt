package com.radiacode.ble

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic Color Calculator for Radiation Levels
 * 
 * Provides color interpolation based on radiation values:
 * - Blue zone: Very low levels
 * - Green zone: Safe/normal levels
 * - Yellow zone: Elevated/attention needed
 * - Red zone: Warning/danger levels
 * 
 * Thresholds are customizable per-user preferences.
 */
object DynamicColorCalculator {

    private const val COLOR_BLUE = 0xFF40C4FF.toInt()      // Cool blue
    private const val COLOR_GREEN = 0xFF69F0AE.toInt()     // Pro green
    private const val COLOR_AMBER = 0xFFFFD740.toInt()     // Pro amber
    private const val COLOR_RED = 0xFFFF5252.toInt()       // Pro red

    /**
     * Calculate color based on dose rate value.
     * 
     * @param doseRate Dose rate in μSv/h
     * @param thresholds Custom thresholds or null for defaults
     * @return Color int for the given radiation level
     */
    fun getColorForDose(doseRate: Float, thresholds: DynamicColorThresholds? = null): Int {
        val t = thresholds ?: DynamicColorThresholds.DEFAULT
        
        return when {
            doseRate < t.lowThreshold -> {
                // Blue zone - interpolate intensity
                val intensity = doseRate / t.lowThreshold
                interpolateColor(brighten(COLOR_BLUE), COLOR_BLUE, intensity)
            }
            doseRate < t.normalThreshold -> {
                // Green zone - interpolate from blue to green
                val progress = (doseRate - t.lowThreshold) / (t.normalThreshold - t.lowThreshold)
                interpolateColor(COLOR_BLUE, COLOR_GREEN, progress)
            }
            doseRate < t.elevatedThreshold -> {
                // Amber zone - interpolate from green to amber
                val progress = (doseRate - t.normalThreshold) / (t.elevatedThreshold - t.normalThreshold)
                interpolateColor(COLOR_GREEN, COLOR_AMBER, progress)
            }
            else -> {
                // Red zone - interpolate from amber to red
                val overage = min((doseRate - t.elevatedThreshold) / t.elevatedThreshold, 1f)
                interpolateColor(COLOR_AMBER, COLOR_RED, min(overage * 2f, 1f))
            }
        }
    }

    /**
     * Calculate color based on count rate (CPS).
     * Uses rough conversion: ~30 CPS ≈ 1.0 μSv/h for typical gamma
     * 
     * @param cps Count rate in counts per second
     * @param thresholds Custom thresholds or null for defaults
     * @return Color int for the given count rate
     */
    fun getColorForCps(cps: Float, thresholds: DynamicColorThresholds? = null): Int {
        // Rough conversion from CPS to μSv/h (varies by isotope and detector)
        val estimatedDose = cps / 30f  // ~30 CPS per μSv/h as rough estimate
        return getColorForDose(estimatedDose, thresholds)
    }

    /**
     * Get a descriptive label for the radiation level.
     */
    fun getLevelLabel(doseRate: Float, thresholds: DynamicColorThresholds? = null): String {
        val t = thresholds ?: DynamicColorThresholds.DEFAULT
        return when {
            doseRate < t.lowThreshold -> "Very Low"
            doseRate < t.normalThreshold -> "Normal"
            doseRate < t.elevatedThreshold -> "Elevated"
            else -> "High"
        }
    }

    /**
     * Get a risk percentage (0-100) for display.
     */
    fun getRiskPercentage(doseRate: Float, thresholds: DynamicColorThresholds? = null): Int {
        val t = thresholds ?: DynamicColorThresholds.DEFAULT
        return when {
            doseRate < t.lowThreshold -> ((doseRate / t.lowThreshold) * 20).toInt()
            doseRate < t.normalThreshold -> (20 + ((doseRate - t.lowThreshold) / (t.normalThreshold - t.lowThreshold)) * 30).toInt()
            doseRate < t.elevatedThreshold -> (50 + ((doseRate - t.normalThreshold) / (t.elevatedThreshold - t.normalThreshold)) * 25).toInt()
            else -> min(75 + ((doseRate - t.elevatedThreshold) / t.elevatedThreshold * 25).toInt(), 100)
        }
    }

    /**
     * Interpolate between two colors.
     */
    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)
        
        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)
        
        val a = (startA + (endA - startA) * f).toInt()
        val r = (startR + (endR - startR) * f).toInt()
        val g = (startG + (endG - startG) * f).toInt()
        val b = (startB + (endB - startB) * f).toInt()
        
        return Color.argb(a, r, g, b)
    }

    /**
     * Brighten a color slightly.
     */
    private fun brighten(color: Int): Int {
        val r = min(255, Color.red(color) + 30)
        val g = min(255, Color.green(color) + 30)
        val b = min(255, Color.blue(color) + 30)
        return Color.argb(Color.alpha(color), r, g, b)
    }
}
