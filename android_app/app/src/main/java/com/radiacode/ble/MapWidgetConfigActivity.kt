package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.*

/**
 * Map Widget Configuration Activity
 * 
 * Configuration screen for the Open RadiaCode Maps widget.
 * Allows users to customize:
 * - Device selection
 * - Metric type (dose rate or count rate)
 * - Map theme
 * - Temperature/battery display
 * - Background opacity and border
 */
class MapWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // UI Elements
    private lateinit var previewImage: ImageView
    private lateinit var deviceSpinner: Spinner
    private lateinit var metricChips: ChipGroup
    private lateinit var themeChips: ChipGroup
    private lateinit var opacityChips: ChipGroup
    private lateinit var showHexagonsSwitch: SwitchMaterial
    private lateinit var showTemperatureSwitch: SwitchMaterial
    private lateinit var showBatterySwitch: SwitchMaterial
    private lateinit var showBorderSwitch: SwitchMaterial
    private lateinit var cancelButton: MaterialButton
    private lateinit var createButton: MaterialButton

    // State
    private var devices: List<DeviceConfig> = emptyList()
    private var selectedDeviceId: String? = null
    private var metricType = MapMetricType.DOSE_RATE
    private var mapTheme = Prefs.MapTheme.HOME
    private var backgroundOpacity = 100
    private var showHexagons = true
    private var showTemperature = false
    private var showBattery = false
    private var showBorder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set result to CANCELED in case user backs out
        setResult(RESULT_CANCELED)

        // Get widget ID
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_map_widget_config)

        bindViews()
        setupDeviceSpinner()
        setupMetricChips()
        setupThemeChips()
        setupOpacityChips()
        setupSwitches()
        setupButtons()

        // Load existing config if editing
        loadExistingConfig()

        // Initial preview render
        updatePreview()
    }

    private fun bindViews() {
        previewImage = findViewById(R.id.mapPreviewImage)
        deviceSpinner = findViewById(R.id.mapDeviceSpinner)
        metricChips = findViewById(R.id.mapMetricChips)
        themeChips = findViewById(R.id.mapThemeChips)
        opacityChips = findViewById(R.id.mapOpacityChips)
        showHexagonsSwitch = findViewById(R.id.showHexagonsSwitch)
        showTemperatureSwitch = findViewById(R.id.mapShowTemperatureSwitch)
        showBatterySwitch = findViewById(R.id.mapShowBatterySwitch)
        showBorderSwitch = findViewById(R.id.mapShowBorderSwitch)
        cancelButton = findViewById(R.id.mapCancelButton)
        createButton = findViewById(R.id.mapCreateButton)
    }

    private fun setupDeviceSpinner() {
        devices = Prefs.getDevices(this)

        if (devices.isEmpty()) {
            val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, listOf("No devices paired"))
            deviceSpinner.adapter = adapter
            deviceSpinner.isEnabled = false
            return
        }

        val deviceNames = listOf("All Devices") + devices.map { it.displayName }
        val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, deviceNames)
        adapter.setDropDownViewResource(R.layout.item_device_spinner_simple)
        deviceSpinner.adapter = adapter

        selectedDeviceId = null  // Start with "All Devices"

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeviceId = if (position == 0) null else devices.getOrNull(position - 1)?.id
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMetricChips() {
        metricChips.removeAllViews()

        val metrics = listOf(
            MapMetricType.DOSE_RATE to "☢️ Dose Rate",
            MapMetricType.COUNT_RATE to "📈 Count Rate"
        )

        metrics.forEach { (type, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = type == metricType
                tag = type
                setOnClickListener {
                    metricType = type
                    updateMetricSelection()
                    updatePreview()
                }
            }
            metricChips.addView(chip)
        }
    }

    private fun setupThemeChips() {
        themeChips.removeAllViews()

        val themes = listOf(
            Prefs.MapTheme.HOME to "Home",
            Prefs.MapTheme.DARK_MATTER to "Dark Matter",
            Prefs.MapTheme.DARK_GRAY to "Dark Gray",
            Prefs.MapTheme.TONER to "Toner"
        )

        themes.forEach { (theme, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = theme == mapTheme
                tag = theme
                setOnClickListener {
                    mapTheme = theme
                    updateThemeSelection()
                    updatePreview()
                }
            }
            themeChips.addView(chip)
        }
    }

    private fun setupOpacityChips() {
        opacityChips.removeAllViews()

        val opacities = listOf(
            0 to "0%",
            25 to "25%",
            50 to "50%",
            75 to "75%",
            100 to "100%"
        )

        opacities.forEach { (opacity, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = opacity == backgroundOpacity
                tag = opacity
                setOnClickListener {
                    backgroundOpacity = opacity
                    updateOpacitySelection()
                    updatePreview()
                }
            }
            opacityChips.addView(chip)
        }
    }

    private fun setupSwitches() {
        showHexagonsSwitch.isChecked = showHexagons
        showHexagonsSwitch.setOnCheckedChangeListener { _, isChecked ->
            showHexagons = isChecked
            updatePreview()
        }

        showTemperatureSwitch.setOnCheckedChangeListener { _, isChecked ->
            showTemperature = isChecked
            updatePreview()
        }

        showBatterySwitch.setOnCheckedChangeListener { _, isChecked ->
            showBattery = isChecked
            updatePreview()
        }

        showBorderSwitch.setOnCheckedChangeListener { _, isChecked ->
            showBorder = isChecked
            updatePreview()
        }
    }

    private fun setupButtons() {
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        createButton.setOnClickListener {
            saveConfigAndFinish()
        }
    }

    private fun loadExistingConfig() {
        val existingConfig = Prefs.getMapWidgetConfig(this, appWidgetId) ?: return

        // Restore device selection
        selectedDeviceId = existingConfig.deviceId
        val deviceIndex = if (selectedDeviceId == null) 0 else {
            val idx = devices.indexOfFirst { it.id == selectedDeviceId }
            if (idx >= 0) idx + 1 else 0
        }
        deviceSpinner.setSelection(deviceIndex)

        // Restore settings
        metricType = existingConfig.metricType
        updateMetricSelection()

        mapTheme = existingConfig.mapTheme
        updateThemeSelection()

        backgroundOpacity = existingConfig.backgroundOpacity
        updateOpacitySelection()

        showHexagons = existingConfig.showHexagonGrid
        showHexagonsSwitch.isChecked = showHexagons

        showTemperature = existingConfig.showTemperature
        showTemperatureSwitch.isChecked = showTemperature

        showBattery = existingConfig.showBattery
        showBatterySwitch.isChecked = showBattery

        showBorder = existingConfig.showBorder
        showBorderSwitch.isChecked = showBorder

        // Update button text for editing
        createButton.text = "Save Widget"
    }

    private fun updateMetricSelection() {
        for (i in 0 until metricChips.childCount) {
            val chip = metricChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == metricType
        }
    }

    private fun updateThemeSelection() {
        for (i in 0 until themeChips.childCount) {
            val chip = themeChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == mapTheme
        }
    }

    private fun updateOpacitySelection() {
        for (i in 0 until opacityChips.childCount) {
            val chip = opacityChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == backgroundOpacity
        }
    }

    private fun updatePreview() {
        val config = buildConfig()
        val density = resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (200 * density).toInt()
        
        val bitmap = renderPreviewBitmap(config, width, height, density)
        previewImage.setImageBitmap(bitmap)
    }

    private fun renderPreviewBitmap(config: MapWidgetConfig, width: Int, height: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Get map data points
        val dataPoints = Prefs.getMapDataPoints(this)
        
        // Calculate bounds
        val bounds = if (dataPoints.isNotEmpty()) {
            val minLat = dataPoints.minOf { it.latitude }
            val maxLat = dataPoints.maxOf { it.latitude }
            val minLng = dataPoints.minOf { it.longitude }
            val maxLng = dataPoints.maxOf { it.longitude }
            MapBounds(minLat, maxLat, minLng, maxLng)
        } else {
            MapBounds(0.0, 0.001, 0.0, 0.001)
        }
        
        // Draw rounded rect background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (config.backgroundOpacity) {
                0 -> Color.TRANSPARENT
                25 -> Color.argb(64, 26, 26, 30)
                50 -> Color.argb(128, 26, 26, 30)
                75 -> Color.argb(192, 26, 26, 30)
                else -> Color.parseColor("#1A1A1E")
            }
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16 * density, 16 * density, bgPaint)
        
        // Border if enabled
        if (config.showBorder) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.parseColor("#2A2A2E")
                strokeWidth = 1f * density
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16 * density, 16 * density, borderPaint)
        }
        
        // Map content area (with padding for labels)
        val contentPadding = 12f * density
        val headerHeight = 24f * density
        val contentTop = contentPadding + headerHeight
        val contentBottom = height - contentPadding - 20 * density
        
        // Draw grid
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getThemeGridColor(config.mapTheme)
            strokeWidth = 1f * density
            style = Paint.Style.STROKE
        }
        
        val gridSpacing = (width - 2 * contentPadding) / 6f
        for (i in 1..5) {
            canvas.drawLine(contentPadding + gridSpacing * i, contentTop, contentPadding + gridSpacing * i, contentBottom, gridPaint)
        }
        val vertGridSpacing = (contentBottom - contentTop) / 4f
        for (i in 1..3) {
            canvas.drawLine(contentPadding, contentTop + vertGridSpacing * i, width - contentPadding, contentTop + vertGridSpacing * i, gridPaint)
        }
        
        if (dataPoints.isEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9E9EA8")
                textSize = 12f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No map data", width / 2f, (contentTop + contentBottom) / 2f, textPaint)
        } else {
            // Draw data visualization
            drawDataVisualization(canvas, config, dataPoints, bounds, contentPadding, contentTop, contentBottom, width.toFloat(), density)
        }
        
        // Draw header
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val metricLabel = when (config.metricType) {
            MapMetricType.DOSE_RATE -> "☢️ DOSE"
            MapMetricType.COUNT_RATE -> "📈 CPS"
        }
        canvas.drawText(metricLabel, contentPadding, contentPadding + 14 * density, headerPaint)
        
        // Point count
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6E6E78")
            textSize = 10f * density
        }
        canvas.drawText("${dataPoints.size} points", contentPadding, height - contentPadding, pointPaint)
        
        // Metadata
        if (config.showTemperature || config.showBattery) {
            val metadata = if (config.deviceId != null) {
                Prefs.getDeviceMetadata(this, config.deviceId!!)
            } else {
                Prefs.getAllDeviceMetadata(this).values.firstOrNull()
            }
            
            val metadataText = StringBuilder()
            if (config.showTemperature && metadata?.temperature != null) {
                metadataText.append("🌡️ ${"%.0f".format(metadata.temperature)}°C  ")
            }
            if (config.showBattery && metadata?.batteryLevel != null) {
                metadataText.append("🔋 ${metadata.batteryLevel}%")
            }
            
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9E9EA8")
                textSize = 10f * density
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(metadataText.toString(), width - contentPadding, contentPadding + 14 * density, metaPaint)
        }
        
        return bitmap
    }
    
    private fun drawDataVisualization(
        canvas: Canvas,
        config: MapWidgetConfig,
        dataPoints: List<Prefs.MapDataPoint>,
        bounds: MapBounds,
        padding: Float,
        top: Float,
        bottom: Float,
        width: Float,
        density: Float
    ) {
        val contentWidth = width - 2 * padding
        val contentHeight = bottom - top
        
        val values = when (config.metricType) {
            MapMetricType.DOSE_RATE -> dataPoints.map { it.uSvPerHour }
            MapMetricType.COUNT_RATE -> dataPoints.map { it.cps }
        }
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        
        if (config.showHexagonGrid) {
            // Group into hexagons
            val hexagonData = mutableMapOf<String, MutableList<Prefs.MapDataPoint>>()
            dataPoints.forEach { point ->
                val hexId = latLngToHexId(point.latitude, point.longitude)
                hexagonData.getOrPut(hexId) { mutableListOf() }.add(point)
            }
            
            val hexFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val hexStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f * density
                color = Color.argb(60, 255, 255, 255)
            }
            
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
                
                val hexCenter = hexIdToScreenPos(hexId, bounds, padding, top, contentWidth, contentHeight)
                if (hexCenter != null) {
                    val hexPath = createHexagonPath(hexCenter.first, hexCenter.second, 12f * density)
                    canvas.drawPath(hexPath, hexFillPaint)
                    canvas.drawPath(hexPath, hexStrokePaint)
                }
            }
        } else {
            // Draw as circles
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
                
                val screenPos = latLngToScreenPos(point.latitude, point.longitude, bounds, padding, top, contentWidth, contentHeight)
                canvas.drawCircle(screenPos.first, screenPos.second, 5f * density, circlePaint)
            }
        }
    }
    
    private data class MapBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )
    
    private fun latLngToHexId(lat: Double, lng: Double): String {
        Prefs.ensureMapGridOrigin(this, lat, lng)
        val origin = Prefs.getMapGridOrigin(this) ?: Pair(lat, lng)
        val axial = HexGrid.latLngToAxial(lat, lng, HexGrid.Origin(origin.first, origin.second), 25.0)
        return axial.toString()
    }
    
    private fun hexIdToScreenPos(
        hexId: String,
        bounds: MapBounds,
        padding: Float,
        top: Float,
        contentWidth: Float,
        contentHeight: Float
    ): Pair<Float, Float>? {
        val parts = hexId.split(",")
        if (parts.size != 2) return null
        
        val q = parts[0].toIntOrNull() ?: return null
        val r = parts[1].toIntOrNull() ?: return null
        
        val size = 25.0
        
        val x = size * (sqrt(3.0) * q + sqrt(3.0) / 2.0 * r)
        val y = size * (3.0 / 2.0 * r)
        
        val centerLat = (bounds.minLat + bounds.maxLat) / 2
        val metersPerDegreeLat = 111320.0
        val metersPerDegreeLng = 111320.0 * cos(Math.toRadians(centerLat))
        
        val lng = x / metersPerDegreeLng
        val lat = y / metersPerDegreeLat
        
        return latLngToScreenPos(lat, lng, bounds, padding, top, contentWidth, contentHeight)
    }
    
    private fun latLngToScreenPos(
        lat: Double,
        lng: Double,
        bounds: MapBounds,
        padding: Float,
        top: Float,
        contentWidth: Float,
        contentHeight: Float
    ): Pair<Float, Float> {
        val innerPadding = 0.1f
        val latRange = (bounds.maxLat - bounds.minLat).let { if (it < 0.0001) 0.001 else it }
        val lngRange = (bounds.maxLng - bounds.minLng).let { if (it < 0.0001) 0.001 else it }
        
        val x = padding + ((lng - bounds.minLng) / lngRange * (1 - 2 * innerPadding) + innerPadding) * contentWidth
        val y = top + ((bounds.maxLat - lat) / latRange * (1 - 2 * innerPadding) + innerPadding) * contentHeight
        
        return Pair(x.toFloat(), y.toFloat())
    }
    
    private fun createHexagonPath(centerX: Float, centerY: Float, size: Float): Path {
        val path = Path()
        for (i in 0 until 6) {
            val angleDeg = 60.0 * i - 30.0
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
    
    private fun getThemeGridColor(theme: Prefs.MapTheme): Int {
        return when (theme) {
            Prefs.MapTheme.HOME,
            Prefs.MapTheme.DARK_MATTER,
            Prefs.MapTheme.DARK_GRAY,
            Prefs.MapTheme.TONER -> Color.argb(40, 255, 255, 255)
            else -> Color.argb(30, 0, 0, 0)
        }
    }

    private fun buildConfig(): MapWidgetConfig {
        return MapWidgetConfig(
            widgetId = appWidgetId,
            deviceId = selectedDeviceId,
            metricType = metricType,
            mapTheme = mapTheme,
            showHexagonGrid = showHexagons,
            showTemperature = showTemperature,
            showBattery = showBattery,
            backgroundOpacity = backgroundOpacity,
            showBorder = showBorder
        )
    }

    private fun saveConfigAndFinish() {
        val config = buildConfig()
        Prefs.setMapWidgetConfig(this, config)

        // Update the widget immediately
        MapWidgetProvider.updateWidget(this, appWidgetId)

        // Return success
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
