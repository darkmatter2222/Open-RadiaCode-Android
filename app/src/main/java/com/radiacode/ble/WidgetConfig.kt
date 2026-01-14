package com.radiacode.ble

import java.util.UUID

/**
 * Configuration for a home screen widget instance.
 * Each widget can be bound to a specific device and customized independently.
 */
data class WidgetConfig(
    val widgetId: Int,
    val deviceId: String? = null,  // null = "All Devices" aggregate
    val chartType: ChartType = ChartType.LINE,
    val showDose: Boolean = true,
    val showCps: Boolean = true,
    val showTime: Boolean = true,
    val showStatus: Boolean = true,
    val showSparkline: Boolean = true,
    val updateIntervalSeconds: Int = 1,  // 1 = real-time, 5, 30, 60, 300
    val timeWindowSeconds: Int = 60,  // Chart time window
    val colorScheme: ColorScheme = ColorScheme.DEFAULT,
    val customColors: CustomColors? = null,
    val layoutTemplate: LayoutTemplate = LayoutTemplate.DUAL_SPARKLINE,
    val aggregationSeconds: Int = 1,  // For bar/candle charts: 1, 10, 60, 300, 900, 3600
    val dynamicColorEnabled: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val customColorsJson = customColors?.toJson() ?: "null"
        return """{"widgetId":$widgetId,"deviceId":${if (deviceId != null) "\"$deviceId\"" else "null"},"chartType":"${chartType.name}","showDose":$showDose,"showCps":$showCps,"showTime":$showTime,"showStatus":$showStatus,"showSparkline":$showSparkline,"updateIntervalSeconds":$updateIntervalSeconds,"timeWindowSeconds":$timeWindowSeconds,"colorScheme":"${colorScheme.name}","customColors":$customColorsJson,"layoutTemplate":"${layoutTemplate.name}","aggregationSeconds":$aggregationSeconds,"dynamicColorEnabled":$dynamicColorEnabled,"createdAtMs":$createdAtMs}"""
    }

    companion object {
        fun fromJson(json: String): WidgetConfig? {
            return try {
                val widgetId = json.substringAfter("\"widgetId\":").substringBefore(",").toInt()
                val deviceIdRaw = json.substringAfter("\"deviceId\":").substringBefore(",")
                val deviceId = if (deviceIdRaw == "null") null else deviceIdRaw.trim('"')
                val chartType = try {
                    ChartType.valueOf(json.substringAfter("\"chartType\":\"").substringBefore("\""))
                } catch (_: Exception) { ChartType.LINE }
                val showDose = json.substringAfter("\"showDose\":").substringBefore(",").toBoolean()
                val showCps = json.substringAfter("\"showCps\":").substringBefore(",").toBoolean()
                val showTime = json.substringAfter("\"showTime\":").substringBefore(",").toBooleanStrictOrNull() ?: true
                val showStatus = json.substringAfter("\"showStatus\":").substringBefore(",").toBooleanStrictOrNull() ?: true
                val showSparkline = json.substringAfter("\"showSparkline\":").substringBefore(",").toBooleanStrictOrNull() ?: true
                val updateIntervalSeconds = json.substringAfter("\"updateIntervalSeconds\":").substringBefore(",").toIntOrNull() ?: 1
                val timeWindowSeconds = json.substringAfter("\"timeWindowSeconds\":").substringBefore(",").toIntOrNull() ?: 60
                val colorScheme = try {
                    ColorScheme.valueOf(json.substringAfter("\"colorScheme\":\"").substringBefore("\""))
                } catch (_: Exception) { ColorScheme.DEFAULT }
                val layoutTemplate = try {
                    LayoutTemplate.valueOf(json.substringAfter("\"layoutTemplate\":\"").substringBefore("\""))
                } catch (_: Exception) { LayoutTemplate.DUAL_SPARKLINE }
                val aggregationSeconds = json.substringAfter("\"aggregationSeconds\":").substringBefore(",").toIntOrNull() ?: 1
                val dynamicColorEnabled = json.substringAfter("\"dynamicColorEnabled\":").substringBefore(",").substringBefore("}").toBooleanStrictOrNull() ?: false
                val createdAtMs = json.substringAfter("\"createdAtMs\":").substringBefore("}").toLongOrNull() ?: System.currentTimeMillis()
                
                // Parse custom colors if present
                val customColors = if (json.contains("\"customColors\":null") || !json.contains("\"customColors\":")) {
                    null
                } else {
                    val customColorsJson = json.substringAfter("\"customColors\":{").substringBefore("},")
                    CustomColors.fromJson("{$customColorsJson}")
                }

                WidgetConfig(
                    widgetId = widgetId,
                    deviceId = deviceId,
                    chartType = chartType,
                    showDose = showDose,
                    showCps = showCps,
                    showTime = showTime,
                    showStatus = showStatus,
                    showSparkline = showSparkline,
                    updateIntervalSeconds = updateIntervalSeconds,
                    timeWindowSeconds = timeWindowSeconds,
                    colorScheme = colorScheme,
                    customColors = customColors,
                    layoutTemplate = layoutTemplate,
                    aggregationSeconds = aggregationSeconds,
                    dynamicColorEnabled = dynamicColorEnabled,
                    createdAtMs = createdAtMs
                )
            } catch (e: Exception) {
                android.util.Log.e("WidgetConfig", "Failed to parse: $json", e)
                null
            }
        }
        
        /**
         * Create a default config for a new widget.
         */
        fun createDefault(widgetId: Int): WidgetConfig {
            return WidgetConfig(widgetId = widgetId)
        }
    }
}

