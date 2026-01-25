package com.radiacode.ble.spectrogram

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Maps count/intensity values to colors for the spectrogram heatmap.
 * Supports multiple color schemes and handles background subtraction display.
 */
object SpectrogramColorMapper {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRO THEME COLORS (Dark → Magenta → Cyan → White)
    // Matches the app's professional dark theme aesthetic
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val PRO_THEME_STOPS = listOf(
        0.0f to Color.parseColor("#0D0D0F"),   // pro_background (zero counts)
        0.1f to Color.parseColor("#1A1A2E"),   // very dark purple
        0.25f to Color.parseColor("#3D1A4E"),  // dark magenta
        0.4f to Color.parseColor("#E040FB"),   // pro_magenta (medium counts)
        0.6f to Color.parseColor("#4DD0E1"),   // light cyan
        0.75f to Color.parseColor("#00E5FF"),  // pro_cyan (high counts)
        0.9f to Color.parseColor("#B2EBF2"),   // light cyan-white
        1.0f to Color.parseColor("#FFFFFF")    // white (max counts)
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HOT COLOR SCHEME (Black → Red → Orange → Yellow → White)
    // Classic thermal imaging style
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val HOT_STOPS = listOf(
        0.0f to Color.parseColor("#000000"),   // black
        0.2f to Color.parseColor("#8B0000"),   // dark red
        0.4f to Color.parseColor("#FF4500"),   // orange-red
        0.6f to Color.parseColor("#FF8C00"),   // dark orange
        0.8f to Color.parseColor("#FFD700"),   // gold
        1.0f to Color.parseColor("#FFFFFF")    // white
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIRIDIS COLOR SCHEME (Purple → Blue → Green → Yellow)
    // Perceptually uniform, colorblind-friendly
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val VIRIDIS_STOPS = listOf(
        0.0f to Color.parseColor("#440154"),   // dark purple
        0.2f to Color.parseColor("#3B528B"),   // blue-purple
        0.4f to Color.parseColor("#21918C"),   // teal
        0.6f to Color.parseColor("#5EC962"),   // green
        0.8f to Color.parseColor("#ADDC30"),   // yellow-green
        1.0f to Color.parseColor("#FDE725")    // yellow
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PLASMA COLOR SCHEME (Magenta → Red → Orange → Yellow)
    // High contrast, dramatic
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val PLASMA_STOPS = listOf(
        0.0f to Color.parseColor("#0D0887"),   // deep blue
        0.2f to Color.parseColor("#7E03A8"),   // purple
        0.4f to Color.parseColor("#CC4778"),   // magenta-pink
        0.6f to Color.parseColor("#F89441"),   // orange
        0.8f to Color.parseColor("#F0F921"),   // bright yellow
        1.0f to Color.parseColor("#FFFFFF")    // white
    )
    
    /**
     * Get the color stops for a given scheme.
     */
    private fun getStops(scheme: SpectrogramPrefs.ColorScheme): List<Pair<Float, Int>> {
        return when (scheme) {
            SpectrogramPrefs.ColorScheme.PRO_THEME -> PRO_THEME_STOPS
            SpectrogramPrefs.ColorScheme.HOT -> HOT_STOPS
            SpectrogramPrefs.ColorScheme.VIRIDIS -> VIRIDIS_STOPS
            SpectrogramPrefs.ColorScheme.PLASMA -> PLASMA_STOPS
        }
    }
    
    /**
     * Map a normalized intensity (0.0 - 1.0) to a color.
     * 
     * @param intensity Normalized intensity value (0.0 = min, 1.0 = max)
     * @param scheme Color scheme to use
     * @return ARGB color int
     */
    @ColorInt
    fun mapToColor(intensity: Float, scheme: SpectrogramPrefs.ColorScheme): Int {
        val normalizedIntensity = intensity.coerceIn(0f, 1f)
        val stops = getStops(scheme)
        
        // Find the two stops we're between
        var lowerStop = stops.first()
        var upperStop = stops.last()
        
        for (i in 0 until stops.size - 1) {
            if (normalizedIntensity >= stops[i].first && normalizedIntensity <= stops[i + 1].first) {
                lowerStop = stops[i]
                upperStop = stops[i + 1]
                break
            }
        }
        
        // Interpolate between the two colors
        val range = upperStop.first - lowerStop.first
        val t = if (range > 0) (normalizedIntensity - lowerStop.first) / range else 0f
        
        return interpolateColor(lowerStop.second, upperStop.second, t)
    }
    
    /**
     * Map raw counts to color using logarithmic scaling.
     * This prevents a few bright pixels from washing out the rest of the image.
     * 
     * @param counts Raw count value
     * @param maxCounts Maximum expected counts (for normalization)
     * @param scheme Color scheme to use
     * @return ARGB color int
     */
    @ColorInt
    fun mapCountsToColor(
        counts: Int,
        maxCounts: Int,
        scheme: SpectrogramPrefs.ColorScheme
    ): Int {
        if (counts <= 0 || maxCounts <= 0) {
            return getStops(scheme).first().second
        }
        
        // Logarithmic normalization for better dynamic range visualization
        val logCounts = kotlin.math.ln(counts.toFloat() + 1)
        val logMax = kotlin.math.ln(maxCounts.toFloat() + 1)
        val normalized = (logCounts / logMax).coerceIn(0f, 1f)
        
        return mapToColor(normalized, scheme)
    }
    
    /**
     * Map counts with optional background subtraction.
     * Negative values (after subtraction) are shown in a distinct color.
     */
    @ColorInt
    fun mapCountsWithBackground(
        counts: Int,
        backgroundCounts: Int,
        maxCounts: Int,
        scheme: SpectrogramPrefs.ColorScheme
    ): Int {
        val subtracted = counts - backgroundCounts
        
        // Negative values (below background) get special blue tint
        if (subtracted < 0) {
            val negativeIntensity = (-subtracted.toFloat() / maxCounts.coerceAtLeast(1)).coerceIn(0f, 1f)
            return interpolateColor(
                Color.parseColor("#0D0D0F"),  // Background
                Color.parseColor("#1565C0"),   // Deep blue for negative
                negativeIntensity * 0.5f       // Keep it subtle
            )
        }
        
        return mapCountsToColor(subtracted, maxCounts, scheme)
    }
    
    /**
     * Linear interpolation between two colors.
     */
    @ColorInt
    private fun interpolateColor(color1: Int, color2: Int, t: Float): Int {
        val a1 = Color.alpha(color1)
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)
        
        val a2 = Color.alpha(color2)
        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)
        
        return Color.argb(
            (a1 + (a2 - a1) * t).toInt(),
            (r1 + (r2 - r1) * t).toInt(),
            (g1 + (g2 - g1) * t).toInt(),
            (b1 + (b2 - b1) * t).toInt()
        )
    }
    
    /**
     * Generate a color bar/legend bitmap for the current scheme.
     * Returns an array of colors from min to max intensity.
     * 
     * @param steps Number of steps in the color bar
     * @param scheme Color scheme to use
     * @return IntArray of colors from low to high
     */
    fun generateColorBar(steps: Int, scheme: SpectrogramPrefs.ColorScheme): IntArray {
        return IntArray(steps) { i ->
            val intensity = i.toFloat() / (steps - 1).coerceAtLeast(1)
            mapToColor(intensity, scheme)
        }
    }
    
    /**
     * Get the background color for a scheme (used for zero counts).
     */
    @ColorInt
    fun getBackgroundColor(scheme: SpectrogramPrefs.ColorScheme): Int {
        return getStops(scheme).first().second
    }
    
    /**
     * Get the maximum intensity color for a scheme.
     */
    @ColorInt
    fun getMaxColor(scheme: SpectrogramPrefs.ColorScheme): Int {
        return getStops(scheme).last().second
    }
    
    /**
     * Special color for disconnected periods in the timeline.
     */
    @ColorInt
    val DISCONNECTED_COLOR = Color.parseColor("#FF5252")  // pro_red
    
    /**
     * Color for recovered (merged) data markers.
     */
    @ColorInt
    val RECOVERED_DATA_COLOR = Color.parseColor("#FFD600")  // pro_yellow
    
    /**
     * Color for gap markers in the waterfall.
     */
    @ColorInt
    val GAP_MARKER_COLOR = Color.parseColor("#2A2A2E")  // pro_border with pattern
}
