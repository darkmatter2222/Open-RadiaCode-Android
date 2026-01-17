package com.radiacode.ble.dashboard

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import com.radiacode.ble.ui.MetricCardView
import com.radiacode.ble.ui.ProChartView
import com.radiacode.ble.ui.StatRowView

/**
 * Factory for creating dashboard panel views.
 * Each panel is wrapped in a container that supports edit mode (drag/resize handles).
 */
object DashboardPanelFactory {
    
    /**
     * Create a panel view for the given type.
     * Returns a DashboardPanelWrapper containing the panel content.
     */
    fun createPanel(context: Context, panelType: PanelType): DashboardPanelWrapper {
        val inflater = LayoutInflater.from(context)
        
        // Create the wrapper (container with drag/resize handles)
        val wrapper = DashboardPanelWrapper(context)
        
        // Create panel-specific content
        val content = when (panelType) {
            PanelType.DELTA_DOSE -> createDeltaCard(context, isDose = true)
            PanelType.DELTA_COUNT -> createDeltaCard(context, isDose = false)
            PanelType.INTELLIGENCE -> inflater.inflate(R.layout.dashboard_panel_intelligence, null)
            PanelType.DOSE_CHART -> createChartPanel(inflater, isDose = true)
            PanelType.COUNT_CHART -> createChartPanel(inflater, isDose = false)
            PanelType.ISOTOPE_ID -> createIsotopePanel(context)
        }
        
        wrapper.setContent(content)
        wrapper.panelType = panelType
        
        return wrapper
    }
    
    private fun createDeltaCard(context: Context, isDose: Boolean): MetricCardView {
        val card = MetricCardView(context)
        
        if (isDose) {
            card.setLabel("DELTA DOSE RATE")
            card.setAccentColor(ContextCompat.getColor(context, R.color.pro_cyan))
        } else {
            card.setLabel("DELTA COUNT RATE")
            card.setAccentColor(ContextCompat.getColor(context, R.color.pro_magenta))
        }
        
        card.setValueText("—")
        card.setTrend(0f)
        
        return card
    }
    
    private fun createChartPanel(inflater: LayoutInflater, isDose: Boolean): View {
        val view = inflater.inflate(R.layout.dashboard_panel_chart, null)
        
        val title = view.findViewById<TextView>(R.id.chartTitle)
        val chart = view.findViewById<ProChartView>(R.id.chartView)
        
        val context = view.context
        if (isDose) {
            title.text = "REAL TIME DOSE RATE"
            chart.setAccentColor(ContextCompat.getColor(context, R.color.pro_cyan))
        } else {
            title.text = "REAL TIME COUNT RATE"
            chart.setAccentColor(ContextCompat.getColor(context, R.color.pro_magenta))
        }
        
        return view
    }
    
    private fun createIsotopePanel(context: Context): View {
        // For now, create a placeholder - will integrate with existing isotope UI
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        
        val title = TextView(context).apply {
            text = "ISOTOPE IDENTIFICATION"
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_muted))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
        }
        
        layout.addView(title)
        
        val placeholder = TextView(context).apply {
            text = "Isotope panel content"
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        
        layout.addView(placeholder)
        
        return layout
    }
}

/**
 * Wrapper view for dashboard panels.
 * Provides drag handle and resize handles in edit mode.
 */
class DashboardPanelWrapper(context: Context) : FrameLayout(context), DashboardPanelContainer {
    
    var panelType: PanelType = PanelType.DELTA_DOSE
    
    private var contentView: View? = null
    private val dragHandle: ImageView
    private val resizeHandleCorner: View
    private val resizeHandleRight: View
    private val resizeHandleBottom: View
    
    private var isCompact: Boolean = false
    private var inEditMode: Boolean = false
    
    // Listener for resize actions
    var onResizeListener: ((Int, Int) -> Unit)? = null
    
