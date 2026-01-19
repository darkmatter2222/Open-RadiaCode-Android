package com.radiacode.ble.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.radiacode.ble.HexGrid
import com.radiacode.ble.FullscreenMapActivity
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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
 * Professional map card with:
 * - Real-time location tracking
 * - Hexagonal grid overlay for radiation readings
 * - Color-coded hexagons based on average readings
 * - Dark theme map tiles
 * - Metric selector (dose rate / count rate)
 * - Dynamic color scale bar
 */
class MapCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    
    // UI Components
    private lateinit var mapView: MapView
    private lateinit var metricSelector: Spinner
    private lateinit var centerButton: ImageButton
    private lateinit var scaleBarView: ScaleBarView
    
    // Overlays
    private var hexagonOverlay: HexagonOverlay? = null
    private var positionOverlay: PositionOverlay? = null
    private var homeLabelsOverlay: TilesOverlay? = null
    
    // Location
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var currentLocation: Location? = null
    private var isTrackingLocation = false
    
    // Data
    private var selectedMetric = MetricType.DOSE_RATE
    private val dataPoints = mutableListOf<Prefs.MapDataPoint>()
    
    // Hexagon grid data: maps hex cell ID -> list of readings
    private val hexagonData = mutableMapOf<String, MutableList<HexReading>>()
    
    // Hex resolution: ~30m edge length ≈ house-sized hexagons
    // This is roughly H3 resolution 9 (edge ~174m) to 10 (edge ~66m)
    // We'll use a custom grid with ~25m cells
    companion object {
        const val HEX_SIZE_METERS = 25.0  // Edge length in meters (house-sized)
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
    
    /**
     * Create a tile source for the given map theme.
     */
    private fun createTileSource(theme: Prefs.MapTheme): XYTileSource {
        return when (theme) {
            // Home theme uses Dark Matter - already has correct gray background
            // Color filter makes roads/labels pop green
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
    
    init {
        orientation = VERTICAL
        
        // Initialize osmdroid configuration
        Configuration.getInstance().userAgentValue = context.packageName
        
        // Inflate layout
        LayoutInflater.from(context).inflate(R.layout.view_map_card, this, true)
        
        setupViews()
        setupMap()
        setupMetricSelector()
        setupCenterButton()
        setupHeaderClick()
    }
    
    private fun setupViews() {
        mapView = findViewById(R.id.mapView)
        metricSelector = findViewById(R.id.metricSelector)
        centerButton = findViewById(R.id.centerButton)
        
        // Create and add scale bar programmatically
        val scaleBarContainer = findViewById<FrameLayout>(R.id.scaleBarContainer)
        scaleBarView = ScaleBarView(context)
        scaleBarContainer.addView(scaleBarView)
    }
    
    private fun setupMap() {
        // Load tile source from saved preference
        val theme = Prefs.getMapTheme(context)
        mapView.setTileSource(createTileSource(theme))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)  // Closer zoom to see hexagons better

        // Home theme is composed from two raster layers:
        // - base: dark_nolabels (roads/features)
        // - labels: dark_only_labels (text with baked-in halo)
        updateHomeLabelsOverlay(theme)
        
        // Prevent parent ScrollView from intercepting touch events
        mapView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Request parent to not intercept touch events
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Allow parent to intercept again
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false  // Let the map handle the touch
        }
        
        // Apply color filter for Home theme
        applyThemeColorFilter(theme)
        
        // Set default center (will be updated when location is available)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
        
        // Add hexagon overlay (below position marker)
        hexagonOverlay = HexagonOverlay()
        mapView.overlays.add(hexagonOverlay)
        
        // Add tap detection overlay for hexagon details
        mapView.overlays.add(HexagonTapOverlay())
        
        // Add position marker overlay (on top of hexagons)
        positionOverlay = PositionOverlay()
        mapView.overlays.add(positionOverlay)
    }

    private fun updateHomeLabelsOverlay(theme: Prefs.MapTheme) {
        homeLabelsOverlay?.let { existing ->
            mapView.overlays.remove(existing)
        }
        homeLabelsOverlay = null

        if (theme != Prefs.MapTheme.HOME) return

        val provider = MapTileProviderBasic(context, createHomeLabelsTileSource())
        val overlay = TilesOverlay(provider, context)
        overlay.loadingBackgroundColor = Color.TRANSPARENT
        overlay.loadingLineColor = Color.TRANSPARENT

        // Insert below our data overlays (hexagons/marker), above base tiles.
        mapView.overlays.add(0, overlay)
        homeLabelsOverlay = overlay
    }
    
    /**
     * Setup the center-on-location button.
     */
    private fun setupCenterButton() {
        centerButton.setOnClickListener {
            currentLocation?.let { location ->
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                mapView.controller.animateTo(geoPoint)
                mapView.controller.setZoom(17.0)
            }
        }
    }
    
    /**
     * Apply a color filter to match the app's cyberpunk theme.
     * Home theme uses a two-overlay approach:
     * - Base layer (dark_nolabels): muted green roads/linework
     * - Labels layer (dark_only_labels): brighter fluorescent green text with baked-in halo
     */
    private fun applyThemeColorFilter(theme: Prefs.MapTheme) {
        val baseTilesOverlay = mapView.overlayManager.tilesOverlay
        
        if (theme == Prefs.MapTheme.HOME) {
            // Base layer: boost green based on brightness and slightly reduce opacity
            // so the MapView background (@color/pro_surface) participates in the final perceived gray.
            val baseMatrix = ColorMatrix(floatArrayOf(
                1.0f,  0.0f, 0.0f, 0f,    0f,   // R: keep
                0.2f,  1.6f, 0.2f, 0f,   -7f,   // G: boost, keep darks subdued
                0.0f,  0.0f, 1.0f, 0f,    0f,   // B: keep
                0.0f,  0.0f, 0.0f, 0.85f, 0f    // A: blend toward MapView background
            ))
            baseTilesOverlay.setColorFilter(ColorMatrixColorFilter(baseMatrix))

            // Labels layer: stronger fluorescent green; keep halo/outline dark (no offsets).
            val labelsMatrix = ColorMatrix(floatArrayOf(
                0.15f, 0.0f,  0.0f, 0f, 0f,
                0.0f,  2.4f,  0.0f, 0f, 0f,
                0.0f,  0.0f,  0.15f,0f, 0f,
                0.0f,  0.0f,  0.0f, 1f, 0f
            ))
            homeLabelsOverlay?.setColorFilter(ColorMatrixColorFilter(labelsMatrix))
        } else {
            // Clear any color filter for other themes
            baseTilesOverlay.setColorFilter(null)
            homeLabelsOverlay?.setColorFilter(null)
        }
    }
    
    /**
     * Change the map theme dynamically.
     */
    fun setMapTheme(theme: Prefs.MapTheme) {
        mapView.setTileSource(createTileSource(theme))
        updateHomeLabelsOverlay(theme)
        applyThemeColorFilter(theme)
        mapView.invalidate()
    }
    
    /**
     * Setup header click to launch fullscreen map.
     */
    private fun setupHeaderClick() {
        findViewById<View>(R.id.headerRow).setOnClickListener {
            FullscreenMapActivity.launch(context)
        }
    }
    
    private fun setupMetricSelector() {
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            arrayOf("Dose Rate", "Count Rate")
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        metricSelector.adapter = adapter
        
        metricSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMetric = if (position == 0) MetricType.DOSE_RATE else MetricType.COUNT_RATE
                updateHexagonColors()
                updateScaleBar()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * Start tracking user's location and updating map.
     */
    fun startLocationTracking() {
        if (isTrackingLocation) {
            android.util.Log.d("MapCardView", "startLocationTracking: Already tracking")
            return
        }
        
        // Check location permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("MapCardView", "startLocationTracking: No location permission")
            return
        }
        
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                android.util.Log.d("MapCardView", "onLocationChanged: ${location.latitude},${location.longitude}")
                currentLocation = location
                updateMapCenter(location)
                // Invalidate to redraw position marker
                mapView.invalidate()
            }
            
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                android.util.Log.d("MapCardView", "GPS provider enabled")
            }
            override fun onProviderDisabled(provider: String) {
                android.util.Log.w("MapCardView", "GPS provider disabled")
            }
        }
        
        try {
            // Request updates from GPS
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,  // 5 seconds
                10f,    // 10 meters
                locationListener!!
            )
            
            // Also try network provider as fallback
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    locationListener!!
                )
            } catch (e: Exception) {
                android.util.Log.w("MapCardView", "Network location provider not available")
            }
            
            // Try to get last known location immediately
            currentLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (currentLocation != null) {
                android.util.Log.d("MapCardView", "Got last known location: ${currentLocation!!.latitude},${currentLocation!!.longitude}")
                updateMapCenter(currentLocation!!)
            } else {
                android.util.Log.w("MapCardView", "No last known location available")
            }
            
            isTrackingLocation = true
            android.util.Log.d("MapCardView", "Location tracking started")
        } catch (e: SecurityException) {
            android.util.Log.e("MapCardView", "SecurityException starting location tracking", e)
        }
    }
    
    /**
     * Stop tracking location.
     */
    fun stopLocationTracking() {
        locationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                // Ignore
            }
        }
        locationListener = null
        isTrackingLocation = false
    }
    
    private fun updateMapCenter(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        mapView.controller.animateTo(geoPoint)
    }
    
    // ========== HEXAGON GRID SYSTEM ==========
    // Static, tessellating hex grid (flat-top) anchored to a fixed origin in Prefs.
    // Cells share edges (no overlap) because all centers/corners are generated
    // in the same local meter projection.

    private fun getGridOrigin(): HexGrid.Origin? {
        val origin = Prefs.getMapGridOrigin(context) ?: return null
        return HexGrid.Origin(origin.first, origin.second)
    }

    private fun latLngToHexId(lat: Double, lng: Double): String {
        Prefs.ensureMapGridOrigin(context, lat, lng)
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
    
    /**
     * Add a new reading at the current location.
     */
    fun addReading(uSvPerHour: Float, cps: Float) {
        val location = currentLocation
        if (location == null) {
            android.util.Log.w("MapCardView", "addReading: No location available, skipping reading ($uSvPerHour μSv/h)")
            return
        }
        
        android.util.Log.d("MapCardView", "addReading: Adding $uSvPerHour μSv/h at ${location.latitude},${location.longitude}")
        
        val point = Prefs.MapDataPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            uSvPerHour = uSvPerHour,
            cps = cps,
            timestampMs = System.currentTimeMillis()
        )
        
        dataPoints.add(point)
        
        // Add to hexagon grid
        val hexId = latLngToHexId(location.latitude, location.longitude)
        val reading = HexReading(uSvPerHour, cps, System.currentTimeMillis())
        hexagonData.getOrPut(hexId) { mutableListOf() }.add(reading)
        
        // Save to prefs
        Prefs.addMapDataPoint(context, location.latitude, location.longitude, uSvPerHour, cps)
        
        // Update map
        updateHexagonColors()
        updateScaleBar()
    }
    
    /**
     * Load existing data points from storage and rebuild hexagon grid.
     */
    fun loadDataPoints() {
        dataPoints.clear()
        hexagonData.clear()
        
        val points = Prefs.getMapDataPoints(context)
        dataPoints.addAll(points)
        
        // Rebuild hexagon data from saved points
        points.forEach { point ->
            val hexId = latLngToHexId(point.latitude, point.longitude)
            val reading = HexReading(point.uSvPerHour, point.cps, point.timestampMs)
            hexagonData.getOrPut(hexId) { mutableListOf() }.add(reading)
        }
        
        updateHexagonColors()
        updateScaleBar()
    }
    
    /**
     * Clear all map data.
     */
    fun clearMapData() {
        dataPoints.clear()
        hexagonData.clear()
        Prefs.clearMapDataPoints(context)
        updateHexagonColors()
        updateScaleBar()
    }
    
    /**
     * Update hexagon overlay colors based on current metric selection.
     */
    private fun updateHexagonColors() {
        mapView.invalidate()
    }
    
    /**
     * Get average value for a hexagon cell.
     */
    private fun getHexAverage(hexId: String): Float? {
        val readings = hexagonData[hexId] ?: return null
        if (readings.isEmpty()) return null
        
        return when (selectedMetric) {
            MetricType.DOSE_RATE -> readings.map { it.uSvPerHour }.average().toFloat()
            MetricType.COUNT_RATE -> readings.map { it.cps }.average().toFloat()
        }
    }
    
    /**
     * Get global min/max values across all hexagons.
     */
    private fun getGlobalMinMax(): Pair<Float, Float> {
        if (hexagonData.isEmpty()) return Pair(0f, 1f)
        
        val allValues = hexagonData.values.flatten().map { reading ->
            when (selectedMetric) {
                MetricType.DOSE_RATE -> reading.uSvPerHour
                MetricType.COUNT_RATE -> reading.cps
            }
        }
        
        if (allValues.isEmpty()) return Pair(0f, 1f)
        
        val minVal = allValues.minOrNull() ?: 0f
        val maxVal = allValues.maxOrNull() ?: 1f
        
        return Pair(minVal, if (maxVal > minVal) maxVal else minVal + 1f)
    }
    
    private fun interpolateColor(normalizedValue: Float): Int {
        // Green (low) -> Yellow (medium) -> Red (high)
        val green = ContextCompat.getColor(context, R.color.pro_green)
        val yellow = ContextCompat.getColor(context, R.color.pro_yellow)
        val red = ContextCompat.getColor(context, R.color.pro_red)
        
        return when {
            normalizedValue < 0.5f -> {
                val t = normalizedValue * 2
                interpolateColorValues(green, yellow, t)
            }
            else -> {
                val t = (normalizedValue - 0.5f) * 2
                interpolateColorValues(yellow, red, t)
            }
        }
    }
    
    private fun interpolateColorValues(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
    
    private fun updateScaleBar() {
        val (minValue, maxValue) = getGlobalMinMax()
        scaleBarView.setRange(minValue, maxValue, selectedMetric)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLocationTracking()
        mapView.onDetach()
    }
    
    /**
     * Custom overlay that draws hexagons on the map.
     */
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
                
                // Convert corners to screen coordinates
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
                
                // Calculate color based on normalized value
                val normalized = if (maxVal > minVal) {
                    ((avg - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                
                val color = interpolateColor(normalized)
                
                // Draw semi-transparent filled hexagon
                fillPaint.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawPath(path, fillPaint)
                
                // Draw border
                canvas.drawPath(path, strokePaint)
            }
        }
    }
    
    /**
     * Custom view for displaying the color scale bar.
     */
    private class ScaleBarView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        
        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity
        
        private var minValue = 0f
        private var maxValue = 1f
        private var metricType = MetricType.DOSE_RATE
        
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledDensity * 11f
            color = ContextCompat.getColor(context, R.color.pro_text_secondary)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        
        fun setRange(min: Float, max: Float, metric: MetricType) {
            minValue = min
            maxValue = max
            metricType = metric
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val barHeight = height - textPaint.textSize - 8 * density
            val barWidth = width.toFloat()
            val barTop = textPaint.textSize + 4 * density
            
            // Draw gradient bar
            val green = ContextCompat.getColor(context, R.color.pro_green)
            val yellow = ContextCompat.getColor(context, R.color.pro_yellow)
            val red = ContextCompat.getColor(context, R.color.pro_red)
            
            val gradient = LinearGradient(
                0f, barTop, barWidth, barTop,
                intArrayOf(green, yellow, red),
                null,
                Shader.TileMode.CLAMP
            )
            
            barPaint.shader = gradient
            canvas.drawRect(0f, barTop, barWidth, barTop + barHeight, barPaint)
            
            // Draw min label
            val minLabel = formatValue(minValue, metricType)
            canvas.drawText(minLabel, 4 * density, textPaint.textSize, textPaint)
            
            // Draw max label
            val maxLabel = formatValue(maxValue, metricType)
            val maxLabelWidth = textPaint.measureText(maxLabel)
            canvas.drawText(maxLabel, barWidth - maxLabelWidth - 4 * density, textPaint.textSize, textPaint)
        }
        
        private fun formatValue(value: Float, metric: MetricType): String {
            return when (metric) {
                MetricType.DOSE_RATE -> String.format("%.3f µSv/h", value)
                MetricType.COUNT_RATE -> String.format("%.1f CPS", value)
            }
        }
    }
    
    /**
     * Custom overlay that draws a crosshair position marker at current location.
     */
    private inner class PositionOverlay : Overlay() {
        
        private val outerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = ContextCompat.getColor(context, R.color.pro_cyan)
        }
        
        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.pro_cyan)
        }
        
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = ContextCompat.getColor(context, R.color.pro_cyan)
        }
        
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(60, 0, 229, 255)  // pro_cyan with alpha
        }
        
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            
            val location = currentLocation ?: return
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            val screenPoint = mapView.projection.toPixels(geoPoint, null)
            
            val x = screenPoint.x.toFloat()
            val y = screenPoint.y.toFloat()
            
            // Sizes reduced by 25% from original
            val outerRadius = 10.5f * density
            val innerRadius = 3.75f * density
            val crosshairLength = 16.5f * density
            val glowRadius = 15f * density
            
            // Draw glow effect
            canvas.drawCircle(x, y, glowRadius, glowPaint)
            
            // Draw crosshair lines
            canvas.drawLine(x - crosshairLength, y, x - outerRadius - 2 * density, y, crosshairPaint)
            canvas.drawLine(x + outerRadius + 2 * density, y, x + crosshairLength, y, crosshairPaint)
            canvas.drawLine(x, y - crosshairLength, x, y - outerRadius - 2 * density, crosshairPaint)
            canvas.drawLine(x, y + outerRadius + 2 * density, x, y + crosshairLength, crosshairPaint)
            
            // Draw outer circle
            canvas.drawCircle(x, y, outerRadius, outerCirclePaint)
            
            // Draw inner filled dot
            canvas.drawCircle(x, y, innerRadius, innerCirclePaint)
        }
    }
    
    /**
     * Overlay that detects taps on hexagons and shows details dialog.
     */
    private inner class HexagonTapOverlay : Overlay() {
        
        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            val projection = mapView.projection
            val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            
            // Convert tap location to hex ID
            val hexId = latLngToHexId(geoPoint.latitude, geoPoint.longitude)
            
            // Check if this hex has data
            val readings = hexagonData[hexId]
            if (readings != null && readings.isNotEmpty()) {
                showHexagonDetailsDialog(hexId, readings)
                return true
            }
            
            return false
        }
    }
    
    /**
     * Show a dialog with detailed statistics for a hexagon.
     */
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
        
        // Calculate time span
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
        
        AlertDialog.Builder(context, R.style.DarkDialogTheme)
            .setTitle("Hexagon Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
