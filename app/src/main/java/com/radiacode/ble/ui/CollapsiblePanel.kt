package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.radiacode.ble.R

/**
 * Collapsible Panel Section
 * 
 * A card-like container that can be expanded/collapsed with smooth animation.
 * Remembers state per section via SharedPreferences.
 */
class CollapsiblePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var sectionId: String = ""
    private var title: String = ""
    private var isExpanded: Boolean = true
    
    private lateinit var headerView: LinearLayout
    private lateinit var titleTextView: TextView
    private lateinit var chevronView: ChevronView
    private lateinit var contentContainer: FrameLayout
    
    private var contentView: View? = null
    private var measuredContentHeight = 0
    
    private val prefs by lazy { 
        context.getSharedPreferences("collapsible_panels", Context.MODE_PRIVATE) 
    }
    
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        setupView()
        
        // Parse attributes
        context.theme.obtainStyledAttributes(attrs, R.styleable.CollapsiblePanel, 0, 0).apply {
            try {
                sectionId = getString(R.styleable.CollapsiblePanel_sectionId) ?: ""
                title = getString(R.styleable.CollapsiblePanel_sectionTitle) ?: "Section"
                isExpanded = getBoolean(R.styleable.CollapsiblePanel_expanded, true)
            } finally {
                recycle()
            }
        }
        
        // Restore saved state if sectionId is set
        if (sectionId.isNotEmpty()) {
            isExpanded = prefs.getBoolean("section_$sectionId", isExpanded)
        }
        
        titleTextView.text = title
        updateChevron(false)
        updateContentVisibility(false)
    }

    private fun setupView() {
        // Header
        headerView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            setBackgroundResource(R.drawable.collapsible_header_bg)
            
            // Make clickable
            isClickable = true
            isFocusable = true
            setOnClickListener { toggle() }
        }
        
        // Title
        titleTextView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerView.addView(titleTextView)
        
        // Chevron
        chevronView = ChevronView(context).apply {
            layoutParams = LayoutParams(24.dp, 24.dp)
        }
        headerView.addView(chevronView)
        
        addView(headerView)
        
        // Content container
        contentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, 8.dp, 0, 0)
        }
        addView(contentContainer)
    }

    /**
     * Set the content view for this collapsible section.
     */
    fun setContentView(view: View) {
        contentContainer.removeAllViews()
        contentView = view
        contentContainer.addView(view)
        
        // Measure content height for animation
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        measuredContentHeight = view.measuredHeight
        
        updateContentVisibility(false)
    }

    fun setTitle(newTitle: String) {
        title = newTitle
        titleTextView.text = newTitle
    }

    fun setSectionId(id: String) {
        sectionId = id
        if (id.isNotEmpty()) {
            isExpanded = prefs.getBoolean("section_$id", isExpanded)
            updateChevron(false)
            updateContentVisibility(false)
        }
    }

    /**
     * Toggle expanded/collapsed state with animation.
     */
    fun toggle() {
        isExpanded = !isExpanded
        animateToggle()
        saveState()
        onExpandedChanged?.invoke(isExpanded)
    }

    /**
     * Expand the panel.
     */
    fun expand() {
        if (!isExpanded) {
            isExpanded = true
            animateToggle()
            saveState()
            onExpandedChanged?.invoke(true)
        }
    }

    /**
     * Collapse the panel.
     */
    fun collapse() {
        if (isExpanded) {
            isExpanded = false
            animateToggle()
            saveState()
            onExpandedChanged?.invoke(false)
        }
    }

    fun isExpanded() = isExpanded

    private fun animateToggle() {
        // Animate chevron rotation
        chevronView.animateTo(if (isExpanded) 0f else -90f)
        
        // Animate content height
        val targetHeight = if (isExpanded) {
            // Re-measure in case content changed
            contentView?.let { view ->
                view.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                measuredContentHeight = view.measuredHeight
            }
            measuredContentHeight
        } else {
            0
        }
        
        val startHeight = contentContainer.height
        
        ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                contentContainer.layoutParams.height = value
                contentContainer.requestLayout()
            }
            start()
        }
    }

    private fun updateContentVisibility(animate: Boolean) {
        if (animate) {
            animateToggle()
        } else {
            contentContainer.layoutParams.height = if (isExpanded) {
                LayoutParams.WRAP_CONTENT
            } else {
                0
            }
            contentContainer.requestLayout()
        }
    }

    private fun updateChevron(animate: Boolean) {
        val rotation = if (isExpanded) 0f else -90f
        if (animate) {
            chevronView.animateTo(rotation)
        } else {
            chevronView.setRotationAngle(rotation)
        }
    }

    private fun saveState() {
        if (sectionId.isNotEmpty()) {
            prefs.edit().putBoolean("section_$sectionId", isExpanded).apply()
        }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    /**
     * Custom chevron view with smooth rotation animation.
     */
    private inner class ChevronView(context: Context) : View(context) {
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ContextCompat.getColor(context, R.color.pro_text_muted)
        }
        
        private val path = Path()
        private var currentRotation = 0f
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val centerX = width / 2f
            val centerY = height / 2f
            val size = minOf(width, height) * 0.35f
            
            canvas.save()
            canvas.rotate(currentRotation, centerX, centerY)
            
            path.reset()
            path.moveTo(centerX - size, centerY - size / 2)
            path.lineTo(centerX, centerY + size / 2)
            path.lineTo(centerX + size, centerY - size / 2)
            
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        
        fun setRotationAngle(angle: Float) {
            currentRotation = angle
            invalidate()
        }
        
        fun animateTo(targetAngle: Float) {
            ValueAnimator.ofFloat(currentRotation, targetAngle).apply {
                duration = 200
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    currentRotation = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }
}
