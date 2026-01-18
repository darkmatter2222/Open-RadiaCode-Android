package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import kotlin.math.*

/**
 * Compass Bearing to Peak Radiation
 * 
 * Shows a compass that points toward the direction of increasing radiation.
 * Uses recent location + radiation data to estimate the bearing to the hottest area.
 */
class CompassBearingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var currentAzimuth = 0f // Device's current heading (0 = North)
    
    // Peak tracking
    private var peakBearing: Float? = null // Bearing to peak radiation area
    private var peakDistance: Float? = null // Distance to peak in meters
    private var peakDoseRate: Float = 0f
    private var currentDoseRate: Float = 0f
    private var confidenceLevel: Float = 0f // How confident we are in the bearing (0-1)
    
    // Recent readings with location
    private val recentReadings = mutableListOf<LocationReading>()
    private val maxReadings = 100
    
    // Drawing
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val redColor = ContextCompat.getColor(context, R.color.pro_red)
    private val mutedColor = ContextCompat.getColor(context, R.color.pro_text_muted)
    private val surfaceColor = ContextCompat.getColor(context, R.color.pro_surface)
    
    // Animation
    private var targetAzimuth = 0f
    private var displayAzimuth = 0f
    
    init {
        textPaint.apply {
            textSize = 14f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        
        arcPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
        }
        
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)
        
        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            // Convert azimuth from radians to degrees
            val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            currentAzimuth = (azimuthDegrees + 360) % 360
            
            // Smooth animation
            val diff = normalizeAngle(currentAzimuth - displayAzimuth)
            displayAzimuth = normalizeAngle(displayAzimuth + diff * 0.15f)
            
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Add a new reading with location data.
     */
    fun addReading(location: Location, doseRate: Float, countRate: Float) {
        currentDoseRate = doseRate
        
        val reading = LocationReading(
            latitude = location.latitude,
            longitude = location.longitude,
            doseRate = doseRate,
            countRate = countRate,
            timestampMs = System.currentTimeMillis()
        )
        
        recentReadings.add(reading)
        
        // Keep only recent readings
        while (recentReadings.size > maxReadings) {
            recentReadings.removeAt(0)
        }
        
        // Recalculate peak bearing
        calculatePeakBearing(location)
        
        invalidate()
    }

    /**
     * Calculate the bearing to the area with highest radiation.
     */
    private fun calculatePeakBearing(currentLocation: Location) {
        if (recentReadings.size < 5) {
            peakBearing = null
            confidenceLevel = 0f
            return
        }
        
        // Find readings that are significantly above average
        val avgDose = recentReadings.map { it.doseRate }.average().toFloat()
        val hotReadings = recentReadings.filter { it.doseRate > avgDose * 1.2f }
        
        if (hotReadings.isEmpty()) {
            peakBearing = null
            confidenceLevel = 0f
            return
        }
        
        // Calculate weighted centroid of hot readings
        var sumLat = 0.0
        var sumLon = 0.0
        var totalWeight = 0f
        var maxDose = 0f
        
        hotReadings.forEach { reading ->
            val weight = reading.doseRate
            sumLat += reading.latitude * weight
            sumLon += reading.longitude * weight
            totalWeight += weight
            if (reading.doseRate > maxDose) {
                maxDose = reading.doseRate
            }
        }
        
        if (totalWeight <= 0) {
            peakBearing = null
            confidenceLevel = 0f
            return
        }
        
        val centroidLat = sumLat / totalWeight
        val centroidLon = sumLon / totalWeight
        
        // Calculate bearing from current location to centroid
        val bearing = calculateBearing(
            currentLocation.latitude, currentLocation.longitude,
            centroidLat, centroidLon
        )
        
        // Calculate distance
        val distance = calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            centroidLat, centroidLon
        )
        
        // Calculate confidence based on:
        // 1. Number of hot readings
        // 2. Spread of readings (lower spread = higher confidence)
        // 3. Consistency of direction
        val readingCount = hotReadings.size.toFloat() / maxReadings
        val spreadFactor = 1f - (hotReadings.map { it.doseRate }.let { 
            val std = sqrt(it.map { v -> (v - avgDose).pow(2) }.average()).toFloat()
            (std / avgDose).coerceIn(0f, 1f)
        })
        
        peakBearing = bearing.toFloat()
        peakDistance = distance.toFloat()
        peakDoseRate = maxDose
        confidenceLevel = ((readingCount * 0.3f + spreadFactor * 0.7f) * 100).coerceIn(0f, 100f) / 100f
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360) % 360
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360
        if (normalized > 180) normalized -= 360
        if (normalized < -180) normalized += 360
        return normalized
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - 20f * resources.displayMetrics.density
        
        // Draw compass background
        compassPaint.apply {
            style = Paint.Style.FILL
            color = surfaceColor
        }
        canvas.drawCircle(centerX, centerY, radius, compassPaint)
        
        // Draw compass ring
        compassPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = mutedColor
        }
        canvas.drawCircle(centerX, centerY, radius, compassPaint)
        
        // Save canvas state for rotation
        canvas.save()
        canvas.rotate(-displayAzimuth, centerX, centerY)
        
        // Draw cardinal directions
        val directions = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        directions.forEach { (label, angle) ->
            canvas.save()
            canvas.rotate(angle, centerX, centerY)
            
            // Draw tick
            compassPaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * resources.displayMetrics.density
                color = if (label == "N") redColor else mutedColor
            }
            canvas.drawLine(centerX, centerY - radius + 10.dp, centerX, centerY - radius + 25.dp, compassPaint)
            
            // Draw label
            textPaint.color = if (label == "N") redColor else mutedColor
            canvas.drawText(label, centerX, centerY - radius + 45.dp, textPaint)
            
            canvas.restore()
        }
        
        // Draw minor ticks
        compassPaint.apply {
            strokeWidth = 1f * resources.displayMetrics.density
            color = mutedColor
        }
        for (i in 0 until 360 step 30) {
            if (i % 90 != 0) {
                canvas.save()
                canvas.rotate(i.toFloat(), centerX, centerY)
                canvas.drawLine(centerX, centerY - radius + 10.dp, centerX, centerY - radius + 18.dp, compassPaint)
                canvas.restore()
            }
        }
        
        // Draw peak bearing indicator (if available)
        peakBearing?.let { bearing ->
            canvas.save()
            canvas.rotate(bearing, centerX, centerY)
            
            // Confidence arc
            arcPaint.color = Color.argb((confidenceLevel * 150).toInt(), Color.red(magentaColor), Color.green(magentaColor), Color.blue(magentaColor))
            val arcWidth = 30f * confidenceLevel
            val arcRect = RectF(centerX - radius + 30.dp, centerY - radius + 30.dp, centerX + radius - 30.dp, centerY + radius - 30.dp)
            canvas.drawArc(arcRect, -90f - arcWidth, arcWidth * 2, false, arcPaint)
            
            // Radiation arrow (pointing to peak)
            val arrowLength = radius - 40.dp
            val arrowPath = Path().apply {
                moveTo(centerX, centerY - arrowLength)
                lineTo(centerX - 15.dp, centerY - arrowLength + 30.dp)
                lineTo(centerX, centerY - arrowLength + 20.dp)
                lineTo(centerX + 15.dp, centerY - arrowLength + 30.dp)
                close()
            }
            
            // Glow effect
            glowPaint.apply {
                color = magentaColor
                maskFilter = BlurMaskFilter(8.dp.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(arrowPath, glowPaint)
            
            // Arrow fill
            needlePaint.apply {
                style = Paint.Style.FILL
                color = magentaColor
                maskFilter = null
            }
            canvas.drawPath(arrowPath, needlePaint)
            
            canvas.restore()
        }
        
        canvas.restore() // Restore from rotation
        
        // Draw center info
        val infoY = centerY + 20.dp
        
        // Current heading
        textPaint.apply {
            color = cyanColor
            textSize = 18f * resources.displayMetrics.density
        }
        canvas.drawText("%.0f°".format(displayAzimuth), centerX, centerY - 10.dp, textPaint)
        
        // Peak info
        textPaint.textSize = 12f * resources.displayMetrics.density
        if (peakBearing != null) {
            textPaint.color = magentaColor
            canvas.drawText("Peak: %.0f°".format(peakBearing), centerX, infoY, textPaint)
            
            peakDistance?.let { dist ->
                val distStr = if (dist >= 1000) "%.1fkm".format(dist / 1000) else "%.0fm".format(dist)
                textPaint.color = mutedColor
                canvas.drawText(distStr, centerX, infoY + 18.dp, textPaint)
            }
            
            // Confidence indicator
            textPaint.color = when {
                confidenceLevel > 0.7f -> greenColor
                confidenceLevel > 0.4f -> Color.YELLOW
                else -> mutedColor
            }
            canvas.drawText("Conf: %.0f%%".format(confidenceLevel * 100), centerX, infoY + 36.dp, textPaint)
        } else {
            textPaint.color = mutedColor
            canvas.drawText("Scanning...", centerX, infoY, textPaint)
            canvas.drawText("Move around to detect gradient", centerX, infoY + 18.dp, textPaint)
        }
    }

    fun clearReadings() {
        recentReadings.clear()
        peakBearing = null
        peakDistance = null
        confidenceLevel = 0f
        invalidate()
    }

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density

    data class LocationReading(
        val latitude: Double,
        val longitude: Double,
        val doseRate: Float,
        val countRate: Float,
        val timestampMs: Long
    )
}
