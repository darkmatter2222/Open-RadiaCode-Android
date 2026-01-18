package com.radiacode.ble.dashboard

import android.animation.ValueAnimator
import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * Helper class that enables drag-to-reorder functionality on a LinearLayout's children.
 * 
 * Each draggable section is identified by a tag. When a section is dragged and dropped
 * onto another section, they swap positions.
 */
class DashboardReorderHelper(
    private val container: LinearLayout,
    private val onOrderChanged: (List<String>) -> Unit
) {
    
    // Map of section tags to their views
    private val sectionViews = mutableMapOf<String, View>()
    
    // Currently in edit mode?
    var isEditMode: Boolean = false
        set(value) {
            field = value
            updateSectionAppearance()
        }
    
    // Tags for each section (order matches display order)
    companion object {
        const val SECTION_METRICS = "metrics"
        const val SECTION_INTELLIGENCE = "intelligence"
        const val SECTION_DOSE_CHART = "dose_chart"
        const val SECTION_COUNT_CHART = "count_chart"
        const val SECTION_ISOTOPE = "isotope"
        
        val DEFAULT_ORDER = listOf(
            SECTION_METRICS,
            SECTION_INTELLIGENCE,
            SECTION_DOSE_CHART,
            SECTION_COUNT_CHART,
            SECTION_ISOTOPE
        )
    }
    
    /**
     * Register a view as a draggable section.
     */
    fun registerSection(tag: String, view: View) {
        sectionViews[tag] = view
        view.tag = tag
        setupDragHandling(view)
    }
    
    /**
     * Apply a saved order to the container.
     */
    fun applyOrder(order: List<String>) {
        // Remove all section views from container
        val viewsToReorder = order.mapNotNull { sectionViews[it] }
        
        // Find the indices where these views currently are
        val indices = viewsToReorder.map { container.indexOfChild(it) }.filter { it >= 0 }
        if (indices.isEmpty()) return
        
        val startIndex = indices.minOrNull() ?: return
        
        // Remove views (in reverse order to preserve indices)
        viewsToReorder.reversed().forEach { view ->
            container.removeView(view)
        }
        
        // Add back in correct order
        viewsToReorder.forEachIndexed { index, view ->
            container.addView(view, startIndex + index)
        }
    }
    
    /**
     * Get current order of sections.
     */
    fun getCurrentOrder(): List<String> {
        val order = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val tag = child.tag as? String
            if (tag != null && sectionViews.containsKey(tag)) {
                order.add(tag)
            }
        }
        return order
    }
    
    /**
     * Reset to default order.
     */
    fun resetToDefault() {
        applyOrder(DEFAULT_ORDER)
        onOrderChanged(DEFAULT_ORDER)
    }
    
    private fun setupDragHandling(view: View) {
        // Long press starts drag
        view.setOnLongClickListener { v ->
            if (!isEditMode) return@setOnLongClickListener false
            
            // Create drag shadow
            val shadowBuilder = View.DragShadowBuilder(v)
            
            // Start drag
            val clipData = ClipData.newPlainText("section", v.tag as String)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                v.startDragAndDrop(clipData, shadowBuilder, v, 0)
            } else {
                @Suppress("DEPRECATION")
                v.startDrag(clipData, shadowBuilder, v, 0)
            }
            
            // Fade the original view
            v.alpha = 0.3f
            
            true
        }
        
        // Handle drops
        view.setOnDragListener { v, event ->
            if (!isEditMode) return@setOnDragListener false
            
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    // Highlight as drop target
                    if (v != event.localState) {
                        highlightDropTarget(v, true)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    highlightDropTarget(v, false)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    highlightDropTarget(v, false)
                    
                    val draggedView = event.localState as? View ?: return@setOnDragListener false
                    val targetView = v
                    
                    if (draggedView != targetView) {
                        swapViews(draggedView, targetView)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    // Restore original view
                    val draggedView = event.localState as? View
                    draggedView?.alpha = 1.0f
                    highlightDropTarget(v, false)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun highlightDropTarget(view: View, highlight: Boolean) {
        if (highlight) {
            view.setBackgroundColor(ContextCompat.getColor(view.context, R.color.pro_cyan) and 0x20FFFFFF)
        } else {
            // Restore original background based on section type
            val tag = view.tag as? String
            when (tag) {
                SECTION_METRICS -> view.setBackgroundColor(Color.TRANSPARENT)
                else -> view.setBackgroundResource(R.drawable.card_background)
            }
        }
    }
    
    private fun swapViews(view1: View, view2: View) {
        val index1 = container.indexOfChild(view1)
        val index2 = container.indexOfChild(view2)
        
        if (index1 < 0 || index2 < 0) return
        
        // Animate the swap
        val params1 = view1.layoutParams
        val params2 = view2.layoutParams
        
        container.removeView(view1)
        container.removeView(view2)
        
        if (index1 < index2) {
            container.addView(view2, index1, params1)
            container.addView(view1, index2, params2)
        } else {
            container.addView(view1, index2, params2)
            container.addView(view2, index1, params1)
        }
        
        // Notify of order change
        onOrderChanged(getCurrentOrder())
        
        // Animate views
        animateView(view1)
        animateView(view2)
    }
    
    private fun animateView(view: View) {
        view.alpha = 0.5f
        view.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    
    private fun updateSectionAppearance() {
        for ((_, view) in sectionViews) {
            // In edit mode, add visual indicator that views are draggable
            if (isEditMode) {
                // Add subtle border or overlay to indicate draggability
                view.elevation = 4f * view.resources.displayMetrics.density
            } else {
                view.elevation = 0f
            }
        }
    }
}
