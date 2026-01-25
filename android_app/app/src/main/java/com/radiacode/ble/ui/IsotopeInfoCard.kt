package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.radiacode.ble.IsotopeEncyclopedia

/**
 * Isotope Info Card
 * Rich information card displaying isotope details when detected
 * Surfaces the existing IsotopeEncyclopedia data in a user-friendly format
 */
class IsotopeInfoCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors - Pro Dark Theme
    private val colorBackground = Color.parseColor("#1A1A1E")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorMuted = Color.parseColor("#6E6E78")
    private val colorText = Color.parseColor("#9E9EA8")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#E040FB")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorYellow = Color.parseColor("#FFD600")
    private val colorRed = Color.parseColor("#FF5252")

    // Current isotope
    private var isotopeInfo: IsotopeEncyclopedia.IsotopeInfo? = null
    private var confidence: Float = 0f

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBackground
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBorder
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 72f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMuted
        textSize = 24f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 26f
    }

    private val energyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }

    private val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 24f
    }

    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setIsotope(name: String, confidence: Float = 0.8f) {
        this.isotopeInfo = IsotopeEncyclopedia.getIsotopeInfo(name)
        this.confidence = confidence.coerceIn(0f, 1f)
        requestLayout()
        invalidate()
    }

    fun setIsotope(info: IsotopeEncyclopedia.IsotopeInfo, confidence: Float = 0.8f) {
        this.isotopeInfo = info
        this.confidence = confidence.coerceIn(0f, 1f)
        requestLayout()
        invalidate()
    }

    fun clear() {
        this.isotopeInfo = null
        this.confidence = 0f
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (isotopeInfo != null) 400 else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val info = isotopeInfo ?: return
        
        val cornerRadius = 24f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Glow effect behind card based on category
        val glowColor = getCategoryGlowColor(info.category)
        glowPaint.shader = RadialGradient(
            width / 2f, 0f,
            width / 2f,
            intArrayOf(glowColor, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)

        // Background
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Layout sections
        var y = 32f
        val leftPadding = 24f
        val rightSection = width - 120f

        // === Header Section ===
        
        // Emoji box on left
        val symbolBoxSize = 100f
        val symbolRect = RectF(leftPadding, y, leftPadding + symbolBoxSize, y + symbolBoxSize)
        
        val symbolBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2A2E")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(symbolRect, 16f, 16f, symbolBgPaint)
        
        // Emoji
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(info.emoji, symbolRect.centerX(), symbolRect.centerY() + 16f, emojiPaint)

        // Name and symbol on right of emoji
        val nameX = leftPadding + symbolBoxSize + 20f
        canvas.drawText(info.fullName, nameX, y + 36f, namePaint)
        
        val elementText = info.symbol
        canvas.drawText(elementText, nameX, y + 68f, labelPaint)

        // Confidence badge
        if (confidence > 0) {
            drawConfidenceBadge(canvas, rightSection, y + 20f, confidence)
        }

        y += symbolBoxSize + 24f

        // === Energy Section ===
        drawSectionLabel(canvas, "Gamma Energies", leftPadding, y)
        y += 36f

        info.gammaEnergies.take(3).forEach { energy ->
            canvas.drawText("${energy.toInt()} keV", leftPadding, y, energyPaint)
            y += 32f
        }

        y += 8f

        // === Properties Row ===
        val colWidth = (width - leftPadding * 2) / 3

        // Half-life
        drawPropertyBox(canvas, "Half-life", info.halfLife, leftPadding, y, colWidth - 8f)
        
        // Decay mode
        drawPropertyBox(canvas, "Decay", info.decayMode, leftPadding + colWidth, y, colWidth - 8f)
        
        // Category
        drawPropertyBox(canvas, "Type", info.category.displayName, leftPadding + colWidth * 2, y, colWidth - 8f)

        y += 80f

        // === Category Badge ===
        drawCategoryBadge(canvas, info.category, leftPadding, y)

        y += 48f

        // === Description (if room) ===
        if (y + 60 < height) {
            val desc = info.commonSources.firstOrNull() ?: ""
            if (desc.isNotEmpty()) {
                labelPaint.color = colorMuted
                canvas.drawText("Common source:", leftPadding, y, labelPaint)
                
                // Truncate if needed
                val maxWidth = width - leftPadding * 2
                val truncatedDesc = truncateText(desc, descPaint, maxWidth)
                canvas.drawText(truncatedDesc, leftPadding, y + 28f, descPaint)
            }
        }
    }

    private fun drawSectionLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        labelPaint.color = colorMuted
        canvas.drawText(label, x, y, labelPaint)
    }

    private fun drawPropertyBox(canvas: Canvas, label: String, value: String, x: Float, y: Float, width: Float) {
        val boxRect = RectF(x, y, x + width, y + 64f)
        val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121214")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxRect, 12f, 12f, boxBg)

        labelPaint.color = colorMuted
        labelPaint.textSize = 18f
        canvas.drawText(label, x + 12f, y + 22f, labelPaint)

        valuePaint.textSize = 22f
        val truncatedValue = truncateText(value, valuePaint, width - 24f)
        canvas.drawText(truncatedValue, x + 12f, y + 50f, valuePaint)
    }

    private fun drawConfidenceBadge(canvas: Canvas, x: Float, y: Float, confidence: Float) {
        val text = "${(confidence * 100).toInt()}%"
        val textWidth = tagPaint.measureText(text)
        
        tagBgPaint.color = when {
            confidence >= 0.8f -> colorGreen
            confidence >= 0.5f -> colorYellow
            else -> colorRed
        }
        tagPaint.color = Color.parseColor("#0D0D0F")

        val rect = RectF(x - textWidth - 16f, y, x, y + 32f)
        canvas.drawRoundRect(rect, 16f, 16f, tagBgPaint)
        canvas.drawText(text, rect.centerX() - textWidth / 2, rect.centerY() + 7f, tagPaint)
    }

    private fun drawCategoryBadge(canvas: Canvas, category: IsotopeEncyclopedia.Category, x: Float, y: Float) {
        val label = category.displayName
        val categoryColor = Color.parseColor("#${category.colorHex}")

        valuePaint.color = categoryColor
        valuePaint.textSize = 28f
        canvas.drawText("●  $label", x, y, valuePaint)
    }

    private fun getCategoryGlowColor(category: IsotopeEncyclopedia.Category): Int {
        return when (category) {
            IsotopeEncyclopedia.Category.NATURAL -> Color.argb(20, 105, 240, 174)
            IsotopeEncyclopedia.Category.MEDICAL -> Color.argb(30, 0, 229, 255)
            IsotopeEncyclopedia.Category.INDUSTRIAL -> Color.argb(30, 255, 215, 64)
            IsotopeEncyclopedia.Category.FISSION -> Color.argb(40, 255, 82, 82)
            IsotopeEncyclopedia.Category.COSMOGENIC -> Color.argb(30, 224, 64, 251)
        }
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        
        var truncated = text
        while (truncated.length > 3 && paint.measureText("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }
}
