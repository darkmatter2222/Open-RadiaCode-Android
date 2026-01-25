package com.radiacode.ble.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.radiacode.ble.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Photo Geotagging with Radiation Data
 * 
 * Overlays radiation readings on photos and embeds location + radiation data in EXIF.
 * Creates a visual record of radiation readings at photographed locations.
 */
class PhotoGeoTagger(private val context: Context) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val redColor = ContextCompat.getColor(context, R.color.pro_red)
    
    init {
        textPaint.apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 48f
            color = Color.WHITE
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        
        backgroundPaint.apply {
            color = Color.argb(180, 0, 0, 0)
        }
    }

    /**
     * Process a photo with radiation data overlay and EXIF tagging.
     */
    fun processPhoto(
        inputUri: Uri,
        radiationData: RadiationPhotoData,
        outputName: String? = null
    ): ProcessedPhoto? {
        return try {
            // Load the original image
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            // Create overlay
            val overlaidBitmap = addRadiationOverlay(originalBitmap, radiationData)
            
            // Save with EXIF data
            val savedUri = saveWithExif(overlaidBitmap, radiationData, outputName)
            
            originalBitmap.recycle()
            
            ProcessedPhoto(
                uri = savedUri,
                radiationData = radiationData,
                timestampMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Add radiation data overlay to a photo.
     */
    private fun addRadiationOverlay(original: Bitmap, data: RadiationPhotoData): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        val width = result.width.toFloat()
        val height = result.height.toFloat()
        
        // Scale text based on image size
        val scaleFactor = minOf(width, height) / 1000f
        textPaint.textSize = 48f * scaleFactor
        
        // Bottom overlay bar
        val barHeight = 120f * scaleFactor
        val barRect = RectF(0f, height - barHeight, width, height)
        canvas.drawRect(barRect, backgroundPaint)
        
        // Radiation symbol
        val radiationSymbol = "☢"
        textPaint.color = getRadiationColor(data.doseRate)
        textPaint.textSize = 60f * scaleFactor
        canvas.drawText(radiationSymbol, 20f * scaleFactor, height - 40f * scaleFactor, textPaint)
        
        // Dose rate
        textPaint.color = cyanColor
        textPaint.textSize = 48f * scaleFactor
        val doseText = "%.3f µSv/h".format(data.doseRate)
        canvas.drawText(doseText, 100f * scaleFactor, height - 65f * scaleFactor, textPaint)
        
        // Count rate
        textPaint.color = magentaColor
        textPaint.textSize = 36f * scaleFactor
        val cpsText = "%.0f CPS".format(data.countRate)
        canvas.drawText(cpsText, 100f * scaleFactor, height - 25f * scaleFactor, textPaint)
        
        // Location (if available)
        data.location?.let { loc ->
            textPaint.color = Color.WHITE
            textPaint.textSize = 28f * scaleFactor
            val locText = "%.5f, %.5f".format(loc.latitude, loc.longitude)
            canvas.drawText(locText, width - textPaint.measureText(locText) - 20f * scaleFactor, height - 65f * scaleFactor, textPaint)
            
            // Altitude if available
            if (loc.hasAltitude()) {
                val altText = "%.0f m".format(loc.altitude)
                canvas.drawText(altText, width - textPaint.measureText(altText) - 20f * scaleFactor, height - 30f * scaleFactor, textPaint)
            }
        }
        
        // Timestamp in top left
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f * scaleFactor
        val timeText = dateFormat.format(Date(data.timestampMs))
        
        // Background for timestamp
        val timeBgRect = RectF(0f, 0f, textPaint.measureText(timeText) + 30f * scaleFactor, 40f * scaleFactor)
        canvas.drawRect(timeBgRect, backgroundPaint)
        canvas.drawText(timeText, 15f * scaleFactor, 28f * scaleFactor, textPaint)
        
        // Safety indicator in top right
        val safetyText = getSafetyLabel(data.doseRate)
        val safetyColor = getRadiationColor(data.doseRate)
        textPaint.color = safetyColor
        textPaint.textSize = 32f * scaleFactor
        
        val safetyWidth = textPaint.measureText(safetyText) + 30f * scaleFactor
        val safetyBgRect = RectF(width - safetyWidth, 0f, width, 50f * scaleFactor)
        backgroundPaint.color = Color.argb(200, 0, 0, 0)
        canvas.drawRect(safetyBgRect, backgroundPaint)
        backgroundPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawText(safetyText, width - safetyWidth + 15f * scaleFactor, 35f * scaleFactor, textPaint)
        
        // Optional: Add isotope if detected
        data.detectedIsotope?.let { isotope ->
            textPaint.color = Color.YELLOW
            textPaint.textSize = 28f * scaleFactor
            val isotopeText = "Detected: $isotope"
            val isoWidth = textPaint.measureText(isotopeText) + 30f * scaleFactor
            val isoRect = RectF(width / 2 - isoWidth / 2, height - barHeight - 50f * scaleFactor, width / 2 + isoWidth / 2, height - barHeight)
            canvas.drawRoundRect(isoRect, 10f * scaleFactor, 10f * scaleFactor, backgroundPaint)
            canvas.drawText(isotopeText, width / 2 - textPaint.measureText(isotopeText) / 2, height - barHeight - 15f * scaleFactor, textPaint)
        }
        
        return result
    }

    private fun getRadiationColor(doseRate: Float): Int {
        return when {
            doseRate > 10f -> redColor    // Very high
            doseRate > 1f -> Color.rgb(255, 165, 0)  // Orange - elevated
            doseRate > 0.3f -> Color.YELLOW   // Slightly elevated
            else -> greenColor  // Normal
        }
    }

    private fun getSafetyLabel(doseRate: Float): String {
        return when {
            doseRate > 10f -> "⚠️ DANGER"
            doseRate > 1f -> "⚠️ ELEVATED"
            doseRate > 0.3f -> "ABOVE NORMAL"
            else -> "✓ NORMAL"
        }
    }

    /**
     * Save the processed image with EXIF data.
     */
    private fun saveWithExif(bitmap: Bitmap, data: RadiationPhotoData, outputName: String?): Uri? {
        val filename = outputName ?: "RadiaCode_${System.currentTimeMillis()}.jpg"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(bitmap, data, filename)
        } else {
            saveToFile(bitmap, data, filename)
        }
    }

    private fun saveWithMediaStore(bitmap: Bitmap, data: RadiationPhotoData, filename: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RadiaCode")
            put(MediaStore.Images.Media.DATE_TAKEN, data.timestampMs)
            
            // Add location if available
            data.location?.let { loc ->
                put(MediaStore.Images.Media.LATITUDE, loc.latitude)
                put(MediaStore.Images.Media.LONGITUDE, loc.longitude)
            }
        }
        
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }
        
        // Add EXIF data
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                writeExifData(exif, data)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return uri
    }

    private fun saveToFile(bitmap: Bitmap, data: RadiationPhotoData, filename: String): Uri? {
        val picturesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "RadiaCode"
        )
        picturesDir.mkdirs()
        
        val file = File(picturesDir, filename)
        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }
        
        // Add EXIF data
        try {
            val exif = ExifInterface(file.absolutePath)
            writeExifData(exif, data)
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return Uri.fromFile(file)
    }

    private fun writeExifData(exif: ExifInterface, data: RadiationPhotoData) {
        // Standard EXIF tags
        val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateFormat.format(Date(data.timestampMs)))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateFormat.format(Date(data.timestampMs)))
        
        // GPS data
        data.location?.let { loc ->
            exif.setLatLong(loc.latitude, loc.longitude)
            if (loc.hasAltitude()) {
                exif.setAltitude(loc.altitude)
            }
        }
        
        // Store radiation data in UserComment field (EXIF allows custom data here)
        val radiationComment = buildString {
            append("RadiaCode Data | ")
            append("DoseRate: %.4f µSv/h | ".format(data.doseRate))
            append("CountRate: %.1f CPS | ".format(data.countRate))
            data.detectedIsotope?.let { append("Isotope: $it | ") }
            data.temperature?.let { append("Temp: %.1f°C | ".format(it)) }
            append("DeviceID: ${data.deviceId}")
        }
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, radiationComment)
        
        // Software tag
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Open RadiaCode")
        
        // Image description with radiation summary
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, 
            "Radiation: %.3f µSv/h".format(data.doseRate))
    }

    /**
     * Extract radiation data from a previously tagged photo.
     */
    fun extractRadiationData(uri: Uri): RadiationPhotoData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null
                
                if (!userComment.startsWith("RadiaCode Data")) return null
                
                // Parse the comment
                val parts = userComment.split(" | ").associate { 
                    val kv = it.split(": ", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else "" to ""
                }
                
                val doseRate = parts["DoseRate"]?.replace(" µSv/h", "")?.toFloatOrNull() ?: 0f
                val countRate = parts["CountRate"]?.replace(" CPS", "")?.toFloatOrNull() ?: 0f
                val isotope = parts["Isotope"]
                val deviceId = parts["DeviceID"] ?: ""
                
                // Get location
                val latLong = exif.latLong
                val location = if (latLong != null) {
                    Location("exif").apply {
                        latitude = latLong[0]
                        longitude = latLong[1]
                        altitude = exif.getAltitude(0.0)
                    }
                } else null
                
                // Get timestamp
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val timestamp = try {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr ?: "")?.time ?: 0L
                } catch (e: Exception) { 0L }
                
                RadiationPhotoData(
                    doseRate = doseRate,
                    countRate = countRate,
                    location = location,
                    timestampMs = timestamp,
                    detectedIsotope = isotope,
                    temperature = null,
                    deviceId = deviceId
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create a simple overlay without processing the full image (for preview).
     */
    fun createOverlayPreview(width: Int, height: Int, data: RadiationPhotoData): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Just draw the overlay bars (transparent background)
        canvas.drawColor(Color.TRANSPARENT)
        
        val scaleFactor = minOf(width, height) / 500f
        
        // Bottom bar
        val barHeight = 60f * scaleFactor
        backgroundPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(0f, height - barHeight, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Dose rate
        textPaint.color = cyanColor
        textPaint.textSize = 24f * scaleFactor
        canvas.drawText("%.3f µSv/h".format(data.doseRate), 20f * scaleFactor, height - 20f * scaleFactor, textPaint)
        
        return bitmap
    }

    // Data classes
    data class RadiationPhotoData(
        val doseRate: Float,
        val countRate: Float,
        val location: Location?,
        val timestampMs: Long = System.currentTimeMillis(),
        val detectedIsotope: String? = null,
        val temperature: Float? = null,
        val deviceId: String = ""
    )

    data class ProcessedPhoto(
        val uri: Uri?,
        val radiationData: RadiationPhotoData,
        val timestampMs: Long
    )
}
