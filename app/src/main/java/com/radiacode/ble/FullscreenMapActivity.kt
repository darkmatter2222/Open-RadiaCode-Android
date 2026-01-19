package com.radiacode.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Fullscreen immersive map activity for exploring radiation data.
 * Launched when user taps on the map card in the dashboard.
 * Forces landscape orientation for maximum map real estate.
 */
class FullscreenMapActivity : AppCompatActivity() {

    private val density by lazy { resources.displayMetrics.density }
    
    // UI Components
    private lateinit var mapView: MapView
    private lateinit var closeButton: ImageButton
    private lateinit var centerButton: ImageButton
    private lateinit var metricSelector: Spinner
    private lateinit var zoomInButton: View
    private lateinit var zoomOutButton: View
    
    // Statistics panel views
    private lateinit var statsHexagonCount: TextView
    private lateinit var statsReadingCount: TextView
    private lateinit var statsAvgDose: TextView
    private lateinit var statsMaxDose: TextView
    private lateinit var statsStdDev: TextView
    private lateinit var statsAreaCovered: TextView
    
    // Location
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var currentLocation: Location? = null
    private var isTrackingLocation = false
    
    // Data
    private var selectedMetric = MetricType.DOSE_RATE
    private val hexagonData = mutableMapOf<String, MutableList<HexReading>>()
    
    // Live updates
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var lastProcessedTimestampMs: Long = 0L
    
    // Overlays
    private var hexagonOverlay: HexagonOverlay? = null
    private var positionOverlay: PositionOverlay? = null
    private var homeLabelsOverlay: TilesOverlay? = null
    
    companion object {
        const val HEX_SIZE_METERS = 25.0
        
        fun launch(context: Context) {
            context.startActivity(Intent(context, FullscreenMapActivity::class.java))
        }
    }
    
    data class HexReading(
        val uSvPerHour: Float,
        val cps: Float,
        val timestampMs: Long
    )
    
    enum class MetricType {
        DOSE_RATE,
        COUNT_RATE
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        // Fullscreen immersive mode
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        // Initialize osmdroid
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_fullscreen_map)
        
