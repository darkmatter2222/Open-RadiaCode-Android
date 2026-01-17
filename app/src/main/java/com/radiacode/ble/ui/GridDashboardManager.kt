package com.radiacode.ble.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.radiacode.ble.DashboardCardType
import com.radiacode.ble.DashboardItem
import com.radiacode.ble.DashboardLayout
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * Grid-based dashboard manager supporting 2-column layout.
 * 
 * This manager dynamically constructs the dashboard layout by:
 * 1. Accepting individual card views via registerCard()
 * 2. Building rows dynamically based on the DashboardLayout configuration
 * 3. Supporting drag-and-drop in edit mode with drop zones for columns
 * 
 * Key features:
 * - Cards can be placed in left column (0), right column (1), or span full width
 * - Drag-and-drop with visual feedback shows where cards can be dropped
 * - Dose rate and count rate cards are treated as separate items
 */
class GridDashboardManager(
    private val context: Context,
    private val dashboardContainer: LinearLayout,
    private val scrollView: NestedScrollView,
    private val deviceSelectorId: Int,
    private val onLayoutChanged: () -> Unit,
    private val onEditModeChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "GridDashboard"
        private const val HANDLE_SIZE_DP = 32
        private const val HANDLE_MARGIN_DP = 10
        private const val GAP_DP = 12
        private const val ROW_TAG_PREFIX = "grid_row_"
    }

    private val density = context.resources.displayMetrics.density
    private val gap = (GAP_DP * density).toInt()

    // Edit mode state
    var isEditMode = false
        private set

    // Drag state  
    var isDragging = false
        private set
    private var draggedCardId: String? = null
    private var dragShadowView: View? = null
    private var dragOriginalPosition: GridPosition? = null
    private var dropTarget: GridPosition? = null
    private var dragStartY = 0f
    private var dragStartX = 0f

    // Grid state: maps card IDs to their actual views
    private val cardViews = mutableMapOf<String, View>()
    
    // Current layout representation - built from DashboardLayout
    private val gridRows = mutableListOf<GridRow>()
    
    // Track wrapped cards in edit mode
    private val wrappedCards = mutableMapOf<String, FrameLayout>()
    
    // Drop zone overlay for showing where cards can be placed
    private var dropZoneOverlay: DropZoneOverlay? = null

    data class GridPosition(val row: Int, val column: Int, val spanFull: Boolean)
    
    data class GridRow(
        val leftCard: String? = null,
        val rightCard: String? = null,
        val fullWidthCard: String? = null
    ) {
        val isEmpty: Boolean get() = leftCard == null && rightCard == null && fullWidthCard == null
        val isFull: Boolean get() = fullWidthCard != null || (leftCard != null && rightCard != null)
        val hasSpace: Boolean get() = !isFull
        
        fun getCards(): List<String> = listOfNotNull(fullWidthCard, leftCard, rightCard)
    }

    /**
     * Register a card view by its type ID.
     */
    fun registerCard(cardId: String, view: View) {
        cardViews[cardId] = view
        Log.d(TAG, "Registered card: $cardId -> ${view::class.simpleName}")
    }

    /**
     * Apply a layout configuration - this rebuilds the entire dashboard.
     */
    fun applyLayout(layout: DashboardLayout) {
        Log.d(TAG, "Applying layout with ${layout.items.size} items")
        
        // Build grid from layout items
        gridRows.clear()
        
        // Group items by row
        val rowMap = layout.items.groupBy { it.row }
        val maxRow = rowMap.keys.maxOrNull() ?: 0
        
        for (rowIndex in 0..maxRow) {
            val items = rowMap[rowIndex] ?: emptyList()
            val row = when {
                items.isEmpty() -> continue
                items.size == 1 && items[0].spanFull -> {
                    GridRow(fullWidthCard = items[0].id)
                }
                items.size == 1 && items[0].column == 0 -> {
                    GridRow(leftCard = items[0].id)
                }
                items.size == 1 && items[0].column == 1 -> {
                    GridRow(rightCard = items[0].id)
                }
                items.size >= 2 -> {
                    val left = items.find { it.column == 0 }?.id
                    val right = items.find { it.column == 1 }?.id
                    GridRow(leftCard = left, rightCard = right)
                }
                else -> continue
            }
            gridRows.add(row)
        }
        
        rebuildDashboardViews()
    }

    /**
     * Rebuild the dashboard views based on current grid state.
     */
    private fun rebuildDashboardViews() {
        // Clear tracked wrappers
        wrappedCards.clear()
        
        // Find insert position (after device selector)
        var insertIndex = 0
        for (i in 0 until dashboardContainer.childCount) {
            val child = dashboardContainer.getChildAt(i)
            if (child.id == deviceSelectorId) {
                insertIndex = i + 1
                break
            }
        }
        
        // Remove all existing grid rows (tagged views)
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until dashboardContainer.childCount) {
            val child = dashboardContainer.getChildAt(i)
            val tag = child.tag as? String
            if (tag?.startsWith(ROW_TAG_PREFIX) == true) {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { view ->
            // Detach cards from row before removing
            if (view is ViewGroup) {
                detachCardsFromViewGroup(view)
            }
            dashboardContainer.removeView(view)
        }
        
        // Create and add new rows
        for ((rowIndex, row) in gridRows.withIndex()) {
            val rowView = createRowView(row, rowIndex)
            rowView.tag = "$ROW_TAG_PREFIX$rowIndex"
            
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (rowIndex > 0) {
                params.topMargin = gap
            }
            
            dashboardContainer.addView(rowView, insertIndex + rowIndex, params)
        }
        
        Log.d(TAG, "Rebuilt dashboard: ${gridRows.size} rows, editMode=$isEditMode")
    }
    
    /**
     * Recursively detach all cards from a view group.
     */
    private fun detachCardsFromViewGroup(parent: ViewGroup) {
        val children = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            children.add(parent.getChildAt(i))
        }
        
        for (child in children) {
            when (child) {
                is FrameLayout -> {
                    // Could be a wrapper - check for cards inside
                    detachCardsFromViewGroup(child)
                }
                is ViewGroup -> {
                    detachCardsFromViewGroup(child)
                }
            }
        }
        
        parent.removeAllViews()
    }

    /**
     * Create a row view for the given grid row.
     */
    private fun createRowView(row: GridRow, rowIndex: Int): View {
        return when {
            row.fullWidthCard != null -> {
                // Full-width card
                createCardContainer(row.fullWidthCard, rowIndex, 0, true)
            }
            else -> {
                // Side-by-side row
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    
                    // Left column
                    if (row.leftCard != null) {
                        val container = createCardContainer(row.leftCard, rowIndex, 0, false)
                        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        params.marginEnd = gap / 2
                        addView(container, params)
                    } else {
                        addView(createEmptySlot(rowIndex, 0), LinearLayout.LayoutParams(0, (160 * density).toInt(), 1f).apply {
                            marginEnd = gap / 2
                        })
                    }
                    
                    // Right column
                    if (row.rightCard != null) {
                        val container = createCardContainer(row.rightCard, rowIndex, 1, false)
                        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        params.marginStart = gap / 2
                        addView(container, params)
                    } else {
                        addView(createEmptySlot(rowIndex, 1), LinearLayout.LayoutParams(0, (160 * density).toInt(), 1f).apply {
                            marginStart = gap / 2
                        })
                    }
                }
            }
        }
    }
    
    /**
     * Create a container for a card - wraps with edit overlay if in edit mode.
     */
    private fun createCardContainer(cardId: String, row: Int, column: Int, spanFull: Boolean): View {
        val card = cardViews[cardId]
        
        if (card == null) {
            Log.w(TAG, "Card not found: $cardId")
            return createPlaceholder()
        }
        
        // Remove from current parent
        (card.parent as? ViewGroup)?.removeView(card)
        
        if (!isEditMode) {
            // Return card directly when not in edit mode
            return card
        }
        
        // Wrap card with edit overlay
        val wrapper = FrameLayout(context).apply {
            tag = "wrapper_$cardId"
        }
        
        wrapper.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Add drag overlay
        val position = GridPosition(row, column, spanFull)
        val overlay = DragOverlay(context, cardId, position)
        wrapper.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        wrappedCards[cardId] = wrapper
        
        return wrapper
    }
    
    private fun createPlaceholder(): View {
        return View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pro_surface))
            alpha = 0.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (100 * density).toInt()
            )
        }
    }

    private fun createEmptySlot(row: Int, column: Int): View {
        return FrameLayout(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pro_surface))
            alpha = 0.3f
            
            if (isEditMode) {
                // Add dashed border to show drop zone
                background = DropZoneBorder(context, density)
            }
        }
    }

    /**
     * Enter edit mode - shows drag handles and drop zones.
     */
    fun enterEditMode() {
        if (isEditMode) return
        
        Log.d(TAG, "Entering edit mode")
        isEditMode = true
        vibrate()
        rebuildDashboardViews()
        onEditModeChanged(true)
    }

    /**
     * Exit edit mode - saves layout and removes edit overlays.
     */
    fun exitEditMode() {
        if (!isEditMode) return
        
        Log.d(TAG, "Exiting edit mode")
        
        if (isDragging) {
            cancelDrag()
        }
        
        isEditMode = false
        saveCurrentLayout()
        rebuildDashboardViews()
        onEditModeChanged(false)
    }

    /**
     * Start dragging a card.
     */
    private fun startDrag(cardId: String, position: GridPosition, rawX: Float, rawY: Float) {
        if (!isEditMode || isDragging) return
        
        val wrapper = wrappedCards[cardId] ?: return
        
        isDragging = true
        draggedCardId = cardId
        dragShadowView = wrapper
        dragOriginalPosition = position
        dragStartY = rawY
        dragStartX = rawX
        dropTarget = position
        
        // Disable scrolling
        scrollView.requestDisallowInterceptTouchEvent(true)
        
        // Lift the card
        wrapper.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .alpha(0.9f)
            .setDuration(100)
            .start()
        wrapper.elevation = 20 * density
        
        // Show drop zone overlay
        showDropZoneOverlay()
        
        vibrate()
        Log.d(TAG, "Started drag: $cardId from row=${position.row}, col=${position.column}")
    }

    /**
     * Update drag position and find drop target.
     */
    private fun updateDrag(rawX: Float, rawY: Float) {
        if (!isDragging) return
        
        val wrapper = dragShadowView ?: return
        
        // Move the card
        wrapper.translationY = rawY - dragStartY
        wrapper.translationX = (rawX - dragStartX) * 0.3f // Reduced horizontal movement
        
        // Update drop target
        findDropTarget(rawX, rawY)
    }
    
    private fun findDropTarget(rawX: Float, rawY: Float) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val isRightSide = rawX > screenWidth / 2
        
        // Find which row we're over by checking Y positions
        var targetRow = 0
        
        val location = IntArray(2)
        dashboardContainer.getLocationOnScreen(location)
        val containerTop = location[1]
        
        var currentY = containerTop
        for ((index, _) in gridRows.withIndex()) {
            // Find the row view
            val rowView = dashboardContainer.findViewWithTag<View>("$ROW_TAG_PREFIX$index")
            if (rowView != null) {
                rowView.getLocationOnScreen(location)
                val rowMid = location[1] + rowView.height / 2
                
                if (rawY < rowMid) {
                    targetRow = index
                    break
                }
                targetRow = index + 1
            }
        }
        
        targetRow = targetRow.coerceIn(0, gridRows.size)
        
        val newTarget = GridPosition(
            row = targetRow,
            column = if (isRightSide) 1 else 0,
            spanFull = false
        )
        
        if (dropTarget != newTarget) {
            dropTarget = newTarget
            updateDropZoneOverlay(newTarget)
        }
    }
    
    private fun showDropZoneOverlay() {
        // Highlight possible drop positions
    }
    
    private fun updateDropZoneOverlay(target: GridPosition) {
        Log.d(TAG, "Drop target: row=${target.row}, col=${target.column}")
    }

    /**
     * End drag and commit the move.
     */
    private fun endDrag() {
        if (!isDragging) return
        
        val cardId = draggedCardId ?: return
        val wrapper = dragShadowView ?: return
        val originalPos = dragOriginalPosition ?: return
        val targetPos = dropTarget ?: return
        
        // Clear drag state FIRST
        isDragging = false
        draggedCardId = null
        dragShadowView = null
        dragOriginalPosition = null
        dropTarget = null
        
        // Re-enable scrolling
        scrollView.requestDisallowInterceptTouchEvent(false)
        
        // Hide drop zone overlay
        hideDropZoneOverlay()
        
        // Apply the move
        if (targetPos.row != originalPos.row || targetPos.column != originalPos.column) {
            Log.d(TAG, "Moving $cardId from (${originalPos.row},${originalPos.column}) to (${targetPos.row},${targetPos.column})")
            moveCard(cardId, originalPos, targetPos)
        } else {
            // Just reset visual state
            wrapper.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(150)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        wrapper.elevation = 0f
                    }
                })
                .start()
        }
    }
    
    private fun hideDropZoneOverlay() {
        dropZoneOverlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            dropZoneOverlay = null
        }
    }

    /**
     * Move a card to a new position in the grid.
     */
    private fun moveCard(cardId: String, from: GridPosition, to: GridPosition) {
        // Step 1: Remove card from old position
        if (from.row < gridRows.size) {
            val oldRow = gridRows[from.row]
            gridRows[from.row] = when {
                oldRow.fullWidthCard == cardId -> GridRow()
                oldRow.leftCard == cardId -> oldRow.copy(leftCard = null)
                oldRow.rightCard == cardId -> oldRow.copy(rightCard = null)
                else -> oldRow
            }
        }
        
        // Clean up empty rows
        gridRows.removeAll { it.isEmpty }
        
        // Step 2: Insert at new position
        val adjustedRow = to.row.coerceIn(0, gridRows.size)
        
        // Check if target row exists and has space
        if (adjustedRow < gridRows.size) {
            val targetRow = gridRows[adjustedRow]
            val cardType = DashboardCardType.fromId(cardId)
            val canBeSideBySide = cardType?.canBeSideBySide ?: true
            
            when {
                // Card can't be side-by-side - insert as full row
                !canBeSideBySide -> {
                    gridRows.add(adjustedRow, GridRow(fullWidthCard = cardId))
                }
                // Target has full-width card - insert before as new row
                targetRow.fullWidthCard != null -> {
                    val newRow = if (to.column == 0) GridRow(leftCard = cardId) else GridRow(rightCard = cardId)
                    gridRows.add(adjustedRow, newRow)
                }
                // Target left slot is empty and we want left
                to.column == 0 && targetRow.leftCard == null -> {
                    gridRows[adjustedRow] = targetRow.copy(leftCard = cardId)
                }
                // Target right slot is empty and we want right
                to.column == 1 && targetRow.rightCard == null -> {
                    gridRows[adjustedRow] = targetRow.copy(rightCard = cardId)
                }
                // Slot is occupied - insert as new row
                else -> {
                    val newRow = if (to.column == 0) GridRow(leftCard = cardId) else GridRow(rightCard = cardId)
                    gridRows.add(adjustedRow, newRow)
                }
            }
        } else {
            // Append new row
            val newRow = if (to.column == 0) GridRow(leftCard = cardId) else GridRow(rightCard = cardId)
            gridRows.add(newRow)
        }
        
        // Clean up empty rows again
        gridRows.removeAll { it.isEmpty }
        
        // Rebuild and save
        rebuildDashboardViews()
        saveCurrentLayout()
        
        vibrate()
    }

    /**
     * Cancel drag without committing changes.
     */
    private fun cancelDrag() {
        if (!isDragging) return
        
        val wrapper = dragShadowView
        
        // Clear state
        isDragging = false
        draggedCardId = null
        dragShadowView = null
        dragOriginalPosition = null
        dropTarget = null
        
        // Re-enable scrolling
        scrollView.requestDisallowInterceptTouchEvent(false)
        
        // Hide overlay
        hideDropZoneOverlay()
        
        // Reset visual state
        wrapper?.animate()
            ?.translationX(0f)
            ?.translationY(0f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(150)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    wrapper.elevation = 0f
                }
            })
            ?.start()
    }

    /**
     * Save current grid layout to preferences.
     */
    private fun saveCurrentLayout() {
        val items = mutableListOf<DashboardItem>()
        
        for ((rowIndex, row) in gridRows.withIndex()) {
            row.fullWidthCard?.let { cardId ->
                items.add(DashboardItem(
                    id = cardId,
                    row = rowIndex,
                    column = 0,
                    spanFull = true,
                    compactMode = false
                ))
            }
            row.leftCard?.let { cardId ->
                items.add(DashboardItem(
                    id = cardId,
                    row = rowIndex,
                    column = 0,
                    spanFull = false,
                    compactMode = false
                ))
            }
            row.rightCard?.let { cardId ->
                items.add(DashboardItem(
                    id = cardId,
                    row = rowIndex,
                    column = 1,
                    spanFull = false,
                    compactMode = false
                ))
            }
        }
        
        val layout = DashboardLayout(items = items)
        Prefs.setDashboardLayout(context, layout)
        onLayoutChanged()
        Log.d(TAG, "Saved layout: ${items.map { "${it.id}@(${it.row},${it.column})" }}")
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    /**
     * Drop zone background drawable.
     */
    class DropZoneBorder(context: Context, private val density: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * density, 8 * density), 0f)
        }
        
        override fun draw(canvas: Canvas) {
            val rect = RectF(
                4 * density, 4 * density,
                bounds.width() - 4 * density, bounds.height() - 4 * density
            )
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
        }
        
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Drop zone overlay showing possible drop positions.
     */
    inner class DropZoneOverlay(context: Context) : View(context)

    /**
     * Drag overlay view that sits on top of each card in edit mode.
     */
    inner class DragOverlay(
        context: Context,
        private val cardId: String,
        private val position: GridPosition
    ) : View(context) {

        private val handleSize = HANDLE_SIZE_DP * density
        private val handleMargin = HANDLE_MARGIN_DP * density
        private val cornerRadius = 8 * density

        private val handleRect = RectF()
        private var isPressed = false
        private var hasMoved = false
        private var touchDownX = 0f
        private var touchDownY = 0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_surface)
        }

        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            alpha = 100
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Edit mode border around entire card
            val cardRect = RectF(
                2 * density, 2 * density,
                width - 2 * density, height - 2 * density
            )
            canvas.drawRoundRect(cardRect, 16 * density, 16 * density, borderPaint)

            // Drag handle in top-left
            handleRect.set(
                handleMargin,
                handleMargin,
                handleMargin + handleSize,
                handleMargin + handleSize
            )

            // Handle background
            bgPaint.alpha = if (isPressed) 255 else 230
            canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, bgPaint)

            // Handle border
            accentPaint.alpha = if (isPressed) 255 else 180
            canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, accentPaint)

            // 6-dot grip icon
            val dotRadius = 2.5f * density
            val dotSpacingX = 7f * density
            val dotSpacingY = 6f * density
            val centerX = handleRect.centerX()
            val centerY = handleRect.centerY()

            dotPaint.color = if (isPressed) {
                ContextCompat.getColor(context, R.color.pro_cyan)
            } else {
                ContextCompat.getColor(context, R.color.pro_text_primary)
            }

            for (row in -1..1) {
                for (col in 0..1) {
                    val x = centerX + (col - 0.5f) * dotSpacingX
                    val y = centerY + row * dotSpacingY
                    canvas.drawCircle(x, y, dotRadius, dotPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (handleRect.contains(event.x, event.y)) {
                        isPressed = true
                        hasMoved = false
                        touchDownX = event.x
                        touchDownY = event.y
                        invalidate()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    return false
                }
                
                MotionEvent.ACTION_MOVE -> {
                    if (!isPressed) return false
                    
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    
                    if (dist > 12 * density && !hasMoved) {
                        hasMoved = true
                        startDrag(cardId, position, event.rawX, event.rawY)
                    }
                    
                    if (isDragging) {
                        updateDrag(event.rawX, event.rawY)
                    }
                    
                    return true
                }
                
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isPressed) return false
                    
                    isPressed = false
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    
                    if (isDragging) {
                        endDrag()
                    }
                    
                    return true
                }
            }
            return false
        }
    }
}
