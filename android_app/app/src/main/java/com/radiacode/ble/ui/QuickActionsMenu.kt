package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Quick Actions FAB Menu
 * A floating action button that expands to show quick actions
 */
class QuickActionsMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // State
    private var isExpanded = false
    private var expandProgress = 0f
    private var selectedAction: Int = -1

    // Colors
    private val primaryColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val surfaceColor = ContextCompat.getColor(context, R.color.pro_surface)
    private val bgColor = ContextCompat.getColor(context, R.color.pro_background)
    private val textColor = ContextCompat.getColor(context, R.color.pro_text_primary)
    private val textMuted = ContextCompat.getColor(context, R.color.pro_text_muted)

    // Dimensions
    private val fabSize = density * 56f
    private val actionSize = density * 48f
    private val actionRadius = density * 100f

    // Actions
    data class QuickAction(
        val id: String,
        val icon: String,  // Emoji for simplicity
        val label: String,
        val color: Int
    )

    private val actions = mutableListOf<QuickAction>()
    private var actionListener: ((String) -> Unit)? = null

    // Paints
    private val fabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primaryColor
    }

    private val fabShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 50
        maskFilter = BlurMaskFilter(density * 8f, BlurMaskFilter.Blur.NORMAL)
    }

    private val actionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surfaceColor
    }

    private val actionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = density * 24f
        textAlign = Paint.Align.CENTER
    }

    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        strokeCap = Paint.Cap.ROUND
        color = bgColor
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 12f
        color = textColor
        textAlign = Paint.Align.RIGHT
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private var animator: ValueAnimator? = null

    init {
        // Default actions
        setActions(
            listOf(
                QuickAction("record", "⏺️", "Start Recording", ContextCompat.getColor(context, R.color.pro_red)),
                QuickAction("screenshot", "📸", "Screenshot", ContextCompat.getColor(context, R.color.pro_cyan)),
                QuickAction("bookmark", "🔖", "Add Bookmark", ContextCompat.getColor(context, R.color.pro_yellow)),
                QuickAction("hotspot", "📍", "Mark Hotspot", ContextCompat.getColor(context, R.color.pro_magenta)),
                QuickAction("sound", "🔊", "Toggle Sound", ContextCompat.getColor(context, R.color.pro_green))
            )
        )
        
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // For blur effect
    }

    fun setActions(actionList: List<QuickAction>) {
        actions.clear()
        actions.addAll(actionList)
        invalidate()
    }

    fun setOnActionClickListener(listener: (String) -> Unit) {
        actionListener = listener
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        animateTo(1f)
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(expandProgress, target).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (actionRadius * 2 + fabSize + density * 40f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width - fabSize / 2 - density * 16f
        val centerY = height - fabSize / 2 - density * 16f

        // Draw overlay background when expanded
        if (expandProgress > 0) {
            overlayPaint.alpha = (expandProgress * 200).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        }

        // Draw action buttons in a semi-circle
        if (expandProgress > 0) {
            val numActions = actions.size
            val startAngle = Math.PI * 1.0  // Start from left
            val sweepAngle = Math.PI * 0.5  // Quarter circle

            actions.forEachIndexed { index, action ->
                val angle = startAngle + (sweepAngle * index / (numActions - 1).coerceAtLeast(1))
                val distance = actionRadius * expandProgress
                val x = centerX + (cos(angle) * distance).toFloat()
                val y = centerY + (sin(angle) * distance).toFloat()

                // Scale animation for each action
                val actionScale = (expandProgress * (1 + index * 0.1f)).coerceAtMost(1f)
                val currentSize = actionSize * actionScale

                // Shadow
                canvas.drawCircle(x + density * 2f, y + density * 2f, currentSize / 2f, fabShadowPaint)

                // Background
                val isSelected = selectedAction == index
                if (isSelected) {
                    actionBgPaint.color = action.color
                } else {
                    actionBgPaint.color = surfaceColor
                }
                canvas.drawCircle(x, y, currentSize / 2f, actionBgPaint)
                canvas.drawCircle(x, y, currentSize / 2f, actionBorderPaint)

                // Icon
                val iconY = y + iconPaint.textSize / 3f
                canvas.drawText(action.icon, x, iconY, iconPaint)

                // Label (to the left of the action)
                val labelX = x - currentSize / 2f - density * 8f
                labelPaint.alpha = (expandProgress * 255).toInt()
                canvas.drawText(action.label, labelX, y + labelPaint.textSize / 3f, labelPaint)
            }
        }

        // Draw main FAB
        // Shadow
        canvas.drawCircle(centerX + density * 3f, centerY + density * 3f, fabSize / 2f, fabShadowPaint)
        
        // Button
        canvas.drawCircle(centerX, centerY, fabSize / 2f, fabPaint)

        // Plus/X icon with rotation
        val rotation = expandProgress * 45f
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        
        val iconLength = density * 12f
        // Horizontal line
        canvas.drawLine(
            centerX - iconLength, centerY,
            centerX + iconLength, centerY,
            plusPaint
        )
        // Vertical line
        canvas.drawLine(
            centerX, centerY - iconLength,
            centerX, centerY + iconLength,
            plusPaint
        )
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = width - fabSize / 2 - density * 16f
        val centerY = height - fabSize / 2 - density * 16f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if FAB touched
                val dx = event.x - centerX
                val dy = event.y - centerY
                if (dx * dx + dy * dy < fabSize * fabSize / 4) {
                    return true
                }

                // Check if any action touched
                if (isExpanded) {
                    selectedAction = findTouchedAction(event.x, event.y, centerX, centerY)
                    if (selectedAction >= 0) {
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isExpanded) {
                    selectedAction = findTouchedAction(event.x, event.y, centerX, centerY)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                // Check if FAB tapped
                val dx = event.x - centerX
                val dy = event.y - centerY
                if (dx * dx + dy * dy < fabSize * fabSize / 4) {
                    toggle()
                    return true
                }

                // Check if action tapped
                if (isExpanded && selectedAction >= 0) {
                    val action = actions.getOrNull(selectedAction)
                    if (action != null) {
                        actionListener?.invoke(action.id)
                    }
                    collapse()
                    selectedAction = -1
                    return true
                }

                // Tap outside - collapse
                if (isExpanded) {
                    collapse()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                selectedAction = -1
                invalidate()
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findTouchedAction(touchX: Float, touchY: Float, centerX: Float, centerY: Float): Int {
        val numActions = actions.size
        val startAngle = Math.PI * 1.0
        val sweepAngle = Math.PI * 0.5

        actions.forEachIndexed { index, _ ->
            val angle = startAngle + (sweepAngle * index / (numActions - 1).coerceAtLeast(1))
            val distance = actionRadius * expandProgress
            val x = centerX + (cos(angle) * distance).toFloat()
            val y = centerY + (sin(angle) * distance).toFloat()

            val dx = touchX - x
            val dy = touchY - y
            if (dx * dx + dy * dy < actionSize * actionSize / 4) {
                return index
            }
        }
        return -1
    }
}
