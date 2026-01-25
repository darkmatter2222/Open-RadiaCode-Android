package com.radiacode.ble

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Share Card Generator
 * Creates beautiful shareable images with current reading, location, and isotope ID.
 */
object ShareCardGenerator {
    
    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    
    /**
     * Data to include in the share card
     */
    data class ShareCardData(
        val doseRateUSvH: Float,
        val countRateCPS: Float,
        val timestamp: Long = System.currentTimeMillis(),
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationName: String? = null,
        val topIsotope: String? = null,
        val isotopeConfidence: Float? = null,
        val cumulativeDoseUSv: Double? = null,
        val sessionDuration: String? = null,
        val deviceName: String? = null,
        val safetyStatus: RadiationComparison.SafetyStatus? = null
    )
    
    /**
     * Generate a share card image
     */
    fun generateShareCard(context: Context, data: ShareCardData): Bitmap {
        val width = 1080
        val height = 1350  // Instagram-friendly aspect ratio
        val density = context.resources.displayMetrics.density
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Colors
        val bgColor = Color.parseColor("#0D0D0F")
        val surfaceColor = Color.parseColor("#1A1A1E")
        val borderColor = Color.parseColor("#2A2A2E")
        val cyanColor = Color.parseColor("#00E5FF")
        val magentaColor = Color.parseColor("#E040FB")
        val greenColor = Color.parseColor("#69F0AE")
        val amberColor = Color.parseColor("#FFD740")
        val redColor = Color.parseColor("#FF5252")
        val textPrimary = Color.WHITE
        val textSecondary = Color.parseColor("#B0B0B8")
        val textMuted = Color.parseColor("#6E6E78")
        
        // Background
        canvas.drawColor(bgColor)
        
        // Paints
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = borderColor
        }
        
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            color = textPrimary
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            color = textMuted
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        
        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 120f
            color = cyanColor
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = textSecondary
            textAlign = Paint.Align.CENTER
        }
        
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            color = textPrimary
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 32f
            color = textSecondary
        }
        
        val padding = 60f
        val cardPadding = 40f
        var y = padding
        
        // Header with app name and timestamp
        y += titlePaint.textSize
        canvas.drawText("Open RadiaCode", width / 2f, y, titlePaint)
        
        y += 40f
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(dateFormat.format(Date(data.timestamp)), width / 2f, y, smallPaint)
        
        // Main reading card
        y += 60f
        val mainCardRect = RectF(padding, y, width - padding, y + 340f)
        bgPaint.color = surfaceColor
        canvas.drawRoundRect(mainCardRect, 32f, 32f, bgPaint)
        canvas.drawRoundRect(mainCardRect, 32f, 32f, borderPaint)
        
        // Dose rate
        y += cardPadding + labelPaint.textSize
        canvas.drawText("DOSE RATE", padding + cardPadding, y, labelPaint)
        
        y += heroPaint.textSize + 20f
        val doseText = formatDoseRate(data.doseRateUSvH)
        canvas.drawText(doseText, width / 2f, y, heroPaint)
        
        y += unitPaint.textSize + 10f
        canvas.drawText("μSv/h", width / 2f, y, unitPaint)
        
        // Safety indicator
        val safety = data.safetyStatus ?: RadiationComparison.getSafetyStatus(data.doseRateUSvH)
        y += 50f
        val statusColor = Color.parseColor("#${safety.colorHex}")
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = statusColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("● ${safety.shortDescription.uppercase()}", width / 2f, y, statusPaint)
        
        // Count rate card
        y = mainCardRect.bottom + 30f
        val countCardRect = RectF(padding, y, width - padding, y + 160f)
        bgPaint.color = surfaceColor
        canvas.drawRoundRect(countCardRect, 32f, 32f, bgPaint)
        canvas.drawRoundRect(countCardRect, 32f, 32f, borderPaint)
        
        y += cardPadding + labelPaint.textSize
        canvas.drawText("COUNT RATE", padding + cardPadding, y, labelPaint)
        
        y += valuePaint.textSize + 10f
        valuePaint.color = magentaColor
        canvas.drawText(String.format("%.1f cps", data.countRateCPS), padding + cardPadding, y, valuePaint)
        
        y = countCardRect.bottom + 30f
        
        // Isotope detection (if available)
        if (data.topIsotope != null) {
            val isotopeCardRect = RectF(padding, y, width - padding, y + 140f)
            bgPaint.color = surfaceColor
            canvas.drawRoundRect(isotopeCardRect, 32f, 32f, bgPaint)
            canvas.drawRoundRect(isotopeCardRect, 32f, 32f, borderPaint)
            
            y += cardPadding + labelPaint.textSize
            canvas.drawText("DETECTED ISOTOPE", padding + cardPadding, y, labelPaint)
            
            y += valuePaint.textSize + 10f
            valuePaint.color = cyanColor
            val isotopeText = if (data.isotopeConfidence != null) {
                "${data.topIsotope} (${String.format("%.0f", data.isotopeConfidence * 100)}%)"
            } else {
                data.topIsotope
            }
            canvas.drawText(isotopeText, padding + cardPadding, y, valuePaint)
            
            y = isotopeCardRect.bottom + 30f
        }
        
        // Location (if available)
        if (data.latitude != null && data.longitude != null) {
            val locCardRect = RectF(padding, y, width - padding, y + 140f)
            bgPaint.color = surfaceColor
            canvas.drawRoundRect(locCardRect, 32f, 32f, bgPaint)
            canvas.drawRoundRect(locCardRect, 32f, 32f, borderPaint)
            
            y += cardPadding + labelPaint.textSize
            canvas.drawText("📍 LOCATION", padding + cardPadding, y, labelPaint)
            
            y += smallPaint.textSize + 10f
            smallPaint.textAlign = Paint.Align.LEFT
            val locText = data.locationName ?: String.format("%.5f, %.5f", data.latitude, data.longitude)
            canvas.drawText(locText, padding + cardPadding, y, smallPaint)
            
            y = locCardRect.bottom + 30f
        }
        
        // Cumulative dose (if available)
        if (data.cumulativeDoseUSv != null) {
            val cumCardRect = RectF(padding, y, width - padding, y + 120f)
            bgPaint.color = surfaceColor
            canvas.drawRoundRect(cumCardRect, 32f, 32f, bgPaint)
            canvas.drawRoundRect(cumCardRect, 32f, 32f, borderPaint)
            
            y += cardPadding + labelPaint.textSize
            canvas.drawText("SESSION TOTAL", padding + cardPadding, y, labelPaint)
            
            y += smallPaint.textSize + 10f
            smallPaint.textAlign = Paint.Align.LEFT
            val cumText = CumulativeDoseTracker.formatDose(data.cumulativeDoseUSv)
            val durationText = data.sessionDuration?.let { " in $it" } ?: ""
            canvas.drawText("$cumText$durationText", padding + cardPadding, y, smallPaint)
            
            y = cumCardRect.bottom + 30f
        }
        
        // Contextual comparison
        val comparison = RadiationComparison.getQuickSummary(data.doseRateUSvH)
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.color = textMuted
        canvas.drawText(comparison, width / 2f, height - 80f, smallPaint)
        
        // Watermark
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = textMuted
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Generated by Open RadiaCode • github.com/darkmatter2222", 
            width / 2f, height - 30f, watermarkPaint)
        
        return bitmap
    }
    
    /**
     * Save the share card and return a shareable URI
     */
    fun saveAndGetShareUri(context: Context, bitmap: Bitmap): Uri {
        val fileName = "radiacode_reading_${System.currentTimeMillis()}.png"
        val file = File(context.cacheDir, fileName)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    
    /**
     * Share the card via system share sheet
     */
    fun shareCard(context: Context, data: ShareCardData, additionalText: String? = null) {
        val bitmap = generateShareCard(context, data)
        val uri = saveAndGetShareUri(context, bitmap)
        
        val shareText = buildString {
            append("Radiation Reading: ${formatDoseRate(data.doseRateUSvH)} μSv/h")
            append(" (${String.format("%.1f", data.countRateCPS)} cps)")
            data.topIsotope?.let { append("\nDetected: $it") }
            data.locationName?.let { append("\nLocation: $it") }
            data.latitude?.let { lat ->
                data.longitude?.let { lon ->
                    append("\nhttps://www.google.com/maps?q=$lat,$lon")
                }
            }
            additionalText?.let { append("\n\n$it") }
            append("\n\n#RadiaCode #Radiation #OpenRadiaCode")
        }
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "Share Reading"))
    }
    
    private fun formatDoseRate(uSvH: Float): String {
        return when {
            uSvH < 0.001f -> String.format("%.4f", uSvH)
            uSvH < 0.01f -> String.format("%.3f", uSvH)
            uSvH < 1f -> String.format("%.3f", uSvH)
            uSvH < 10f -> String.format("%.2f", uSvH)
            else -> String.format("%.1f", uSvH)
        }
    }
}
