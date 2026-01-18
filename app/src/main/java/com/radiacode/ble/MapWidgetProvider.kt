package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlin.math.*

/**
 * Map Widget Provider
 * 
 * Displays a radiation map widget with hexagonal grid overlay on OSM tiles.
 * The widget shows collected radiation data points over a map background.
 */
class MapWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            try {
                val options = appWidgetManager.getAppWidgetOptions(id)
                appWidgetManager.updateAppWidget(id, buildViews(context, id, options))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "MapWidget onUpdate error for $id", e)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        try {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId, newOptions))
        } catch (e: Exception) {
            android.util.Log.e("RadiaCode", "MapWidget onOptionsChanged error", e)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            Prefs.deleteMapWidgetConfig(context, id)
        }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private const val HEX_SIZE_METERS = 25.0
        
        /**
         * Update all map widgets.
         */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, MapWidgetProvider::class.java))
            if (ids.isEmpty()) return

            ids.forEach { id ->
                val options = mgr.getAppWidgetOptions(id)
                try {
                    mgr.updateAppWidget(id, buildViews(context, id, options))
                } catch (e: Exception) {
                    android.util.Log.e("RadiaCode", "MapWidget updateAll error", e)
                }
            }
        }

        /**
         * Update a specific map widget by ID.
         */
        fun updateWidget(context: Context, widgetId: Int) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val options = mgr.getAppWidgetOptions(widgetId)
            try {
                mgr.updateAppWidget(widgetId, buildViews(context, widgetId, options))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "MapWidget updateWidget error for $widgetId", e)
            }
        }

        /**
         * Build RemoteViews for the map widget.
         */
        private fun buildViews(context: Context, widgetId: Int, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_map)
            
            // Get config (or create default)
            val config = Prefs.getMapWidgetConfig(context, widgetId) ?: MapWidgetConfig.createDefault(widgetId)
            
            // Get widget dimensions
            val density = context.resources.displayMetrics.density
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200) ?: 200
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200) ?: 200
            val widgetWidth = (minWidth * density).toInt()
            val widgetHeight = (minHeight * density).toInt()
            
            // Apply background based on opacity AND border settings
            val bgDrawable = getBackgroundDrawable(config.backgroundOpacity, config.showBorder)
            views.setInt(R.id.mapWidgetRoot, "setBackgroundResource", bgDrawable)
            
            // Render the map as a bitmap
            val mapBitmap = renderMapBitmap(context, config, widgetWidth, widgetHeight, density)
            views.setImageViewBitmap(R.id.mapImage, mapBitmap)
            
            // Set metric label
            val metricLabel = when (config.metricType) {
                MapMetricType.DOSE_RATE -> "☢️ DOSE"
                MapMetricType.COUNT_RATE -> "📈 CPS"
            }
            views.setTextViewText(R.id.mapMetricLabel, metricLabel)
            
            // Device metadata
            if (config.showTemperature || config.showBattery) {
                views.setViewVisibility(R.id.mapMetadataRow, View.VISIBLE)
                
                val deviceId = config.deviceId
                val metadata = if (deviceId != null) {
                    Prefs.getDeviceMetadata(context, deviceId)
                } else {
                    // Get first available device metadata
                    val allMetadata = Prefs.getAllDeviceMetadata(context)
                    allMetadata.values.firstOrNull()
                }
                
                if (config.showTemperature && metadata?.temperature != null) {
                    views.setTextViewText(R.id.mapTemperature, "🌡️ ${"%.0f".format(metadata.temperature)}°C")
                    views.setViewVisibility(R.id.mapTemperature, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.mapTemperature, View.GONE)
                }
                
                if (config.showBattery && metadata?.batteryLevel != null) {
                    views.setTextViewText(R.id.mapBattery, "🔋 ${metadata.batteryLevel}%")
                    views.setViewVisibility(R.id.mapBattery, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.mapBattery, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.mapMetadataRow, View.GONE)
            }
            
            // Get data point count for subtitle
            val dataPoints = Prefs.getMapDataPoints(context)
            val pointCount = dataPoints.size
            views.setTextViewText(R.id.mapPointCount, "$pointCount points")
            
            // Add click handler to launch fullscreen map activity
            val launchIntent = Intent(context, FullscreenMapActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, widgetId, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.mapWidgetRoot, pi)
            
            return views
        }
        
        /**
         * Render the map visualization as a bitmap.
         */
        private fun renderMapBitmap(
            context: Context,
            config: MapWidgetConfig,
            width: Int,
            height: Int,
            density: Float
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(100), height.coerceAtLeast(100), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Get map data points
            val dataPoints = Prefs.getMapDataPoints(context)
            
            // Calculate bounds
            val bounds = if (dataPoints.isNotEmpty()) {
                val minLat = dataPoints.minOf { it.latitude }
                val maxLat = dataPoints.maxOf { it.latitude }
                val minLng = dataPoints.minOf { it.longitude }
                val maxLng = dataPoints.maxOf { it.longitude }
                MapBounds(minLat, maxLat, minLng, maxLng)
            } else {
                // Default bounds (somewhere neutral)
                MapBounds(0.0, 0.001, 0.0, 0.001)
            }
            
            // Background (map-like dark gradient)
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    getThemeBackgroundColor(config.mapTheme),
                    darkenColor(getThemeBackgroundColor(config.mapTheme), 0.8f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            
            // Draw grid lines (subtle)
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = getThemeGridColor(config.mapTheme)
                strokeWidth = 1f * density
                style = Paint.Style.STROKE
            }
            
            val gridSpacing = width / 6f
            for (i in 1..5) {
                canvas.drawLine(gridSpacing * i, 0f, gridSpacing * i, height.toFloat(), gridPaint)
                canvas.drawLine(0f, gridSpacing * i, width.toFloat(), gridSpacing * i, gridPaint)
            }
            
            if (dataPoints.isEmpty()) {
                // Draw "No data" message
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#9E9EA8")
                    textSize = 14f * density
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("No map data", width / 2f, height / 2f, textPaint)
                return bitmap
            }
            
            // Group data into hexagons
            val hexagonData = mutableMapOf<String, MutableList<Prefs.MapDataPoint>>()
            dataPoints.forEach { point ->
                val hexId = latLngToHexId(point.latitude, point.longitude, bounds)
                hexagonData.getOrPut(hexId) { mutableListOf() }.add(point)
            }
            
            // Calculate min/max values for color scaling
            val values = when (config.metricType) {
                MapMetricType.DOSE_RATE -> dataPoints.map { it.uSvPerHour }
                MapMetricType.COUNT_RATE -> dataPoints.map { it.cps }
            }
            val minVal = values.minOrNull() ?: 0f
            val maxVal = values.maxOrNull() ?: 1f
            
            // Draw hexagons
            val hexFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val hexStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.argb(60, 255, 255, 255)
            }
            
            if (config.showHexagonGrid) {
                hexagonData.forEach { (hexId, points) ->
                    val avgValue = when (config.metricType) {
                        MapMetricType.DOSE_RATE -> points.map { it.uSvPerHour }.average().toFloat()
                        MapMetricType.COUNT_RATE -> points.map { it.cps }.average().toFloat()
                    }
                    
                    val normalized = if (maxVal > minVal) {
                        ((avgValue - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                    } else {
                        0.5f
                    }
                    
                    val color = interpolateRadiationColor(normalized)
                    hexFillPaint.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
                    
                    // Get hex center and draw
                    val hexCenter = hexIdToScreenPos(hexId, bounds, width, height)
                    if (hexCenter != null) {
                        val hexPath = createHexagonPath(hexCenter.first, hexCenter.second, 15f * density)
                        canvas.drawPath(hexPath, hexFillPaint)
                        canvas.drawPath(hexPath, hexStrokePaint)
                    }
                }
            } else {
                // Draw as circles instead
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                }
                
                dataPoints.forEach { point ->
                    val value = when (config.metricType) {
                        MapMetricType.DOSE_RATE -> point.uSvPerHour
                        MapMetricType.COUNT_RATE -> point.cps
                    }
                    
                    val normalized = if (maxVal > minVal) {
                        ((value - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                    } else {
                        0.5f
                    }
                    
                    val color = interpolateRadiationColor(normalized)
                    circlePaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
                    
                    val screenPos = latLngToScreenPos(point.latitude, point.longitude, bounds, width, height)
                    canvas.drawCircle(screenPos.first, screenPos.second, 6f * density, circlePaint)
                }
            }
            
            // Draw scale indicator in corner
            drawScaleIndicator(canvas, width, height, density, minVal, maxVal, config.metricType)
            
            return bitmap
        }
        
        private data class MapBounds(
            val minLat: Double,
            val maxLat: Double,
            val minLng: Double,
            val maxLng: Double
        )
        
        private fun latLngToHexId(lat: Double, lng: Double, bounds: MapBounds): String {
            val metersPerDegreeLat = 111320.0
            val metersPerDegreeLng = 111320.0 * cos(Math.toRadians(lat))
            
            val x = lng * metersPerDegreeLng
            val y = lat * metersPerDegreeLat
            
            val size = HEX_SIZE_METERS
            val q = (sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * y) / size
            val r = (2.0 / 3.0 * y) / size
            
            val (hexQ, hexR) = axialRound(q, r)
            return "$hexQ,$hexR"
        }
        
        private fun axialRound(q: Double, r: Double): Pair<Int, Int> {
            val s = -q - r
            var rq = round(q).toInt()
            var rr = round(r).toInt()
            var rs = round(s).toInt()
            
            val qDiff = abs(rq - q)
            val rDiff = abs(rr - r)
            val sDiff = abs(rs - s)
            
            if (qDiff > rDiff && qDiff > sDiff) {
                rq = -rr - rs
            } else if (rDiff > sDiff) {
                rr = -rq - rs
            }
            
            return Pair(rq, rr)
        }
        
        private fun hexIdToScreenPos(hexId: String, bounds: MapBounds, width: Int, height: Int): Pair<Float, Float>? {
            val parts = hexId.split(",")
            if (parts.size != 2) return null
            
            val q = parts[0].toIntOrNull() ?: return null
            val r = parts[1].toIntOrNull() ?: return null
            
            val size = HEX_SIZE_METERS
            
            // Convert axial to meters
            val x = size * (sqrt(3.0) * q + sqrt(3.0) / 2.0 * r)
            val y = size * (3.0 / 2.0 * r)
            
            // Convert meters back to lat/lng
            val centerLat = (bounds.minLat + bounds.maxLat) / 2
            val metersPerDegreeLat = 111320.0
            val metersPerDegreeLng = 111320.0 * cos(Math.toRadians(centerLat))
            
            val lng = x / metersPerDegreeLng
            val lat = y / metersPerDegreeLat
            
            return latLngToScreenPos(lat, lng, bounds, width, height)
        }
        
        private fun latLngToScreenPos(lat: Double, lng: Double, bounds: MapBounds, width: Int, height: Int): Pair<Float, Float> {
            val padding = 0.1f  // 10% padding
            val latRange = (bounds.maxLat - bounds.minLat).let { if (it < 0.0001) 0.001 else it }
            val lngRange = (bounds.maxLng - bounds.minLng).let { if (it < 0.0001) 0.001 else it }
            
            val x = ((lng - bounds.minLng) / lngRange * (1 - 2 * padding) + padding) * width
            val y = ((bounds.maxLat - lat) / latRange * (1 - 2 * padding) + padding) * height
            
            return Pair(x.toFloat(), y.toFloat())
        }
        
        private fun createHexagonPath(centerX: Float, centerY: Float, size: Float): Path {
            val path = Path()
            for (i in 0 until 6) {
                val angleDeg = 60.0 * i - 30.0  // Pointy-top
                val angleRad = Math.toRadians(angleDeg)
                val x = centerX + size * cos(angleRad).toFloat()
                val y = centerY + size * sin(angleRad).toFloat()
                
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            return path
        }
        
        private fun interpolateRadiationColor(normalized: Float): Int {
            val green = Color.parseColor("#69F0AE")
            val yellow = Color.parseColor("#FFD600")
            val red = Color.parseColor("#FF5252")
            
            return when {
                normalized < 0.5f -> {
                    val t = normalized * 2f
                    blendColors(green, yellow, t)
                }
                else -> {
                    val t = (normalized - 0.5f) * 2f
                    blendColors(yellow, red, t)
                }
            }
        }
        
        private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
            val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
            val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
            val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
            return Color.rgb(r, g, b)
        }
        
        private fun darkenColor(color: Int, factor: Float): Int {
            return Color.rgb(
                (Color.red(color) * factor).toInt(),
                (Color.green(color) * factor).toInt(),
                (Color.blue(color) * factor).toInt()
            )
        }
        
        private fun getThemeBackgroundColor(theme: Prefs.MapTheme): Int {
            return when (theme) {
                Prefs.MapTheme.HOME -> Color.parseColor("#1A1A1E")
                Prefs.MapTheme.DARK_MATTER -> Color.parseColor("#1A1A1E")
                Prefs.MapTheme.DARK_GRAY -> Color.parseColor("#2A2A2E")
                Prefs.MapTheme.VOYAGER -> Color.parseColor("#F2F2F2")
                Prefs.MapTheme.POSITRON -> Color.parseColor("#F2F0E6")
                Prefs.MapTheme.TONER -> Color.parseColor("#000000")
                Prefs.MapTheme.STANDARD -> Color.parseColor("#E8E4D9")
            }
        }
        
        private fun getThemeGridColor(theme: Prefs.MapTheme): Int {
            return when (theme) {
                Prefs.MapTheme.HOME,
                Prefs.MapTheme.DARK_MATTER,
                Prefs.MapTheme.DARK_GRAY,
                Prefs.MapTheme.TONER -> Color.argb(40, 255, 255, 255)
                else -> Color.argb(30, 0, 0, 0)
            }
        }
        
        private fun drawScaleIndicator(
            canvas: Canvas,
            width: Int,
            height: Int,
            density: Float,
            minVal: Float,
            maxVal: Float,
            metricType: MapMetricType
        ) {
            val padding = 8f * density
            val barWidth = 60f * density
            val barHeight = 8f * density
            
            // Gradient bar
            val barLeft = width - padding - barWidth
            val barTop = height - padding - barHeight - 16 * density
            
            val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    barLeft, barTop,
                    barLeft + barWidth, barTop,
                    Color.parseColor("#69F0AE"),
                    Color.parseColor("#FF5252"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 4f * density, 4f * density, gradientPaint)
            
            // Labels
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9f * density
                textAlign = Paint.Align.CENTER
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            
            val unit = when (metricType) {
                MapMetricType.DOSE_RATE -> "µSv/h"
                MapMetricType.COUNT_RATE -> "CPS"
            }
            
            val minText = if (minVal < 0.01f) "%.3f".format(minVal) else "%.2f".format(minVal)
            val maxText = if (maxVal < 0.01f) "%.3f".format(maxVal) else "%.2f".format(maxVal)
            
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(minText, barLeft, barTop + barHeight + 12 * density, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(maxText, barLeft + barWidth, barTop + barHeight + 12 * density, textPaint)
        }
        
        private fun getBackgroundDrawable(opacity: Int, showBorder: Boolean): Int {
            return if (showBorder) {
                when (opacity) {
                    0 -> R.drawable.widget_background_0
                    25 -> R.drawable.widget_background_25
                    50 -> R.drawable.widget_background_50
                    75 -> R.drawable.widget_background_75
                    else -> R.drawable.widget_background  // 100%
                }
            } else {
                when (opacity) {
                    0 -> R.drawable.widget_background_0_noborder
                    25 -> R.drawable.widget_background_25_noborder
                    50 -> R.drawable.widget_background_50_noborder
                    75 -> R.drawable.widget_background_75_noborder
                    else -> R.drawable.widget_background_noborder  // 100%
                }
            }
        }
    }
}