/**
 * Chart visualization type.
 */
enum class ChartType {
    LINE,       // Standard line chart with gradient fill
    BAR,        // Aggregated bar chart
    CANDLE,     // Candlestick OHLC chart
    SPARKLINE,  // Minimal sparkline (no axes)
    AREA,       // Filled area chart
    NONE        // No chart, values only
}

/**
 * Pre-defined color schemes for widgets.
 */
enum class ColorScheme(
    val displayName: String,
    val lineColor: String,
    val fillColorTop: String,
    val fillColorBottom: String,
    val backgroundColor: String,
    val textPrimary: String,
    val textSecondary: String
) {
    DEFAULT(
        "Default (Cyan/Magenta)",
        "00E5FF", "00E5FF", "00E5FF",
        "1A1A1E", "FFFFFF", "9E9EA8"
    ),
    CYBERPUNK(
        "Cyberpunk",
        "FF00FF", "FF00FF", "00FFFF",
        "0D0D1A", "00FFFF", "FF00FF"
    ),
    FOREST(
        "Forest",
        "69F0AE", "69F0AE", "2E7D32",
        "1B2E1B", "A5D6A7", "69F0AE"
    ),
    OCEAN(
        "Ocean",
        "40C4FF", "40C4FF", "0277BD",
        "0D1B2A", "81D4FA", "40C4FF"
    ),
    FIRE(
        "Fire",
        "FF5722", "FF5722", "BF360C",
        "2A1B0D", "FFAB91", "FF5722"
    ),
    GRAYSCALE(
        "Grayscale",
        "BDBDBD", "9E9E9E", "616161",
        "1A1A1A", "EEEEEE", "9E9E9E"
    ),
    AMBER(
        "Amber Alert",
        "FFD740", "FFD740", "FF6F00",
        "2A2100", "FFE082", "FFD740"
    ),
    PURPLE(
        "Purple Haze",
        "B388FF", "B388FF", "7C4DFF",
        "1A0D2A", "D1C4E9", "B388FF"
    ),
    CUSTOM(
        "Custom",
        "00E5FF", "00E5FF", "00E5FF",
        "1A1A1E", "FFFFFF", "9E9EA8"
    )
}

/**
 * Custom colors when ColorScheme.CUSTOM is selected.
 */
data class CustomColors(
    val lineColor: String = "00E5FF",
    val fillColorTop: String = "00E5FF",
    val fillColorBottom: String = "00E5FF",
    val backgroundColor: String = "1A1A1E",
    val textPrimary: String = "FFFFFF",
    val textSecondary: String = "9E9EA8",
    val doseColor: String = "00E5FF",
    val cpsColor: String = "E040FB"
) {
    fun toJson(): String {
        return """{"lineColor":"$lineColor","fillColorTop":"$fillColorTop","fillColorBottom":"$fillColorBottom","backgroundColor":"$backgroundColor","textPrimary":"$textPrimary","textSecondary":"$textSecondary","doseColor":"$doseColor","cpsColor":"$cpsColor"}"""
    }

    companion object {
        fun fromJson(json: String): CustomColors? {
            return try {
                CustomColors(
                    lineColor = json.substringAfter("\"lineColor\":\"").substringBefore("\""),
                    fillColorTop = json.substringAfter("\"fillColorTop\":\"").substringBefore("\""),
                    fillColorBottom = json.substringAfter("\"fillColorBottom\":\"").substringBefore("\""),
                    backgroundColor = json.substringAfter("\"backgroundColor\":\"").substringBefore("\""),
                    textPrimary = json.substringAfter("\"textPrimary\":\"").substringBefore("\""),
                    textSecondary = json.substringAfter("\"textSecondary\":\"").substringBefore("\""),
                    doseColor = json.substringAfter("\"doseColor\":\"").substringBefore("\"").ifEmpty { "00E5FF" },
                    cpsColor = json.substringAfter("\"cpsColor\":\"").substringBefore("\"").ifEmpty { "E040FB" }
                )
            } catch (_: Exception) { null }
        }
    }
}

