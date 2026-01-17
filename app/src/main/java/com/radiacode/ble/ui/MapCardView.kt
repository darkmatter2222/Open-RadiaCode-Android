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
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min

/**
 * Professional map card with:
 * - Real-time location tracking
 * - Radiation reading markers on the map
 * - Metric selector (dose rate / count rate)
 * - Dynamic color scale bar
 * - Reset button to clear markers
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
    private lateinit var resetButton: ImageButton
    private lateinit var scaleBarView: ScaleBarView
    
    // Location
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var isTrackingLocation = false
    
    // Data
    private var selectedMetric = MetricType.DOSE_RATE
    private val dataPoints = mutableListOf<Prefs.MapDataPoint>()
    
    enum class MetricType {
        DOSE_RATE,
        COUNT_RATE
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
        setupResetButton()
    }
    
    private fun setupViews() {
        mapView = findViewById(R.id.mapView)
        metricSelector = findViewById(R.id.metricSelector)
        resetButton = findViewById(R.id.resetButton)
        
        // Create and add scale bar programmatically
        val scaleBarContainer = findViewById<FrameLayout>(R.id.scaleBarContainer)
        scaleBarView = ScaleBarView(context)
        scaleBarContainer.addView(scaleBarView)
    }
    
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        
        // Set default center (will be updated when location is available)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
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
                updateMapMarkers()
                updateScaleBar()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupResetButton() {
        resetButton.setOnClickListener {
            clearMapData()
        }
    }
    
    /**
     * Start tracking user's location and updating map.
     */
    fun startLocationTracking() {
        if (isTrackingLocation) return
        
        // Check location permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                updateMapCenter(location)
            }
            
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,  // 5 seconds
                10f,    // 10 meters
                locationListener
            )
            
            // Try to get last known location immediately
            currentLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            currentLocation?.let { updateMapCenter(it) }
            
            isTrackingLocation = true
        } catch (e: SecurityException) {
            // Permission denied
        }
    }
    
    /**
     * Stop tracking location.
     */
    fun stopLocationTracking() {
        locationManager?.removeUpdates { }
        isTrackingLocation = false
    }
    
    private fun updateMapCenter(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        mapView.controller.animateTo(geoPoint)
    }
    
    /**
     * Add a new reading at the current location.
     */
    fun addReading(uSvPerHour: Float, cps: Float) {
        val location = currentLocation ?: return
        
        val point = Prefs.MapDataPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            uSvPerHour = uSvPerHour,
            cps = cps,
            timestampMs = System.currentTimeMillis()
        )
        
        dataPoints.add(point)
        
        // Save to prefs
        Prefs.addMapDataPoint(context, location.latitude, location.longitude, uSvPerHour, cps)
        
        // Update map
        updateMapMarkers()
        updateScaleBar()
    }
    
    /**
     * Load existing data points from storage.
     */
    fun loadDataPoints() {
        dataPoints.clear()
        dataPoints.addAll(Prefs.getMapDataPoints(context))
        updateMapMarkers()
        updateScaleBar()
    }
    
    /**
     * Clear all map data.
     */
    fun clearMapData() {
        dataPoints.clear()
        Prefs.clearMapDataPoints(context)
        updateMapMarkers()
        updateScaleBar()
    }
    
    private fun updateMapMarkers() {
        // Clear existing markers
        mapView.overlays.clear()
        
        if (dataPoints.isEmpty()) return
        
        // Add markers for each data point
        dataPoints.forEach { point ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(point.latitude, point.longitude)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            // Color based on value
            val value = when (selectedMetric) {
                MetricType.DOSE_RATE -> point.uSvPerHour
                MetricType.COUNT_RATE -> point.cps
            }
            
            marker.icon = createMarkerIcon(value)
            marker.title = formatMarkerTitle(point)
            
            mapView.overlays.add(marker)
        }
        
        mapView.invalidate()
    }
    
    private fun createMarkerIcon(value: Float): android.graphics.drawable.Drawable {
        val minValue = dataPoints.minOfOrNull { 
            when (selectedMetric) {
                MetricType.DOSE_RATE -> it.uSvPerHour
                MetricType.COUNT_RATE -> it.cps
            }
        } ?: 0f
        
        val maxValue = dataPoints.maxOfOrNull { 
            when (selectedMetric) {
                MetricType.DOSE_RATE -> it.uSvPerHour
                MetricType.COUNT_RATE -> it.cps
            }
        } ?: 1f
        
        val normalizedValue = if (maxValue > minValue) {
            (value - minValue) / (maxValue - minValue)
        } else {
            0.5f
        }
        
        val color = interpolateColor(normalizedValue)
        
        // Create a simple circular marker
        val size = (12 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            this.color = Color.WHITE
        }
        
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius - strokePaint.strokeWidth, paint)
        canvas.drawCircle(radius, radius, radius - strokePaint.strokeWidth, strokePaint)
        
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
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
    
    private fun formatMarkerTitle(point: Prefs.MapDataPoint): String {
        val doseUnit = Prefs.getDoseUnit(context)
        val countUnit = Prefs.getCountUnit(context)
        
        val doseStr = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("%.3f µSv/h", point.uSvPerHour)
            Prefs.DoseUnit.NSV_H -> String.format("%.0f nSv/h", point.uSvPerHour * 1000)
        }
        
        val countStr = when (countUnit) {
            Prefs.CountUnit.CPS -> String.format("%.1f CPS", point.cps)
            Prefs.CountUnit.CPM -> String.format("%.0f CPM", point.cps * 60)
        }
        
        return "$doseStr | $countStr"
    }
    
    private fun updateScaleBar() {
        if (dataPoints.isEmpty()) {
            scaleBarView.setRange(0f, 1f, selectedMetric)
            return
        }
        
        val values = dataPoints.map { 
            when (selectedMetric) {
                MetricType.DOSE_RATE -> it.uSvPerHour
                MetricType.COUNT_RATE -> it.cps
            }
        }
        
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        
        scaleBarView.setRange(minValue, maxValue, selectedMetric)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLocationTracking()
        mapView.onDetach()
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
}