        setupViews()
        setupMap()
        setupControls()
        loadSavedData()
        startLocationTracking()
        startLiveUpdates()
    }
    
    private fun setupViews() {
        mapView = findViewById(R.id.fullscreenMapView)
        closeButton = findViewById(R.id.closeButton)
        centerButton = findViewById(R.id.centerButton)
        metricSelector = findViewById(R.id.metricSelector)
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        
        // Statistics panel
        statsHexagonCount = findViewById(R.id.statsHexagonCount)
        statsReadingCount = findViewById(R.id.statsReadingCount)
        statsAvgDose = findViewById(R.id.statsAvgDose)
        statsMaxDose = findViewById(R.id.statsMaxDose)
        statsStdDev = findViewById(R.id.statsStdDev)
        statsAreaCovered = findViewById(R.id.statsAreaCovered)
    }
    
    private fun setupMap() {
        val theme = Prefs.getMapTheme(this)
        mapView.setTileSource(createTileSource(theme))
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)  // Use our custom zoom buttons only
        mapView.controller.setZoom(17.0)

        // Home theme is composed from two raster layers:
        // - base: dark_nolabels (roads/features)
        // - labels: dark_only_labels (text with baked-in halo)
        updateHomeLabelsOverlay(theme)
        
        // Apply color filter for Home theme
        applyThemeColorFilter(theme)
        
        // Set default center
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
        
        // Add hexagon overlay
        hexagonOverlay = HexagonOverlay()
        mapView.overlays.add(hexagonOverlay)
        
        // Add tap detection overlay
        mapView.overlays.add(HexagonTapOverlay())
        
        // Add position marker overlay
        positionOverlay = PositionOverlay()
        mapView.overlays.add(positionOverlay)
    }

    private fun updateHomeLabelsOverlay(theme: Prefs.MapTheme) {
        homeLabelsOverlay?.let { existing ->
            mapView.overlays.remove(existing)
        }
        homeLabelsOverlay = null

        if (theme != Prefs.MapTheme.HOME) return

        val provider = MapTileProviderBasic(this, createHomeLabelsTileSource())
        val overlay = TilesOverlay(provider, this)
        overlay.loadingBackgroundColor = Color.TRANSPARENT
        overlay.loadingLineColor = Color.TRANSPARENT

        // Add below our data overlays (hexagons/marker), above base tiles.
        mapView.overlays.add(0, overlay)
        homeLabelsOverlay = overlay
    }
    
    private fun setupControls() {
        closeButton.setOnClickListener {
            finish()
        }
        
        centerButton.setOnClickListener {
            currentLocation?.let { location ->
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                mapView.controller.animateTo(geoPoint)
                mapView.controller.setZoom(17.0)
            }
        }
        
        zoomInButton.setOnClickListener {
            mapView.controller.zoomIn()
        }
        
        zoomOutButton.setOnClickListener {
            mapView.controller.zoomOut()
        }
        
        // Metric selector
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("Dose Rate", "Count Rate")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        metricSelector.adapter = adapter
        
        metricSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMetric = if (position == 0) MetricType.DOSE_RATE else MetricType.COUNT_RATE
                mapView.invalidate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun loadSavedData() {
        val savedPoints = Prefs.getMapDataPoints(this)
        savedPoints.forEach { point ->
            val hexId = latLngToHexId(point.latitude, point.longitude)
            val reading = HexReading(point.uSvPerHour, point.cps, point.timestampMs)
            hexagonData.getOrPut(hexId) { mutableListOf() }.add(reading)
            // Track the latest timestamp we've processed
            if (point.timestampMs > lastProcessedTimestampMs) {
                lastProcessedTimestampMs = point.timestampMs
            }
        }
        
        // Center on data if available
        if (savedPoints.isNotEmpty()) {
            val avgLat = savedPoints.map { it.latitude }.average()
            val avgLng = savedPoints.map { it.longitude }.average()
            mapView.controller.setCenter(GeoPoint(avgLat, avgLng))
        }
        
        // Update statistics panel with loaded data
        updateStatisticsPanel()
    }
    
    /**
     * Start polling for new readings while the map is active.
     */
    private fun startLiveUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                refreshNewReadings()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updateRunnable!!, 1000)
    }
    
    private fun stopLiveUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }
    
    /**
     * Check for new map data points and add them to hexagon grid.
     */
    private fun refreshNewReadings() {
        val allPoints = Prefs.getMapDataPoints(this)
        val newPoints = allPoints.filter { it.timestampMs > lastProcessedTimestampMs }
        
        if (newPoints.isEmpty()) return
        
        newPoints.forEach { point ->
            val hexId = latLngToHexId(point.latitude, point.longitude)
            val reading = HexReading(point.uSvPerHour, point.cps, point.timestampMs)
            hexagonData.getOrPut(hexId) { mutableListOf() }.add(reading)
            if (point.timestampMs > lastProcessedTimestampMs) {
                lastProcessedTimestampMs = point.timestampMs
            }
        }
        
        // Redraw map with new hexagons
        mapView.invalidate()
        
        // Update statistics panel
        updateStatisticsPanel()
    }
    
    /**
     * Update the statistics panel with current data science metrics.
     */
    private fun updateStatisticsPanel() {
        if (hexagonData.isEmpty()) {
            statsHexagonCount.text = "0"
            statsReadingCount.text = "0"
            statsAvgDose.text = "--"
            statsMaxDose.text = "--"
            statsStdDev.text = "--"
            statsAreaCovered.text = "--"
            return
        }
        
        val hexCount = hexagonData.size
        val totalReadings = hexagonData.values.sumOf { it.size }
        val allDoseValues = hexagonData.values.flatten().map { it.uSvPerHour.toDouble() }
        
        // Calculate statistics
        val avgDose = allDoseValues.average()
        val maxDose = allDoseValues.maxOrNull() ?: 0.0
        val minDose = allDoseValues.minOrNull() ?: 0.0
        
        // Standard deviation
        val variance = if (allDoseValues.size > 1) {
            allDoseValues.map { (it - avgDose).pow(2) }.average()
        } else {
            0.0
        }
        val stdDev = sqrt(variance)
        
        // Calculate area covered (each hexagon is approximately 1625 m² for 25m edge)
        // Area of regular hexagon = (3 * sqrt(3) / 2) * s²
        val hexAreaM2 = (3.0 * sqrt(3.0) / 2.0) * HEX_SIZE_METERS * HEX_SIZE_METERS
        val totalAreaM2 = hexCount * hexAreaM2
        
        // Format area appropriately
        val areaStr = when {
            totalAreaM2 >= 1_000_000 -> String.format("%.2f km²", totalAreaM2 / 1_000_000)
            totalAreaM2 >= 10_000 -> String.format("%.1f ha", totalAreaM2 / 10_000)
            else -> String.format("%.0f m²", totalAreaM2)
        }
        
        // Update UI
        statsHexagonCount.text = hexCount.toString()
        statsReadingCount.text = totalReadings.toString()
        statsAvgDose.text = String.format("%.4f", avgDose)
        statsMaxDose.text = String.format("%.4f", maxDose)
        statsStdDev.text = String.format("%.4f", stdDev)
        statsAreaCovered.text = areaStr
    }
    
    private fun startLocationTracking() {
        if (isTrackingLocation) return
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                mapView.invalidate()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                5f,
                locationListener!!
            )
            currentLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            currentLocation?.let {
                mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
            isTrackingLocation = true
        } catch (e: SecurityException) {
            // Permission denied
        }
    }
    
    private fun stopLocationTracking() {
        locationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {}
        }
        locationListener = null
        isTrackingLocation = false
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        // Re-enter immersive mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLiveUpdates()
        stopLocationTracking()
        mapView.onDetach()
    }
    
    // ===== Tile Source Creation =====
    
    private fun createTileSource(theme: Prefs.MapTheme): XYTileSource {
        return when (theme) {
            Prefs.MapTheme.HOME -> XYTileSource(
                // Home base layer: Dark Matter without labels.
                // Labels are drawn via a separate overlay (dark_only_labels).
                "CartoDB_DarkMatter_NoLabels_Home", 0, 20, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_nolabels/",
                    "https://b.basemaps.cartocdn.com/dark_nolabels/",
                    "https://c.basemaps.cartocdn.com/dark_nolabels/"
                )
            )
            Prefs.MapTheme.DARK_MATTER -> XYTileSource(
                "CartoDB_DarkMatter", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/"
                )
            )
            Prefs.MapTheme.DARK_GRAY -> XYTileSource(
                "CartoDB_DarkGray", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_nolabels/",
                    "https://b.basemaps.cartocdn.com/dark_nolabels/",
                    "https://c.basemaps.cartocdn.com/dark_nolabels/"
                )
            )
            Prefs.MapTheme.VOYAGER -> XYTileSource(
                "CartoDB_Voyager", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                    "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                    "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
                )
            )
            Prefs.MapTheme.POSITRON -> XYTileSource(
                "CartoDB_Positron", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/light_all/",
                    "https://b.basemaps.cartocdn.com/light_all/",
                    "https://c.basemaps.cartocdn.com/light_all/"
                )
            )
            Prefs.MapTheme.TONER -> XYTileSource(
                "Stamen_Toner", 0, 18, 256, ".png",
                arrayOf(
                    "https://tiles.stadiamaps.com/tiles/stamen_toner/"
                )
            )
            Prefs.MapTheme.STANDARD -> XYTileSource(
                "OpenStreetMap", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.tile.openstreetmap.org/",
                    "https://b.tile.openstreetmap.org/",
                    "https://c.tile.openstreetmap.org/"
                )
            )
        }
    }

    private fun createHomeLabelsTileSource(): XYTileSource {
        return XYTileSource(
            "CartoDB_DarkMatter_OnlyLabels_Home", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_only_labels/",
                "https://b.basemaps.cartocdn.com/dark_only_labels/",
                "https://c.basemaps.cartocdn.com/dark_only_labels/"
            )
        )
    }
    
    private fun applyThemeColorFilter(theme: Prefs.MapTheme) {
        val baseTilesOverlay = mapView.overlayManager.tilesOverlay
        
        if (theme == Prefs.MapTheme.HOME) {
            // Goal:
            // - Keep background visually matching card gray (#1A1A1E)
            // - Make roads/linework green (muted)
            // - Make labels brighter fluorescent green (separate overlay)
            //
            // Base layer (roads/features): boost green based on brightness, and slightly reduce opacity
            // so the MapView background (@color/pro_surface) participates in the final perceived gray.
            val baseMatrix = ColorMatrix(floatArrayOf(
                1.0f,  0.0f, 0.0f, 0f,    0f,   // R: keep
                0.2f,  1.6f, 0.2f, 0f,   -7f,   // G: boost, keep darks subdued
                0.0f,  0.0f, 1.0f, 0f,    0f,   // B: keep
                0.0f,  0.0f, 0.0f, 0.85f, 0f    // A: blend toward MapView background
            ))
            baseTilesOverlay.setColorFilter(ColorMatrixColorFilter(baseMatrix))

            // Labels overlay: stronger fluorescent green, keep halo/outline dark (no offsets).
            val labelsMatrix = ColorMatrix(floatArrayOf(
                0.15f, 0.0f,  0.0f, 0f, 0f,
                0.0f,  2.4f,  0.0f, 0f, 0f,
                0.0f,  0.0f,  0.15f,0f, 0f,
                0.0f,  0.0f,  0.0f, 1f, 0f
            ))
            homeLabelsOverlay?.setColorFilter(ColorMatrixColorFilter(labelsMatrix))
        } else {
            baseTilesOverlay.setColorFilter(null)
            homeLabelsOverlay?.setColorFilter(null)
        }
    }
    
    // ========== HEXAGON GRID SYSTEM ==========
    // Static, tessellating hex grid (flat-top) anchored to a fixed origin in Prefs.

    private fun getGridOrigin(): HexGrid.Origin? {
        val origin = Prefs.getMapGridOrigin(this) ?: return null
        return HexGrid.Origin(origin.first, origin.second)
    }

    private fun latLngToHexId(lat: Double, lng: Double): String {
        Prefs.ensureMapGridOrigin(this, lat, lng)
        val origin = getGridOrigin() ?: HexGrid.Origin(lat, lng)
        val axial = HexGrid.latLngToAxial(lat, lng, origin, HEX_SIZE_METERS)
        return axial.toString()
    }

    private fun hexIdToLatLng(hexId: String): Pair<Double, Double>? {
        val axial = HexGrid.Axial.parse(hexId) ?: return null
        val origin = getGridOrigin() ?: return null
        return HexGrid.axialToLatLng(axial, origin, HEX_SIZE_METERS)
    }

    private fun getHexCorners(hexId: String): List<GeoPoint>? {
        val axial = HexGrid.Axial.parse(hexId) ?: return null
        val origin = getGridOrigin() ?: return null
        val corners = HexGrid.axialToCornersLatLng(axial, origin, HEX_SIZE_METERS)
        return corners.map { (lat, lng) -> GeoPoint(lat, lng) }
    }
    
    private fun getHexAverage(hexId: String): Float? {
        val readings = hexagonData[hexId] ?: return null
        if (readings.isEmpty()) return null
        
        return when (selectedMetric) {
            MetricType.DOSE_RATE -> readings.map { it.uSvPerHour }.average().toFloat()
            MetricType.COUNT_RATE -> readings.map { it.cps }.average().toFloat()
        }
    }
    
    private fun getGlobalMinMax(): Pair<Float, Float> {
        if (hexagonData.isEmpty()) return Pair(0f, 1f)
        
        val allAverages = hexagonData.keys.mapNotNull { getHexAverage(it) }
        if (allAverages.isEmpty()) return Pair(0f, 1f)
        
        val minVal = when (selectedMetric) {
            MetricType.DOSE_RATE -> hexagonData.values.flatten().minOfOrNull { it.uSvPerHour } ?: 0f
            MetricType.COUNT_RATE -> hexagonData.values.flatten().minOfOrNull { it.cps } ?: 0f
        }
        val maxVal = when (selectedMetric) {
            MetricType.DOSE_RATE -> hexagonData.values.flatten().maxOfOrNull { it.uSvPerHour } ?: 1f
            MetricType.COUNT_RATE -> hexagonData.values.flatten().maxOfOrNull { it.cps } ?: 1f
        }
        
        return Pair(minVal, maxVal)
    }
    
    private fun interpolateColor(normalized: Float): Int {
        val green = ContextCompat.getColor(this, R.color.pro_green)
        val yellow = ContextCompat.getColor(this, R.color.pro_yellow)
        val red = ContextCompat.getColor(this, R.color.pro_red)
        
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
    
    // ===== Overlays =====
    
    private inner class HexagonOverlay : Overlay() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.argb(80, 255, 255, 255)
        }
        
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            if (hexagonData.isEmpty()) return
            
            val (minVal, maxVal) = getGlobalMinMax()
            val projection = mapView.projection
            
            hexagonData.keys.forEach { hexId ->
                val avg = getHexAverage(hexId) ?: return@forEach
                val corners = getHexCorners(hexId) ?: return@forEach
                
                val path = Path()
                corners.forEachIndexed { index, geoPoint ->
                    val screenPoint = projection.toPixels(geoPoint, null)
                    if (index == 0) {
                        path.moveTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                    } else {
                        path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
                    }
                }
                path.close()
                
                val normalized = if (maxVal > minVal) {
                    ((avg - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                
                val color = interpolateColor(normalized)
                fillPaint.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
        }
    }
    
    private inner class PositionOverlay : Overlay() {
        private val outerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = ContextCompat.getColor(this@FullscreenMapActivity, R.color.pro_cyan)
        }
        
        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(this@FullscreenMapActivity, R.color.pro_cyan)
        }
        
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = ContextCompat.getColor(this@FullscreenMapActivity, R.color.pro_cyan)
        }
        
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(60, 0, 229, 255)
        }
        
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            
            val location = currentLocation ?: return
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            val screenPoint = mapView.projection.toPixels(geoPoint, null)
            
            val x = screenPoint.x.toFloat()
            val y = screenPoint.y.toFloat()
            
            val outerRadius = 10.5f * density
            val innerRadius = 3.75f * density
            val crosshairLength = 16.5f * density
            val glowRadius = 15f * density
            
            canvas.drawCircle(x, y, glowRadius, glowPaint)
            canvas.drawLine(x - crosshairLength, y, x - outerRadius - 2 * density, y, crosshairPaint)
            canvas.drawLine(x + outerRadius + 2 * density, y, x + crosshairLength, y, crosshairPaint)
            canvas.drawLine(x, y - crosshairLength, x, y - outerRadius - 2 * density, crosshairPaint)
            canvas.drawLine(x, y + outerRadius + 2 * density, x, y + crosshairLength, crosshairPaint)
            canvas.drawCircle(x, y, outerRadius, outerCirclePaint)
            canvas.drawCircle(x, y, innerRadius, innerCirclePaint)
        }
    }
    
    private inner class HexagonTapOverlay : Overlay() {
        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            val projection = mapView.projection
            val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            
            val hexId = latLngToHexId(geoPoint.latitude, geoPoint.longitude)
            val readings = hexagonData[hexId]
            
            if (readings != null && readings.isNotEmpty()) {
                showHexagonDetailsDialog(hexId, readings)
                return true
            }
            
            return false
        }
    }
    
    private fun showHexagonDetailsDialog(hexId: String, readings: List<HexReading>) {
        val doseValues = readings.map { it.uSvPerHour }
        val cpsValues = readings.map { it.cps }
        
        val avgDose = doseValues.average().toFloat()
        val minDose = doseValues.minOrNull() ?: 0f
        val maxDose = doseValues.maxOrNull() ?: 0f
        
        val avgCps = cpsValues.average().toFloat()
        val minCps = cpsValues.minOrNull() ?: 0f
        val maxCps = cpsValues.maxOrNull() ?: 0f
        
        val readingCount = readings.size
        val lastReadingTime = readings.maxByOrNull { it.timestampMs }?.timestampMs ?: 0L
        val firstReadingTime = readings.minByOrNull { it.timestampMs }?.timestampMs ?: 0L
        
        val dateFormat = SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault())
        val lastTimeStr = if (lastReadingTime > 0) dateFormat.format(Date(lastReadingTime)) else "N/A"
        val firstTimeStr = if (firstReadingTime > 0) dateFormat.format(Date(firstReadingTime)) else "N/A"
        
        val timeSpanMs = lastReadingTime - firstReadingTime
        val timeSpanStr = when {
            timeSpanMs < 60_000 -> "${timeSpanMs / 1000}s"
            timeSpanMs < 3600_000 -> "${timeSpanMs / 60_000}m ${(timeSpanMs % 60_000) / 1000}s"
            else -> "${timeSpanMs / 3600_000}h ${(timeSpanMs % 3600_000) / 60_000}m"
        }
        
        val message = buildString {
            appendLine("📊 HEXAGON STATISTICS")
            appendLine()
            appendLine("📍 Readings: $readingCount")
            appendLine("⏱️ Time Span: $timeSpanStr")
            appendLine()
            appendLine("☢️ DOSE RATE (µSv/h)")
            appendLine("   Average: ${String.format("%.4f", avgDose)}")
            appendLine("   Min: ${String.format("%.4f", minDose)}")
            appendLine("   Max: ${String.format("%.4f", maxDose)}")
            appendLine()
            appendLine("📈 COUNT RATE (CPS)")
            appendLine("   Average: ${String.format("%.1f", avgCps)}")
            appendLine("   Min: ${String.format("%.1f", minCps)}")
            appendLine("   Max: ${String.format("%.1f", maxCps)}")
            appendLine()
            appendLine("🕐 First: $firstTimeStr")
            appendLine("🕐 Last: $lastTimeStr")
        }
        
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Hexagon Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
