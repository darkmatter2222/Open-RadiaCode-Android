package com.radiacode.ble

/**
 * Configuration for a map widget instance.
 * Each map widget displays radiation data overlaid on an OSM map.
 */
data class MapWidgetConfig(
    val widgetId: Int,
    val deviceId: String? = null,  // null = "All Devices" aggregate
    val metricType: MapMetricType = MapMetricType.DOSE_RATE,
    val mapTheme: Prefs.MapTheme = Prefs.MapTheme.HOME,
    val zoomLevel: Double = 17.0,
    val centerLat: Double? = null,  // null = auto-center on data
    val centerLng: Double? = null,
    val showCurrentLocation: Boolean = true,
    val showHexagonGrid: Boolean = true,
    val showTemperature: Boolean = false,
    val showBattery: Boolean = false,
    val backgroundOpacity: Int = 100,  // 0, 25, 50, 75, 100 percent
    val showBorder: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return """{"widgetId":$widgetId,"deviceId":${if (deviceId != null) "\"$deviceId\"" else "null"},"metricType":"${metricType.name}","mapTheme":"${mapTheme.name}","zoomLevel":$zoomLevel,"centerLat":${centerLat ?: "null"},"centerLng":${centerLng ?: "null"},"showCurrentLocation":$showCurrentLocation,"showHexagonGrid":$showHexagonGrid,"showTemperature":$showTemperature,"showBattery":$showBattery,"backgroundOpacity":$backgroundOpacity,"showBorder":$showBorder,"createdAtMs":$createdAtMs}"""
    }

    companion object {
        fun fromJson(json: String): MapWidgetConfig? {
            return try {
                val widgetId = json.substringAfter("\"widgetId\":").substringBefore(",").toInt()
                val deviceIdRaw = json.substringAfter("\"deviceId\":").substringBefore(",")
                val deviceId = if (deviceIdRaw == "null") null else deviceIdRaw.trim('"')
                val metricType = try {
                    MapMetricType.valueOf(json.substringAfter("\"metricType\":\"").substringBefore("\""))
                } catch (_: Exception) { MapMetricType.DOSE_RATE }
                val mapTheme = try {
                    Prefs.MapTheme.valueOf(json.substringAfter("\"mapTheme\":\"").substringBefore("\""))
                } catch (_: Exception) { Prefs.MapTheme.HOME }
                val zoomLevel = json.substringAfter("\"zoomLevel\":").substringBefore(",").toDoubleOrNull() ?: 17.0
                val centerLatStr = json.substringAfter("\"centerLat\":").substringBefore(",")
                val centerLat = if (centerLatStr == "null") null else centerLatStr.toDoubleOrNull()
                val centerLngStr = json.substringAfter("\"centerLng\":").substringBefore(",")
                val centerLng = if (centerLngStr == "null") null else centerLngStr.toDoubleOrNull()
                val showCurrentLocation = json.substringAfter("\"showCurrentLocation\":").substringBefore(",").toBooleanStrictOrNull() ?: true
                val showHexagonGrid = json.substringAfter("\"showHexagonGrid\":").substringBefore(",").toBooleanStrictOrNull() ?: true
                val showTemperature = json.substringAfter("\"showTemperature\":").substringBefore(",").toBooleanStrictOrNull() ?: false
                val showBattery = json.substringAfter("\"showBattery\":").substringBefore(",").toBooleanStrictOrNull() ?: false
                val backgroundOpacity = json.substringAfter("\"backgroundOpacity\":").substringBefore(",").substringBefore("}").toIntOrNull() ?: 100
                val showBorder = json.substringAfter("\"showBorder\":").substringBefore(",").substringBefore("}").toBooleanStrictOrNull() ?: false
                val createdAtMs = json.substringAfter("\"createdAtMs\":").substringBefore("}").toLongOrNull() ?: System.currentTimeMillis()
                
                MapWidgetConfig(
                    widgetId = widgetId,
                    deviceId = deviceId,
                    metricType = metricType,
                    mapTheme = mapTheme,
                    zoomLevel = zoomLevel,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    showCurrentLocation = showCurrentLocation,
                    showHexagonGrid = showHexagonGrid,
                    showTemperature = showTemperature,
                    showBattery = showBattery,
                    backgroundOpacity = backgroundOpacity,
                    showBorder = showBorder,
                    createdAtMs = createdAtMs
                )
            } catch (e: Exception) {
                android.util.Log.e("MapWidgetConfig", "Failed to parse: $json", e)
                null
            }
        }
        
        /**
         * Create a default config for a new map widget.
         */
        fun createDefault(widgetId: Int): MapWidgetConfig {
            return MapWidgetConfig(widgetId = widgetId)
        }
    }
}

/**
 * Metric type to display on the map widget.
 */
enum class MapMetricType {
    DOSE_RATE,
    COUNT_RATE
}
