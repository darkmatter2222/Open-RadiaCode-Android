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
 * Displays a radiation map widget with hexagonal grid overlay on real OSM tiles.
 * The widget shows collected radiation data points over an actual map background.
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
        private const val DEFAULT_ZOOM = 17  // Higher zoom for better detail
        
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
            
            // Get widget dimensions - use MAX values for actual size when resized
            // MIN values are the minimum guaranteed size, MAX gives us actual rendered size
            val density = context.resources.displayMetrics.density
            val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
            val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250) ?: 250
            
            // Use max dimensions if available (actual size), otherwise fall back to min
            val widthDp = if (maxWidth > 0) maxWidth else minWidth
            val heightDp = if (maxHeight > 0) maxHeight else minHeight
            
            // Convert dp to pixels - multiply by density for crisp rendering
            val widgetWidth = (widthDp * density).toInt().coerceAtLeast(300)
            val widgetHeight = (heightDp * density).toInt().coerceAtLeast(300)
            
            android.util.Log.d("RadiaCode", "MapWidget dimensions: ${widthDp}dp x ${heightDp}dp -> ${widgetWidth}px x ${widgetHeight}px (density=$density)")
            
            // Apply background based on opacity AND border settings
            val bgDrawable = getBackgroundDrawable(config.backgroundOpacity, config.showBorder)
            views.setInt(R.id.mapWidgetRoot, "setBackgroundResource", bgDrawable)
            
            // Render the map as a bitmap at full resolution
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
         * Render the map visualization as a bitmap with real map tiles.
         */
        private fun renderMapBitmap(
            context: Context,
            config: MapWidgetConfig,
            width: Int,
            height: Int,
            density: Float
        ): Bitmap {
            val actualWidth = width.coerceAtLeast(200)
            val actualHeight = height.coerceAtLeast(200)
            
            val bitmap = Bitmap.createBitmap(actualWidth, actualHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Get map data points
            val dataPoints = Prefs.getMapDataPoints(context)
            
            // Determine center and zoom
            val center = if (dataPoints.isNotEmpty()) {
                val latest = dataPoints.maxByOrNull { it.timestampMs }
                if (latest != null) {
                    Pair(latest.latitude, latest.longitude)
                } else {
                    MapTileLoader.getLastKnownCenter(context)
                }
            } else {
                MapTileLoader.getLastKnownCenter(context)
            }
            
            if (center == null) {
                // No location data - show placeholder
                drawNoLocationPlaceholder(canvas, actualWidth, actualHeight, density, config)
                return bitmap
            }
            
            // Try to load actual map tiles
            val mapBackground = try {
                MapTileLoader.loadMapBitmapSync(
                    context,
                    center.first,
                    center.second,
                    DEFAULT_ZOOM,
                    actualWidth,
                    actualHeight,
                    config.mapTheme
                )
            } catch (e: Exception) {
                android.util.Log.w("RadiaCode", "Failed to load map tiles", e)
                null
            }
            
            if (mapBackground != null) {
                canvas.drawBitmap(mapBackground, 0f, 0f, null)
                mapBackground.recycle()
            } else {
                // Draw fallback background
                drawFallbackBackground(canvas, actualWidth, actualHeight, config)
            }
            
            if (dataPoints.isEmpty()) {
                // Draw "No data" message overlay
                drawNoDataOverlay(canvas, actualWidth, actualHeight, density)
                return bitmap
            }
            
            // Calculate bounds for data overlay
            val bounds = calculateBounds(dataPoints, center.first, center.second, actualWidth, actualHeight, DEFAULT_ZOOM)
            
            // Calculate min/max values for color scaling
            val values = when (config.metricType) {
                MapMetricType.DOSE_RATE -> dataPoints.map { it.uSvPerHour }
                MapMetricType.COUNT_RATE -> dataPoints.map { it.cps }
            }
            val minVal = values.minOrNull() ?: 0f
            val maxVal = values.maxOrNull() ?: 1f
            
            // Draw radiation overlay
            if (config.showHexagonGrid) {
                drawHexagonOverlay(context, canvas, dataPoints, bounds, config, minVal, maxVal, density)
            } else {
                drawCircleOverlay(canvas, dataPoints, bounds, config, minVal, maxVal, density)
            }
            
            // Draw scale indicator
            drawScaleIndicator(canvas, actualWidth, actualHeight, density, minVal, maxVal, config.metricType)
            
            // Draw current position marker
            drawPositionMarker(canvas, center.first, center.second, bounds, density)
            
            return bitmap
        }
        
        private data class MapBounds(
            val centerLat: Double,
            val centerLng: Double,
            val latPerPixel: Double,
            val lngPerPixel: Double,
            val width: Int,
            val height: Int
        )
        
        private fun calculateBounds(
            dataPoints: List<Prefs.MapDataPoint>,
            centerLat: Double,
            centerLng: Double,
            width: Int,
            height: Int,
            zoom: Int
        ): MapBounds {
            // Calculate degrees per pixel at this zoom level
            val metersPerPixel = MapTileLoader.metersPerPixel(centerLat, zoom)
            val metersPerDegreeLat = 111320.0
            val metersPerDegreeLng = 111320.0 * cos(Math.toRadians(centerLat))
            
            val latPerPixel = metersPerPixel / metersPerDegreeLat
            val lngPerPixel = metersPerPixel / metersPerDegreeLng
            
            return MapBounds(centerLat, centerLng, latPerPixel, lngPerPixel, width, height)
        }
        
        private fun latLngToScreen(lat: Double, lng: Double, bounds: MapBounds): Pair<Float, Float> {
            val dx = (lng - bounds.centerLng) / bounds.lngPerPixel
            val dy = (bounds.centerLat - lat) / bounds.latPerPixel  // Y is inverted
            
            val x = bounds.width / 2 + dx
            val y = bounds.height / 2 + dy
            
            return Pair(x.toFloat(), y.toFloat())
        }
        
        private fun drawNoLocationPlaceholder(
            canvas: Canvas,
            width: Int,
            height: Int,
            density: Float,
            config: MapWidgetConfig
        ) {
            // Draw themed background
            val bgColor = getThemeBackgroundColor(config.mapTheme)
            canvas.drawColor(bgColor)
            
            // Draw subtle grid
            drawSubtleGrid(canvas, width, height, density, config)
            
            // Draw message
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9E9EA8")
                textSize = 14f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("📍 No location data", width / 2f, height / 2f - 10 * density, textPaint)
            
            textPaint.textSize = 11f * density
            canvas.drawText("Open the app to start mapping", width / 2f, height / 2f + 15 * density, textPaint)
        }
        
        private fun drawFallbackBackground(canvas: Canvas, width: Int, height: Int, config: MapWidgetConfig) {
            val bgColor = getThemeBackgroundColor(config.mapTheme)
            val darkBgColor = darkenColor(bgColor, 0.85f)
            
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    bgColor, darkBgColor,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            
            // Subtle grid
            drawSubtleGrid(canvas, width, height, 1f, config)
        }
        
        private fun drawSubtleGrid(canvas: Canvas, width: Int, height: Int, density: Float, config: MapWidgetConfig) {
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = getThemeGridColor(config.mapTheme)
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            
            val gridSpacing = 40f * density
            var x = gridSpacing
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += gridSpacing
            }
            var y = gridSpacing
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += gridSpacing
            }
        }
        
        private fun drawNoDataOverlay(canvas: Canvas, width: Int, height: Int, density: Float) {
            // Semi-transparent overlay
            val overlayPaint = Paint().apply {
                color = Color.argb(100, 0, 0, 0)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f * density
                textAlign = Paint.Align.CENTER
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText("No radiation data", width / 2f, height / 2f, textPaint)
        }
        
        private fun drawHexagonOverlay(
            context: Context,
            canvas: Canvas,
            dataPoints: List<Prefs.MapDataPoint>,
            bounds: MapBounds,
            config: MapWidgetConfig,
            minVal: Float,
            maxVal: Float,
            density: Float
        ) {
            // Group data into hexagons
            val hexagonData = mutableMapOf<String, MutableList<Prefs.MapDataPoint>>()
            dataPoints.forEach { point ->
                val hexId = latLngToHexId(context, point.latitude, point.longitude)
                hexagonData.getOrPut(hexId) { mutableListOf() }.add(point)
            }
            
            val hexFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                isFilterBitmap = true
            }
            val hexStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = Color.argb(120, 255, 255, 255)
            }
            
            // Calculate hex size in pixels based on zoom - use larger size for visibility
            val metersPerPixel = MapTileLoader.metersPerPixel(bounds.centerLat, DEFAULT_ZOOM)
            // Don't multiply by density again - metersPerPixel already gives us the right scale
            // Use a larger minimum for better visibility on high-DPI screens
            val hexSizePixels = (HEX_SIZE_METERS / metersPerPixel).toFloat().coerceIn(12f * density, 40f * density)
            
            hexagonData.forEach { (_, points) ->
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
                hexFillPaint.color = Color.argb(160, Color.red(color), Color.green(color), Color.blue(color))
                
                // Use the first point's location for hex center
                val centerPoint = points.first()
                val screenPos = latLngToScreen(centerPoint.latitude, centerPoint.longitude, bounds)
                
                // Only draw if on screen
                if (screenPos.first >= -hexSizePixels && screenPos.first <= bounds.width + hexSizePixels &&
                    screenPos.second >= -hexSizePixels && screenPos.second <= bounds.height + hexSizePixels) {
                    
                    val hexPath = createHexagonPath(screenPos.first, screenPos.second, hexSizePixels)
                    canvas.drawPath(hexPath, hexFillPaint)
                    canvas.drawPath(hexPath, hexStrokePaint)
                }
            }
        }
        
        private fun drawCircleOverlay(
            canvas: Canvas,
            dataPoints: List<Prefs.MapDataPoint>,
            bounds: MapBounds,
            @Suppress("UNUSED_PARAMETER") config: MapWidgetConfig,
            minVal: Float,
            maxVal: Float,
            density: Float
        ) {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                isFilterBitmap = true
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = Color.argb(150, 255, 255, 255)
            }
            
            // Larger radius for better visibility
            val radius = 10f * density
            
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
                circlePaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
                
                val screenPos = latLngToScreen(point.latitude, point.longitude, bounds)
                
                // Only draw if on screen
                if (screenPos.first >= -radius && screenPos.first <= bounds.width + radius &&
                    screenPos.second >= -radius && screenPos.second <= bounds.height + radius) {
                    canvas.drawCircle(screenPos.first, screenPos.second, radius, circlePaint)
                    canvas.drawCircle(screenPos.first, screenPos.second, radius, strokePaint)
                }
            }
        }
        
        private fun drawPositionMarker(
            canvas: Canvas,
            lat: Double,
            lng: Double,
            bounds: MapBounds,
            density: Float
        ) {
            val screenPos = latLngToScreen(lat, lng, bounds)
            
            // Draw position dot with glow - larger for visibility
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5000E5FF")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(screenPos.first, screenPos.second, 20f * density, glowPaint)
            
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(screenPos.first, screenPos.second, 6f * density, dotPaint)
            
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }
            canvas.drawCircle(screenPos.first, screenPos.second, 6f * density, borderPaint)
        }
        
        private fun latLngToHexId(context: Context, lat: Double, lng: Double): String {
            Prefs.ensureMapGridOrigin(context, lat, lng)
            val origin = Prefs.getMapGridOrigin(context) ?: Pair(lat, lng)
            val axial = HexGrid.latLngToAxial(lat, lng, HexGrid.Origin(origin.first, origin.second), HEX_SIZE_METERS)
            return axial.toString()
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
                Prefs.MapTheme.DARK_MATTER -> Color.parseColor("#262626")
                Prefs.MapTheme.DARK_GRAY -> Color.parseColor("#1A1A1A")
                Prefs.MapTheme.VOYAGER -> Color.parseColor("#F5F3F2")
                Prefs.MapTheme.POSITRON -> Color.parseColor("#E5E5E5")
                Prefs.MapTheme.TONER -> Color.parseColor("#FFFFFF")
                Prefs.MapTheme.STANDARD -> Color.parseColor("#F2EFE9")
            }
        }
        
        private fun getThemeGridColor(theme: Prefs.MapTheme): Int {
            return when (theme) {
                Prefs.MapTheme.HOME,
                Prefs.MapTheme.DARK_MATTER,
                Prefs.MapTheme.DARK_GRAY,
                Prefs.MapTheme.TONER -> Color.argb(25, 255, 255, 255)
                else -> Color.argb(20, 0, 0, 0)
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
            val padding = 10f * density
            val barWidth = 70f * density
            val barHeight = 10f * density
            
            // Background for scale
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 0, 0, 0)
            }
            val bgRect = RectF(
                width - padding - barWidth - 8 * density,
                height - padding - barHeight - 22 * density,
                width - padding + 4 * density,
                height - padding + 4 * density
            )
            canvas.drawRoundRect(bgRect, 6 * density, 6 * density, bgPaint)
            
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
            canvas.drawRoundRect(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 3f * density, 3f * density, gradientPaint)
            
            // Labels
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
