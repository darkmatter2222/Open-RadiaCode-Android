package com.radiacode.ble.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.util.AttributeSet
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.*
import com.radiacode.ble.R
import java.util.Locale

/**
 * Live map card view that shows radiation readings on a map.
 * Features:
 * - Google Maps integration with user location
 * - Metric selector (dose rate / count rate)
 * - Color-coded dots based on readings
 * - Dynamic legend showing min/max values
 * - Reset button to clear readings
 */
class MapCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Data class for map readings
    data class MapReading(
        val latitude: Double,
        val longitude: Double,
        val doseRate: Float,  // μSv/h
        val countRate: Float, // cps
        val timestampMs: Long
    )

    enum class MetricType {
        DOSE_RATE,
        COUNT_RATE
    }

    // Views
    private val containerLayout: LinearLayout
    private val headerLayout: LinearLayout
    private val titleText: TextView
    private val metricSelector: Spinner
    private val resetButton: ImageButton
    private val contentLayout: LinearLayout
    private val mapView: MapView
    private val legendLayout: LinearLayout
    private val legendBar: View
    private val legendMinLabel: TextView
    private val legendMaxLabel: TextView

    // Map and location
    private var googleMap: GoogleMap? = null
    private val fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private val DEFAULT_ZOOM = 16f

    // Data
    private val readings = mutableListOf<MapReading>()
    private val markers = mutableListOf<Circle>()
    private var currentMetric = MetricType.DOSE_RATE
    private var minValue = 0f
    private var maxValue = 0f
    private var hasInitialLocation = false

    // Callbacks
    var onResetClickListener: (() -> Unit)? = null

    init {
        // Inflate the card layout
        inflate(context, R.layout.view_map_card, this)

        // Bind views
        containerLayout = findViewById(R.id.mapCardContainer)
        headerLayout = findViewById(R.id.mapCardHeader)
        titleText = findViewById(R.id.mapCardTitle)
        metricSelector = findViewById(R.id.mapMetricSelector)
        resetButton = findViewById(R.id.mapResetButton)
        contentLayout = findViewById(R.id.mapCardContent)
        mapView = findViewById(R.id.mapView)
        legendLayout = findViewById(R.id.mapLegendLayout)
        legendBar = findViewById(R.id.mapLegendBar)
        legendMinLabel = findViewById(R.id.mapLegendMin)
        legendMaxLabel = findViewById(R.id.mapLegendMax)

        // Setup location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // Setup metric selector
        setupMetricSelector()

        // Setup reset button
        resetButton.setOnClickListener {
            clearReadings()
            onResetClickListener?.invoke()
        }

        // Initialize title
        titleText.text = "LIVE MAP"
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
                currentMetric = if (position == 0) MetricType.DOSE_RATE else MetricType.COUNT_RATE
                updateMarkersAndLegend()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Initialize the map. Should be called from Activity lifecycle methods.
     */
    fun onCreate(savedInstanceState: android.os.Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            googleMap = map
            setupMap(map)
            getCurrentLocation()
        }
    }

    private fun setupMap(map: GoogleMap) {
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
        }

        // Enable location if permission granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
        }

        // Set map style to dark theme
        try {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark))
        } catch (_: Exception) {
            // Dark style not available, use default
        }
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                if (!hasInitialLocation) {
                    moveToLocation(it.latitude, it.longitude)
                    hasInitialLocation = true
                }
            }
        }
    }

    private fun moveToLocation(latitude: Double, longitude: Double) {
        googleMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(latitude, longitude),
                DEFAULT_ZOOM
            )
        )
    }

    /**
     * Add a new reading to the map.
     */
    fun addReading(reading: MapReading) {
        readings.add(reading)
        updateMarkersAndLegend()
    }

    /**
     * Add a reading with current location (if available).
     */
    fun addReadingAtCurrentLocation(doseRate: Float, countRate: Float, timestampMs: Long) {
        currentLocation?.let { loc ->
            addReading(MapReading(loc.latitude, loc.longitude, doseRate, countRate, timestampMs))
        }
    }

    /**
     * Clear all readings and markers from the map.
     */
    fun clearReadings() {
        readings.clear()
        markers.forEach { it.remove() }
        markers.clear()
        minValue = 0f
        maxValue = 0f
        updateLegend()
    }

    private fun updateMarkersAndLegend() {
        // Remove existing markers
        markers.forEach { it.remove() }
        markers.clear()

        if (readings.isEmpty()) {
            minValue = 0f
            maxValue = 0f
            updateLegend()
            return
        }

        // Calculate min/max for current metric
        val values = readings.map {
            when (currentMetric) {
                MetricType.DOSE_RATE -> it.doseRate
                MetricType.COUNT_RATE -> it.countRate
            }
        }

        minValue = values.minOrNull() ?: 0f
        maxValue = values.maxOrNull() ?: 0f

        // Add markers
        val map = googleMap ?: return
        for (reading in readings) {
            val value = when (currentMetric) {
                MetricType.DOSE_RATE -> reading.doseRate
                MetricType.COUNT_RATE -> reading.countRate
            }

            val color = getColorForValue(value, minValue, maxValue)
            val circle = map.addCircle(
                CircleOptions()
                    .center(LatLng(reading.latitude, reading.longitude))
                    .radius(10.0) // 10 meters
                    .fillColor(color)
                    .strokeColor(Color.WHITE)
                    .strokeWidth(2f)
            )
            markers.add(circle)
        }

        updateLegend()
    }

    private fun getColorForValue(value: Float, min: Float, max: Float): Int {
        if (max <= min) return Color.argb(180, 0, 255, 0)

        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)

        // Color gradient: green (low) -> yellow (medium) -> red (high)
        val red: Int
        val green: Int
        if (normalized < 0.5f) {
            // Green to yellow
            red = (normalized * 2f * 255).toInt()
            green = 255
        } else {
            // Yellow to red
            red = 255
            green = ((1f - normalized) * 2f * 255).toInt()
        }

        return Color.argb(180, red, green, 0)
    }

    private fun updateLegend() {
        val unit = when (currentMetric) {
            MetricType.DOSE_RATE -> "μSv/h"
            MetricType.COUNT_RATE -> "cps"
        }

        if (maxValue > minValue) {
            legendMinLabel.text = String.format(Locale.US, "%.2f %s", minValue, unit)
            legendMaxLabel.text = String.format(Locale.US, "%.2f %s", maxValue, unit)
        } else {
            legendMinLabel.text = "0 $unit"
            legendMaxLabel.text = "0 $unit"
        }
    }

    // Lifecycle methods to forward to MapView
    fun onStart() {
        mapView.onStart()
    }

    fun onResume() {
        mapView.onResume()
        getCurrentLocation() // Refresh location on resume
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onStop() {
        mapView.onStop()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    fun onLowMemory() {
        mapView.onLowMemory()
    }

    fun onSaveInstanceState(outState: android.os.Bundle) {
        mapView.onSaveInstanceState(outState)
    }
}
