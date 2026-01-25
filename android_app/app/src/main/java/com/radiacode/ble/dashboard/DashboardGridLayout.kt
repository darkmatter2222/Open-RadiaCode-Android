package com.radiacode.ble.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom ViewGroup that arranges child views in a 2-column grid.
 * Each child's position and size is determined by its DashboardGridItem configuration.
 * 
 * Supports:
 * - Drag-and-drop reordering
 * - Resize handles
 * - Edit mode overlay with grid lines
 * - Smooth animations
 */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    
    // Grid configuration
    private var layout: DashboardLayout = DashboardLayout.createDefault()
    private val panelViews = mutableMapOf<PanelType, View>()
    
    // Dimensions (calculated in onMeasure)
    private var cellWidth: Int = 0
    private var cellHeight: Int = (DashboardLayout.CELL_HEIGHT_DP * density).toInt()
    private val cellPadding: Int = (6 * density).toInt()
    
    // Edit mode state
    var isEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
            updatePanelEditModeState()
        }
    
    // Drag state
    private var draggedPanel: PanelType? = null
    private var draggedView: View? = null
    private var dragOffsetX: Float = 0f
    private var dragOffsetY: Float = 0f
    private var dragCurrentX: Float = 0f
    private var dragCurrentY: Float = 0f
    private var dragStartColumn: Int = 0
    private var dragStartRow: Int = 0
    private var dropTargetColumn: Int = -1
    private var dropTargetRow: Int = -1
    
    // Animation
    private val animatedPositions = mutableMapOf<PanelType, RectF>()
    
    // Paints for edit mode overlay
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = ContextCompat.getColor(context, R.color.pro_border)
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 4 * density), 0f)
    }
    
    private val dropTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_cyan)
        alpha = 40
    }
    
    private val invalidDropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_red)
        alpha = 40
    }
    
    // Gesture detector for drag
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (isEditMode) {
                tryStartDrag(e.x, e.y)
            }
        }
    })
    
    // Listener for layout changes
    var onLayoutChangedListener: ((DashboardLayout) -> Unit)? = null
    
    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }
    
    /**
     * Set the dashboard layout and rebuild views.
     */
    fun setLayout(newLayout: DashboardLayout) {
        layout = newLayout
        requestLayout()
        invalidate()
    }
    
    /**
     * Get the current layout.
     */
    fun getLayout(): DashboardLayout = layout.copy()
    
    /**
     * Register a panel view for a specific type.
     */
    fun registerPanel(panelType: PanelType, view: View) {
        panelViews[panelType] = view
        if (view.parent == null) {
            addView(view)
        }
        
        // Setup resize listener if it's a panel wrapper
        if (view is DashboardPanelWrapper) {
            view.onResizeListener = { colDelta, rowDelta ->
                handlePanelResize(panelType, colDelta, rowDelta)
            }
            
            // Update compact mode based on current layout
            val item = layout.getItem(panelType)
            if (item != null) {
                view.setCompactMode(item.colSpan == 1)
            }
        }
        
        requestLayout()
    }
    
    /**
     * Handle resize gesture from a panel.
     */
    private fun handlePanelResize(panelType: PanelType, colDelta: Int, rowDelta: Int) {
        val item = layout.getItem(panelType) ?: return
        
        val newColSpan = (item.colSpan + colDelta).coerceIn(
            item.panelType.minColSpan,
            item.panelType.maxColSpan
        )
        val newRowSpan = (item.rowSpan + rowDelta).coerceIn(
            item.panelType.minRowSpan,
            item.panelType.maxRowSpan
        )
        
        if (newColSpan != item.colSpan || newRowSpan != item.rowSpan) {
            resizePanel(panelType, newColSpan, newRowSpan)
        }
    }
    
    /**
     * Get registered panel view.
     */
    fun getPanelView(panelType: PanelType): View? = panelViews[panelType]
    
    /**
     * Calculate bounds for a grid item.
     */
    fun calculateBounds(item: DashboardGridItem): Rect {
        val left = item.column * cellWidth + cellPadding
        val top = item.row * cellHeight + cellPadding
        val right = (item.column + item.colSpan) * cellWidth - cellPadding
        val bottom = (item.row + item.rowSpan) * cellHeight - cellPadding
        return Rect(left, top, right, bottom)
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellWidth = width / DashboardLayout.GRID_COLUMNS
        
        // Calculate total height needed
        val totalRows = layout.getTotalRows()
        val height = totalRows * cellHeight
        
        // Measure each child based on its grid position
        for ((panelType, view) in panelViews) {
            val item = layout.getItem(panelType) ?: continue
            val bounds = calculateBounds(item)
            
            val childWidthSpec = MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY)
            view.measure(childWidthSpec, childHeightSpec)
        }
        
        setMeasuredDimension(width, height)
    }
    
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((panelType, view) in panelViews) {
            val item = layout.getItem(panelType) ?: continue
            
            // Skip dragged panel - it's drawn separately
            if (panelType == draggedPanel) {
                view.layout(0, 0, 0, 0) // Hide during drag
                continue
            }
            
            val bounds = calculateBounds(item)
            
            // Check if we have an animated position
            val animRect = animatedPositions[panelType]
            if (animRect != null) {
                view.layout(
                    animRect.left.toInt(),
                    animRect.top.toInt(),
                    animRect.right.toInt(),
                    animRect.bottom.toInt()
                )
            } else {
                view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
    }
    
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        
        // Draw edit mode overlay
        if (isEditMode) {
            drawGridLines(canvas)
            drawDropTarget(canvas)
        }
        
        // Draw dragged panel on top
        drawDraggedPanel(canvas)
    }
    
    private fun drawGridLines(canvas: Canvas) {
        val width = width.toFloat()
        val totalRows = max(layout.getTotalRows(), 6) // Show at least 6 rows of grid
        
        // Vertical lines
        for (col in 0..DashboardLayout.GRID_COLUMNS) {
            val x = col * cellWidth.toFloat()
            canvas.drawLine(x, 0f, x, totalRows * cellHeight.toFloat(), gridLinePaint)
        }
        
        // Horizontal lines
        for (row in 0..totalRows) {
            val y = row * cellHeight.toFloat()
            canvas.drawLine(0f, y, width, y, gridLinePaint)
        }
    }
    
    private fun drawDropTarget(canvas: Canvas) {
        if (draggedPanel == null || dropTargetColumn < 0) return
        
        val item = layout.getItem(draggedPanel!!) ?: return
        
        // Calculate drop target bounds
        val left = dropTargetColumn * cellWidth.toFloat()
        val top = dropTargetRow * cellHeight.toFloat()
        val right = (dropTargetColumn + item.colSpan) * cellWidth.toFloat()
        val bottom = (dropTargetRow + item.rowSpan) * cellHeight.toFloat()
        
        // Check if valid drop
        val isValidDrop = isValidDropPosition(item, dropTargetColumn, dropTargetRow)
        val paint = if (isValidDrop) dropTargetPaint else invalidDropPaint
        
        canvas.drawRect(left, top, right, bottom, paint)
    }
    
    private fun drawDraggedPanel(canvas: Canvas) {
        val view = draggedView ?: return
        
        canvas.save()
        canvas.translate(dragCurrentX - dragOffsetX, dragCurrentY - dragOffsetY)
        
        // Scale up slightly
        val scale = 1.05f
        canvas.scale(scale, scale, view.width / 2f, view.height / 2f)
        
        // Draw shadow
        val shadowPaint = Paint().apply {
            color = Color.BLACK
            alpha = 60
            maskFilter = BlurMaskFilter(16 * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            -8 * density, -8 * density,
            view.width + 8 * density, view.height + 8 * density,
            12 * density, 12 * density,
            shadowPaint
        )
        
        view.draw(canvas)
        canvas.restore()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        
        if (draggedPanel != null) {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    dragCurrentX = event.x
                    dragCurrentY = event.y
                    updateDropTarget()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    finishDrag()
                    return true
                }
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode && draggedPanel != null) {
            return true
        }
        gestureDetector.onTouchEvent(event)
        return super.onInterceptTouchEvent(event)
    }
    
    private fun tryStartDrag(x: Float, y: Float) {
        // Find which panel was touched
        for ((panelType, view) in panelViews) {
            if (view.left <= x && x <= view.right &&
                view.top <= y && y <= view.bottom) {
                startDrag(panelType, view, x, y)
                break
            }
        }
    }
    
    private fun startDrag(panelType: PanelType, view: View, x: Float, y: Float) {
        val item = layout.getItem(panelType) ?: return
        
        draggedPanel = panelType
        draggedView = view
        dragOffsetX = x - view.left
        dragOffsetY = y - view.top
        dragCurrentX = x
        dragCurrentY = y
        dragStartColumn = item.column
        dragStartRow = item.row
        
        updateDropTarget()
        invalidate()
        requestLayout()
    }
    
    private fun updateDropTarget() {
        val item = layout.getItem(draggedPanel ?: return) ?: return
        
        // Calculate target cell based on center of dragged view
        val centerX = dragCurrentX - dragOffsetX + (item.colSpan * cellWidth) / 2f
        val centerY = dragCurrentY - dragOffsetY + (item.rowSpan * cellHeight) / 2f
        
        dropTargetColumn = ((centerX / cellWidth).toInt()).coerceIn(0, DashboardLayout.GRID_COLUMNS - item.colSpan)
        dropTargetRow = max(0, (centerY / cellHeight).toInt())
    }
    
    private fun isValidDropPosition(item: DashboardGridItem, column: Int, row: Int): Boolean {
        // Check bounds
        if (column < 0 || column + item.colSpan > DashboardLayout.GRID_COLUMNS || row < 0) {
            return false
        }
        
        // Check for collisions with other panels (excluding the dragged one)
        for (other in layout.getItems()) {
            if (other.panelType == item.panelType) continue
            
            // Check overlap
            val itemEndCol = column + item.colSpan
            val itemEndRow = row + item.rowSpan
            
            if (column < other.endColumn && itemEndCol > other.column &&
                row < other.endRow && itemEndRow > other.row) {
                return false
            }
        }
        
        return true
    }
    
    private fun finishDrag() {
        val panel = draggedPanel ?: return
        val item = layout.getItem(panel) ?: return
        
        // Apply drop if valid
        if (isValidDropPosition(item, dropTargetColumn, dropTargetRow)) {
            // Animate to new position
            animatePanelMove(panel, item.column, item.row, dropTargetColumn, dropTargetRow)
            
            // Update layout
            item.column = dropTargetColumn
            item.row = dropTargetRow
            
            // Compact the layout
            layout.compact()
            
            // Notify listener
            onLayoutChangedListener?.invoke(layout)
        }
        
        // Clear drag state
        draggedPanel = null
        draggedView = null
        dropTargetColumn = -1
        dropTargetRow = -1
        
        invalidate()
        requestLayout()
    }
    
    private fun animatePanelMove(panelType: PanelType, fromCol: Int, fromRow: Int, toCol: Int, toRow: Int) {
        val view = panelViews[panelType] ?: return
        val item = layout.getItem(panelType) ?: return
        
        val fromBounds = RectF(
            (fromCol * cellWidth + cellPadding).toFloat(),
            (fromRow * cellHeight + cellPadding).toFloat(),
            ((fromCol + item.colSpan) * cellWidth - cellPadding).toFloat(),
            ((fromRow + item.rowSpan) * cellHeight - cellPadding).toFloat()
        )
        
        val toBounds = RectF(
            (toCol * cellWidth + cellPadding).toFloat(),
            (toRow * cellHeight + cellPadding).toFloat(),
            ((toCol + item.colSpan) * cellWidth - cellPadding).toFloat(),
            ((toRow + item.rowSpan) * cellHeight - cellPadding).toFloat()
        )
        
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                animatedPositions[panelType] = RectF(
                    fromBounds.left + (toBounds.left - fromBounds.left) * progress,
                    fromBounds.top + (toBounds.top - fromBounds.top) * progress,
                    fromBounds.right + (toBounds.right - fromBounds.right) * progress,
                    fromBounds.bottom + (toBounds.bottom - fromBounds.bottom) * progress
                )
                requestLayout()
            }
        }
        
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                animatedPositions.remove(panelType)
                requestLayout()
            }
        })
        
        animator.start()
    }
    
    /**
     * Resize a panel and sync adjacent if needed.
     */
    fun resizePanel(panelType: PanelType, newColSpan: Int, newRowSpan: Int): Boolean {
        val item = layout.getItem(panelType) ?: return false
        val oldColSpan = item.colSpan
        val oldRowSpan = item.rowSpan
        
        val success = layout.resizeItem(item, newColSpan, newRowSpan, syncAdjacent = true)
        
        if (success) {
            // Animate resize
            animatePanelResize(panelType, oldColSpan, oldRowSpan, newColSpan, newRowSpan)
            
            // Update compact mode based on new colSpan
            val view = panelViews[panelType]
            if (view is DashboardPanelWrapper) {
                view.setCompactMode(newColSpan == 1)
            }
            
            // Also animate and update adjacent if it changed
            val adjacent = layout.getAdjacentInRow(item)
            if (adjacent != null && adjacent.rowSpan != oldRowSpan) {
                animatePanelResize(adjacent.panelType, adjacent.colSpan, oldRowSpan, adjacent.colSpan, newRowSpan)
                
                // Update adjacent compact mode too
                val adjacentView = panelViews[adjacent.panelType]
                if (adjacentView is DashboardPanelWrapper) {
                    adjacentView.setCompactMode(adjacent.colSpan == 1)
                }
            }
            
            onLayoutChangedListener?.invoke(layout)
        }
        
        return success
    }
    
    private fun animatePanelResize(panelType: PanelType, fromColSpan: Int, fromRowSpan: Int, toColSpan: Int, toRowSpan: Int) {
        val item = layout.getItem(panelType) ?: return
        
        val fromBounds = RectF(
            (item.column * cellWidth + cellPadding).toFloat(),
            (item.row * cellHeight + cellPadding).toFloat(),
            ((item.column + fromColSpan) * cellWidth - cellPadding).toFloat(),
            ((item.row + fromRowSpan) * cellHeight - cellPadding).toFloat()
        )
        
        val toBounds = RectF(
            (item.column * cellWidth + cellPadding).toFloat(),
            (item.row * cellHeight + cellPadding).toFloat(),
            ((item.column + toColSpan) * cellWidth - cellPadding).toFloat(),
            ((item.row + toRowSpan) * cellHeight - cellPadding).toFloat()
        )
        
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                animatedPositions[panelType] = RectF(
                    fromBounds.left + (toBounds.left - fromBounds.left) * progress,
                    fromBounds.top + (toBounds.top - fromBounds.top) * progress,
                    fromBounds.right + (toBounds.right - fromBounds.right) * progress,
                    fromBounds.bottom + (toBounds.bottom - fromBounds.bottom) * progress
                )
                requestLayout()
            }
        }
        
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                animatedPositions.remove(panelType)
                requestLayout()
            }
        })
        
        animator.start()
    }
    
    /**
     * Reset to default layout with animation.
     */
    fun resetToDefault() {
        val newLayout = DashboardLayout.createDefault()
        
        // Animate each panel to its new position
        for (item in newLayout.getItems()) {
            val oldItem = layout.getItem(item.panelType)
            if (oldItem != null) {
                animatePanelMove(item.panelType, oldItem.column, oldItem.row, item.column, item.row)
            }
        }
        
        layout = newLayout
        onLayoutChangedListener?.invoke(layout)
        requestLayout()
    }
    
    private fun updatePanelEditModeState() {
        // Notify panels of edit mode change (for showing/hiding drag handles)
        for ((_, view) in panelViews) {
            if (view is DashboardPanelContainer) {
                view.setEditMode(isEditMode)
            }
        }
    }
}

/**
 * Interface for panel containers that support edit mode.
 */
interface DashboardPanelContainer {
    fun setEditMode(editMode: Boolean)
    fun setCompactMode(compact: Boolean)
}