/**
 * Pre-built layout templates for widgets.
 */
enum class LayoutTemplate(
    val displayName: String,
    val description: String,
    val showDose: Boolean = true,
    val showCps: Boolean = true,
    val showTime: Boolean = true,
    val showStatus: Boolean = true,
    val showSparkline: Boolean = true,
    val chartType: ChartType = ChartType.SPARKLINE
) {
    COMPACT_TEXT(
        "Compact Text",
        "Just dose + time, minimal footprint",
        showDose = true, showCps = false, showTime = true, 
        showStatus = true, showSparkline = false, chartType = ChartType.NONE
    ),
    DUAL_SPARKLINE(
        "Dual Sparkline",
        "Both metrics with mini charts",
        showDose = true, showCps = true, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.SPARKLINE
    ),
    CHART_FOCUS(
        "Chart Focus",
        "Large chart, compact values",
        showDose = true, showCps = true, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.LINE
    ),
    DATA_DENSE(
        "Data Dense",
        "Everything including stats",
        showDose = true, showCps = true, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.LINE
    ),
    SINGLE_METRIC_DOSE(
        "Dose Only",
        "Large dose rate display with sparkline",
        showDose = true, showCps = false, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.SPARKLINE
    ),
    SINGLE_METRIC_CPS(
        "Count Rate Only",
        "Large count rate display with sparkline",
        showDose = false, showCps = true, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.SPARKLINE
    ),
    CANDLE_CHART(
        "Candlestick",
        "OHLC candlestick chart for trend analysis",
        showDose = true, showCps = false, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.CANDLE
    ),
    BAR_CHART(
        "Bar Chart",
        "Aggregated bar chart view",
        showDose = true, showCps = false, showTime = true,
        showStatus = true, showSparkline = true, chartType = ChartType.BAR
    )
}

/**
 * Dynamic color thresholds for value-based coloring.
 */
data class DynamicColorThresholds(
    val lowThreshold: Float = 0.05f,      // Below this: cool blue
    val normalThreshold: Float = 0.20f,   // Below this: green
    val elevatedThreshold: Float = 0.50f, // Below this: amber, above: red
    val lowColor: String = "40C4FF",      // Cool blue
    val normalColor: String = "69F0AE",   // Green
    val elevatedColor: String = "FFD740", // Amber
    val highColor: String = "FF5252"      // Red
) {
    fun getColorForValue(uSvH: Float): String {
        return when {
            uSvH < lowThreshold -> lowColor
            uSvH < normalThreshold -> normalColor
            uSvH < elevatedThreshold -> elevatedColor
            else -> highColor
        }
    }
    
    fun toJson(): String {
        return """{"lowThreshold":$lowThreshold,"normalThreshold":$normalThreshold,"elevatedThreshold":$elevatedThreshold,"lowColor":"$lowColor","normalColor":"$normalColor","elevatedColor":"$elevatedColor","highColor":"$highColor"}"""
    }
    
    companion object {
        val DEFAULT = DynamicColorThresholds()
        
        fun fromJson(json: String): DynamicColorThresholds? {
            return try {
                DynamicColorThresholds(
                    lowThreshold = json.substringAfter("\"lowThreshold\":").substringBefore(",").toFloat(),
                    normalThreshold = json.substringAfter("\"normalThreshold\":").substringBefore(",").toFloat(),
                    elevatedThreshold = json.substringAfter("\"elevatedThreshold\":").substringBefore(",").toFloat(),
                    lowColor = json.substringAfter("\"lowColor\":\"").substringBefore("\""),
                    normalColor = json.substringAfter("\"normalColor\":\"").substringBefore("\""),
                    elevatedColor = json.substringAfter("\"elevatedColor\":\"").substringBefore("\""),
                    highColor = json.substringAfter("\"highColor\":\"").substringBefore("\"")
                )
            } catch (_: Exception) { null }
        }
    }
}