    init {
        // Set background
        setBackgroundResource(R.drawable.card_background)
        clipChildren = false
        clipToPadding = false
        
        // Add drag handle
        dragHandle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            alpha = 0.7f
            visibility = View.GONE
            setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless)
            contentDescription = "Drag to reposition"
            setPadding(8, 8, 8, 8)
        }
        
        val dragHandleParams = LayoutParams(
            (32 * resources.displayMetrics.density).toInt(),
            (32 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            marginEnd = (4 * resources.displayMetrics.density).toInt()
            topMargin = (4 * resources.displayMetrics.density).toInt()
        }
        addView(dragHandle, dragHandleParams)
        
        // Add resize handles
        val handleSize = (24 * resources.displayMetrics.density).toInt()
        val edgeWidth = (12 * resources.displayMetrics.density).toInt()
        val edgeLength = (48 * resources.displayMetrics.density).toInt()
        
        // Corner resize handle
        resizeHandleCorner = View(context).apply {
            setBackgroundResource(R.drawable.resize_handle_corner)
            visibility = View.GONE
        }
        val cornerParams = LayoutParams(handleSize, handleSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        }
        addView(resizeHandleCorner, cornerParams)
        
        // Right edge resize
        resizeHandleRight = View(context).apply {
            setBackgroundResource(R.drawable.resize_handle_edge)
            visibility = View.GONE
            rotation = 90f
        }
        val rightParams = LayoutParams(edgeWidth, edgeLength).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
        }
        addView(resizeHandleRight, rightParams)
        
        // Bottom edge resize
        resizeHandleBottom = View(context).apply {
            setBackgroundResource(R.drawable.resize_handle_edge)
            visibility = View.GONE
        }
        val bottomParams = LayoutParams(edgeLength, edgeWidth).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }
        addView(resizeHandleBottom, bottomParams)
        
        // Setup resize touch listeners
        setupResizeListeners()
    }
    
    fun setContent(view: View) {
        contentView?.let { removeView(it) }
        contentView = view
        
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        addView(view, 0, params)
    }
    
    fun getContent(): View? = contentView
    
    override fun setEditMode(editMode: Boolean) {
        inEditMode = editMode
        
        dragHandle.visibility = if (editMode) View.VISIBLE else View.GONE
        resizeHandleCorner.visibility = if (editMode) View.VISIBLE else View.GONE
        resizeHandleRight.visibility = if (editMode) View.VISIBLE else View.GONE
        resizeHandleBottom.visibility = if (editMode) View.VISIBLE else View.GONE
        
        // Disable interactions with content during edit mode
        contentView?.isEnabled = !editMode
    }
    
    override fun setCompactMode(compact: Boolean) {
        isCompact = compact
        updateContentForSize()
    }
    
    /**
     * Update content visibility based on current size constraints.
     * Charts hide stats bar when compact (colSpan=1).
     */
    private fun updateContentForSize() {
        val content = contentView ?: return
        
        when (panelType) {
            PanelType.DOSE_CHART, PanelType.COUNT_CHART -> {
                // Hide stats bar in compact mode
                val statsView = content.findViewById<StatRowView>(R.id.chartStats)
                val miniRange = content.findViewById<View>(R.id.chartMiniRange)
                
                statsView?.visibility = if (isCompact) View.GONE else View.VISIBLE
                miniRange?.visibility = if (isCompact) View.VISIBLE else View.GONE
            }
            
            PanelType.INTELLIGENCE -> {
                // Hide expanded section and summary in compact mode
                val summary = content.findViewById<TextView>(R.id.intelligenceSummary)
                val expanded = content.findViewById<View>(R.id.expandedSection)
                val metricsRow = content.findViewById<View>(R.id.metricsRow)
                
                summary?.visibility = if (isCompact) View.GONE else View.VISIBLE
                expanded?.visibility = View.GONE // Always hidden unless explicitly expanded
                metricsRow?.visibility = if (isCompact) View.GONE else View.VISIBLE
            }
            
            else -> {
                // Delta cards and isotope panel adapt automatically
            }
        }
    }
    
    private fun setupResizeListeners() {
        var initialTouchX = 0f
        var initialTouchY = 0f
        var resizeDirection = ResizeDirection.NONE
        
        val handleTouch = { view: View, event: android.view.MotionEvent, direction: ResizeDirection ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    resizeDirection = direction
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    // Calculate grid units moved
                    val gridLayout = parent as? DashboardGridLayout
                    if (gridLayout != null) {
                        val cellWidth = gridLayout.width / DashboardLayout.GRID_COLUMNS
                        val cellHeight = DashboardLayout.CELL_HEIGHT_DP * resources.displayMetrics.density
                        
                        val colChange = (deltaX / cellWidth).toInt()
                        val rowChange = (deltaY / cellHeight).toInt()
                        
                        if (colChange != 0 || rowChange != 0) {
                            onResizeListener?.invoke(
                                if (direction == ResizeDirection.HORIZONTAL || direction == ResizeDirection.BOTH) colChange else 0,
                                if (direction == ResizeDirection.VERTICAL || direction == ResizeDirection.BOTH) rowChange else 0
                            )
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    resizeDirection = ResizeDirection.NONE
                    true
                }
                else -> false
            }
        }
        
        // Corner handle - resize both dimensions
        resizeHandleCorner.setOnTouchListener { view, event ->
            handleTouch(view, event, ResizeDirection.BOTH)
        }
        
        // Right handle - resize width (colSpan)
        resizeHandleRight.setOnTouchListener { view, event ->
            handleTouch(view, event, ResizeDirection.HORIZONTAL)
        }
        
        // Bottom handle - resize height (rowSpan)
        resizeHandleBottom.setOnTouchListener { view, event ->
            handleTouch(view, event, ResizeDirection.VERTICAL)
        }
    }
    
    private enum class ResizeDirection {
        NONE, HORIZONTAL, VERTICAL, BOTH
    }
}
