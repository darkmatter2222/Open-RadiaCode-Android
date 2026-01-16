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
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.radiacode.ble.DashboardCardType
import com.radiacode.ble.DashboardItem
import com.radiacode.ble.DashboardLayout
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * Manages drag-and-drop functionality for the dashboard cards.
 * When edit mode is activated, small drag handles appear in the top-left corner of each card.
 * Cards can be dragged to reorder them, with other cards animating to make room.
 */
class DraggableDashboardManager(
    private val context: Context,
    private val dashboardPanel: LinearLayout,
    private val onLayoutChanged: () -> Unit
) {
    companion object {
        private const val TAG = "DraggableDashboard"
        private const val HANDLE_SIZE_DP = 28
        private const val HANDLE_MARGIN_DP = 8
    }

    private val density = context.resources.displayMetrics.density

    // Edit mode state
    var isEditMode = false
        private set

    // Drag state  
    var isDragging = false
        private set
    private var draggedCardId: String? = null
    private var draggedViewWrapper: ViewGroup? = null
    private var dragOriginalIndex = -1
    private var dropTargetIndex = -1
    private var dragStartY = 0f

    // Registered cards: cardId -> view
    private val cards = mutableMapOf<String, View>()
    
    // Card overlays showing drag handles
    private val overlays = mutableMapOf<String, DragHandleOverlay>()

    /**
     * Register all dashboard cards.
     */
    fun registerCards(
        metricCardsRow: View,
        intelligenceCard: View,
        doseChartPanel: View,
        cpsChartPanel: View,
        isotopePanel: View
    ) {
        cards[DashboardCardType.DOSE_CARD.id] = metricCardsRow
        cards[DashboardCardType.INTELLIGENCE.id] = intelligenceCard
        cards[DashboardCardType.DOSE_CHART.id] = doseChartPanel
        cards[DashboardCardType.CPS_CHART.id] = cpsChartPanel
        cards[DashboardCardType.ISOTOPE.id] = isotopePanel
    }

    /**
     * Toggle edit mode. When enabled, drag handles appear on cards.
     */
    fun toggleEditMode(): Boolean {
        isEditMode = !isEditMode
        
        if (isEditMode) {
            vibrate()
            showDragHandles()
        } else {
            hideDragHandles()
            if (isDragging) {
                cancelDrag()
            }
        }
        
        return isEditMode
    }

    /**
     * Enter edit mode.
     */
    fun enterEditMode() {
        if (!isEditMode) {
            isEditMode = true
            vibrate()
            showDragHandles()
        }
    }

    /**
     * Exit edit mode.
     */
    fun exitEditMode() {
        if (isEditMode) {
            isEditMode = false
            hideDragHandles()
            if (isDragging) {
                cancelDrag()
            }
        }
    }

    private fun showDragHandles() {
        for ((cardId, cardView) in cards) {
            addDragHandleOverlay(cardId, cardView)
        }
    }

    private fun hideDragHandles() {
        for ((cardId, _) in cards) {
            removeDragHandleOverlay(cardId)
        }
    }

    private fun addDragHandleOverlay(cardId: String, cardView: View) {
        val parent = cardView.parent as? ViewGroup ?: return
        
        // Check if overlay already exists
        val existingOverlay = parent.findViewWithTag<View>("drag_overlay_$cardId")
        if (existingOverlay != null) return
        
        val cardIndex = parent.indexOfChild(cardView)
        val cardParams = cardView.layoutParams
        
        // Create a wrapper FrameLayout
        val wrapper = android.widget.FrameLayout(context).apply {
            layoutParams = cardParams
            tag = "wrapper_$cardId"
        }
        
        // Move card into wrapper
        parent.removeViewAt(cardIndex)
        wrapper.addView(cardView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Create and add overlay
        val overlay = DragHandleOverlay(context, cardId, this).apply {
            tag = "drag_overlay_$cardId"
        }
        overlays[cardId] = overlay
        
        wrapper.addView(overlay, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        // Add wrapper back to parent
        parent.addView(wrapper, cardIndex)
        
        Log.d(TAG, "Added drag handle overlay for $cardId")
    }

    private fun removeDragHandleOverlay(cardId: String) {
        val cardView = cards[cardId] ?: return
        val wrapper = cardView.parent as? android.widget.FrameLayout ?: return
        val parent = wrapper.parent as? ViewGroup ?: return
        
        if (wrapper.tag != "wrapper_$cardId") return
        
        val wrapperIndex = parent.indexOfChild(wrapper)
        val cardParams = wrapper.layoutParams
        
        // Remove card from wrapper
        wrapper.removeView(cardView)
        
        // Remove wrapper from parent
        parent.removeViewAt(wrapperIndex)
        
        // Add card back directly
        cardView.layoutParams = cardParams
        parent.addView(cardView, wrapperIndex)
        
        overlays.remove(cardId)
        
        Log.d(TAG, "Removed drag handle overlay for $cardId")
    }

    /**
     * Start dragging a card.
     */
    fun startDrag(cardId: String, touchY: Float) {
        if (!isEditMode) return
        
        val cardView = cards[cardId] ?: return
        val wrapper = cardView.parent as? ViewGroup ?: return
        val parent = wrapper.parent as? ViewGroup ?: return
        
        isDragging = true
        draggedCardId = cardId
        draggedViewWrapper = wrapper
        dragOriginalIndex = parent.indexOfChild(wrapper)
        dragStartY = touchY
        dropTargetIndex = dragOriginalIndex
        
        // Visual feedback
        wrapper.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .alpha(0.92f)
            .setDuration(100)
            .start()
        wrapper.elevation = 12 * density
        
        vibrate()
        Log.d(TAG, "Started dragging $cardId from index $dragOriginalIndex")
    }

    /**
     * Update drag position.
     */
    fun updateDrag(touchY: Float): Boolean {
        if (!isDragging) return false
        
        val wrapper = draggedViewWrapper ?: return false
        val deltaY = touchY - dragStartY
        
        wrapper.translationY = deltaY
        
        // Find new drop target
        updateDropTarget(touchY)
        
        return true
    }

    private fun updateDropTarget(touchY: Float) {
        val parent = dashboardPanel
        var newTarget = dragOriginalIndex
        
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            
            // Skip device selector and dragged card
            if (child.id == R.id.deviceSelector) continue
            if (child == draggedViewWrapper) continue
            
            val location = IntArray(2)
            child.getLocationOnScreen(location)
            val childCenterY = location[1] + child.height / 2
            
            if (touchY < childCenterY) {
                newTarget = i
                break
            } else {
                newTarget = i + 1
            }
        }
        
        if (newTarget != dropTargetIndex) {
            dropTargetIndex = newTarget
            animateOtherCards()
        }
    }

    private fun animateOtherCards() {
        val parent = dashboardPanel
        val draggedHeight = draggedViewWrapper?.height ?: return
        val gap = 12 * density
        
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            
            if (child.id == R.id.deviceSelector) continue
            if (child == draggedViewWrapper) continue
            
            val targetTranslation = when {
                dropTargetIndex > dragOriginalIndex && 
                    i > dragOriginalIndex && i <= dropTargetIndex -> {
                    -(draggedHeight + gap)
                }
                dropTargetIndex < dragOriginalIndex && 
                    i >= dropTargetIndex && i < dragOriginalIndex -> {
                    draggedHeight + gap
                }
                else -> 0f
            }
            
            child.animate()
                .translationY(targetTranslation)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * End drag and apply changes.
     */
    fun endDrag() {
        if (!isDragging) return
        
        val wrapper = draggedViewWrapper ?: return
        val parent = dashboardPanel
        
        // Reset other cards' translations
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child != wrapper) {
                child.animate()
                    .translationY(0f)
                    .setDuration(150)
                    .start()
            }
        }
        
        // If position changed, reorder
        if (dropTargetIndex != dragOriginalIndex && dropTargetIndex >= 0) {
            parent.removeView(wrapper)
            
            val insertIndex = if (dropTargetIndex > dragOriginalIndex) {
                dropTargetIndex - 1
            } else {
                dropTargetIndex
            }
            
            parent.addView(wrapper, insertIndex.coerceIn(1, parent.childCount))
            
            saveCurrentLayout()
            onLayoutChanged()
            
            Log.d(TAG, "Moved $draggedCardId from $dragOriginalIndex to $insertIndex")
        }
        
        // Reset dragged view
        wrapper.animate()
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
        
        isDragging = false
        draggedCardId = null
        draggedViewWrapper = null
        dragOriginalIndex = -1
        dropTargetIndex = -1
    }

    /**
     * Cancel drag without applying changes.
     */
    fun cancelDrag() {
        if (!isDragging) return
        
        val wrapper = draggedViewWrapper ?: return
        val parent = dashboardPanel
        
        for (i in 0 until parent.childCount) {
            parent.getChildAt(i).animate()
                .translationY(0f)
                .setDuration(150)
                .start()
        }
        
        wrapper.animate()
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
        
        isDragging = false
        draggedCardId = null
        draggedViewWrapper = null
        dragOriginalIndex = -1
        dropTargetIndex = -1
    }

    private fun saveCurrentLayout() {
        val items = mutableListOf<DashboardItem>()
        var row = 0
        
        for (i in 0 until dashboardPanel.childCount) {
            val child = dashboardPanel.getChildAt(i)
            
            if (child.id == R.id.deviceSelector) continue
            
            val cardId = findCardIdInView(child)
            if (cardId != null) {
                items.add(DashboardItem(
                    id = cardId,
                    row = row,
                    column = 0,
                    spanFull = true,
                    compactMode = false
                ))
                row++
            }
        }
        
        val layout = DashboardLayout(items = items)
        Prefs.setDashboardLayout(context, layout)
        Log.d(TAG, "Saved layout: ${items.map { it.id }}")
    }

    private fun findCardIdInView(view: View): String? {
        for ((cardId, cardView) in cards) {
            if (view == cardView) return cardId
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (view.getChildAt(i) == cardView) return cardId
                }
            }
        }
        return null
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
     * Overlay view that draws the drag handle and handles touch events.
     */
    class DragHandleOverlay(
        context: Context,
        private val cardId: String,
        private val manager: DraggableDashboardManager
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val handleSize = HANDLE_SIZE_DP * density
        private val handleMargin = HANDLE_MARGIN_DP * density
        private val cornerRadius = 6 * density

        private val handleRect = RectF()
        private var isHandlePressed = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_surface)
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_text_primary)
        }

        private val editModeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            alpha = 80
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw subtle border around entire card
            val cardRect = RectF(
                2 * density, 2 * density,
                width - 2 * density, height - 2 * density
            )
            canvas.drawRoundRect(cardRect, 16 * density, 16 * density, editModeBorderPaint)

            // Draw drag handle in top-left
            handleRect.set(
                handleMargin,
                handleMargin,
                handleMargin + handleSize,
                handleMargin + handleSize
            )

            bgPaint.alpha = if (isHandlePressed) 255 else 220
            canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, bgPaint)

            borderPaint.alpha = if (isHandlePressed) 255 else 180
            canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, borderPaint)

            // Draw 2x3 dot grid
            val dotRadius = 2f * density
            val dotSpacingX = 5f * density
            val dotSpacingY = 4.5f * density
            val centerX = handleRect.centerX()
            val centerY = handleRect.centerY()

            dotPaint.color = if (isHandlePressed) {
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
                        isHandlePressed = true
                        invalidate()
                        
                        // Start drag after brief touch
                        postDelayed({
                            if (isHandlePressed && !manager.isDragging) {
                                manager.startDrag(cardId, event.rawY)
                            }
                        }, 100)
                        
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (manager.isDragging) {
                        manager.updateDrag(event.rawY)
                        return true
                    } else if (isHandlePressed) {
                        manager.startDrag(cardId, event.rawY)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHandlePressed = false
                    invalidate()
                    
                    if (manager.isDragging) {
                        manager.endDrag()
                        return true
                    }
                }
            }
            return false
        }
    }
}
