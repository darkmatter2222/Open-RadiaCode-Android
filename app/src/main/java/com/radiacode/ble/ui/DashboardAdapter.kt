package com.radiacode.ble.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiacode.ble.DashboardCardType
import com.radiacode.ble.DashboardItem
import com.radiacode.ble.DashboardLayout
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import java.util.Collections

/**
 * RecyclerView adapter for the configurable dashboard.
 * Supports drag-and-drop reordering with ItemTouchHelper.
 */
class DashboardAdapter(
    private val context: Context,
    private val onLayoutChanged: (DashboardLayout) -> Unit,
    private val onCardClick: (DashboardCardType) -> Unit
) : RecyclerView.Adapter<DashboardAdapter.DashboardViewHolder>() {

    private val items = mutableListOf<DashboardItem>()
    private var isEditMode = false
    private var itemTouchHelper: ItemTouchHelper? = null
    
    // View caches - we hold references to views for updating
    private val viewHolderCache = mutableMapOf<String, DashboardViewHolder>()
    
    // Listeners for chart/card updates from MainActivity
    var onBindDoseCard: ((MetricCardView) -> Unit)? = null
    var onBindCpsCard: ((MetricCardView) -> Unit)? = null
    var onBindDoseChart: ((ProChartView, StatRowView?, TextView?, ImageButton?, ImageButton?) -> Unit)? = null
    var onBindCpsChart: ((ProChartView, StatRowView?, TextView?, ImageButton?, ImageButton?) -> Unit)? = null
    var onBindIntelligence: ((View) -> Unit)? = null
    var onBindIsotope: ((View) -> Unit)? = null
    
    fun setItems(layout: DashboardLayout) {
        items.clear()
        // Sort items by row then column for proper display order
        items.addAll(layout.items.sortedWith(compareBy({ it.row }, { it.column })))
        notifyDataSetChanged()
    }
    
    fun setEditMode(editMode: Boolean) {
        if (isEditMode != editMode) {
            isEditMode = editMode
            notifyDataSetChanged()
        }
    }
    
    fun isInEditMode(): Boolean = isEditMode
    
    fun attachToRecyclerView(recyclerView: RecyclerView) {
        val callback = DashboardItemTouchCallback()
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }
    
    fun getSpanSize(position: Int): Int {
        return if (position < items.size && items[position].spanFull) 2 else 1
    }
    
    override fun getItemCount(): Int = items.size
    
    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return DashboardCardType.fromId(item.id)?.ordinal ?: 0
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DashboardViewHolder {
        val cardType = DashboardCardType.values().getOrNull(viewType) ?: DashboardCardType.DOSE_CARD
        val inflater = LayoutInflater.from(context)
        
        val view = when (cardType) {
            DashboardCardType.DOSE_CARD, DashboardCardType.CPS_CARD -> {
                inflater.inflate(R.layout.item_dashboard_metric_card, parent, false)
            }
            DashboardCardType.INTELLIGENCE -> {
                inflater.inflate(R.layout.item_dashboard_intelligence, parent, false)
            }
            DashboardCardType.DOSE_CHART, DashboardCardType.CPS_CHART -> {
                inflater.inflate(R.layout.item_dashboard_chart, parent, false)
            }
            DashboardCardType.ISOTOPE -> {
                inflater.inflate(R.layout.item_dashboard_isotope, parent, false)
            }
        }
        
        return DashboardViewHolder(view, cardType)
    }
    
    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: DashboardViewHolder, position: Int) {
        val item = items[position]
        val cardType = DashboardCardType.fromId(item.id) ?: return
        
        // Cache the view holder for external updates
        viewHolderCache[item.id] = holder
        
        // Configure drag handle visibility and touch listener
        holder.dragHandle?.apply {
            visibility = if (isEditMode) View.VISIBLE else View.GONE
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && isEditMode) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }
        }
        
        // Show edit mode border
        holder.editBorder?.visibility = if (isEditMode) View.VISIBLE else View.GONE
        
        // Bind card-specific content
        when (cardType) {
            DashboardCardType.DOSE_CARD -> {
                holder.metricCard?.let { card ->
                    onBindDoseCard?.invoke(card)
                }
            }
            DashboardCardType.CPS_CARD -> {
                holder.metricCard?.let { card ->
                    onBindCpsCard?.invoke(card)
                }
            }
            DashboardCardType.DOSE_CHART, DashboardCardType.CPS_CHART -> {
                // Set compact mode - hide stats row when side-by-side
                val isCompact = !item.spanFull
                holder.statsRow?.visibility = if (isCompact) View.GONE else View.VISIBLE
                
                // Adjust chart height for compact mode
                holder.chartView?.layoutParams?.height = if (isCompact) {
                    dpToPx(140)
                } else {
                    dpToPx(160)
                }
                
                if (cardType == DashboardCardType.DOSE_CHART) {
                    holder.chartView?.let { chart ->
                        onBindDoseChart?.invoke(
                            chart, 
                            holder.statsRow, 
                            holder.chartTitle,
                            holder.chartGoRealtime,
                            holder.chartReset
                        )
                    }
                } else {
                    holder.chartView?.let { chart ->
                        onBindCpsChart?.invoke(
                            chart, 
                            holder.statsRow, 
                            holder.chartTitle,
                            holder.chartGoRealtime,
                            holder.chartReset
                        )
                    }
                }
            }
            DashboardCardType.INTELLIGENCE -> {
                holder.itemView.let { view ->
                    onBindIntelligence?.invoke(view)
                }
            }
            DashboardCardType.ISOTOPE -> {
                holder.itemView.let { view ->
                    onBindIsotope?.invoke(view)
                }
            }
        }
        
        // Click listener for non-edit mode
        if (!isEditMode) {
            holder.itemView.setOnClickListener {
                onCardClick(cardType)
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }
    }
    
    /**
     * Get a cached view holder by card type for external updates.
     */
    fun getViewHolder(cardType: DashboardCardType): DashboardViewHolder? {
        return viewHolderCache[cardType.id]
    }
    
    /**
     * Notify that a specific card's data needs refresh.
     */
    fun refreshCard(cardType: DashboardCardType) {
        val position = items.indexOfFirst { it.id == cardType.id }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
    
    private fun buildCurrentLayout(): DashboardLayout {
        return DashboardLayout(items = items.toList())
    }
    
    private fun recalculateLayout() {
        // Reassign row numbers based on current order
        var currentRow = 0
        var columnInRow = 0
        
        val newItems = mutableListOf<DashboardItem>()
        
        for (item in items) {
            val cardType = DashboardCardType.fromId(item.id)
            val mustBeFullWidth = cardType?.canBeSideBySide == false
            
            // Determine if this item should be full width
            val shouldBeFullWidth = mustBeFullWidth || item.spanFull
            
            if (shouldBeFullWidth) {
                // If we have a partial row, move to next row
                if (columnInRow > 0) {
                    currentRow++
                    columnInRow = 0
                }
                newItems.add(item.copy(row = currentRow, column = 0, spanFull = true))
                currentRow++
            } else {
                // Side-by-side item
                if (columnInRow >= 2) {
                    currentRow++
                    columnInRow = 0
                }
                newItems.add(item.copy(row = currentRow, column = columnInRow, spanFull = false))
                columnInRow++
                if (columnInRow >= 2) {
                    currentRow++
                    columnInRow = 0
                }
            }
        }
        
        items.clear()
        items.addAll(newItems)
    }
    
    // ==================== VIEW HOLDER ====================
    
    inner class DashboardViewHolder(
        itemView: View,
        val cardType: DashboardCardType
    ) : RecyclerView.ViewHolder(itemView) {
        
        // Common elements
        val dragHandle: ImageView? = itemView.findViewById(R.id.dragHandle)
        val editBorder: View? = itemView.findViewById(R.id.editBorder)
        
        // Metric card elements
        val metricCard: MetricCardView? = itemView.findViewById(R.id.metricCard)
        
        // Chart elements
        val chartView: ProChartView? = itemView.findViewById(R.id.chartView)
        val statsRow: StatRowView? = itemView.findViewById(R.id.statsRow)
        val chartTitle: TextView? = itemView.findViewById(R.id.chartTitle)
        val chartGoRealtime: ImageButton? = itemView.findViewById(R.id.chartGoRealtime)
        val chartReset: ImageButton? = itemView.findViewById(R.id.chartReset)
        
        // Intelligence elements
        val intelligenceCard: LinearLayout? = itemView.findViewById(R.id.intelligenceContent)
        
        // Isotope elements  
        val isotopePanel: LinearLayout? = itemView.findViewById(R.id.isotopeContent)
    }
    
    // ==================== ITEM TOUCH CALLBACK ====================
    
    inner class DashboardItemTouchCallback : ItemTouchHelper.Callback() {
        
        private var draggedPosition: Int = -1
        
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (!isEditMode) return 0
            
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or 
                           ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(dragFlags, 0)
        }
        
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPos = viewHolder.adapterPosition
            val toPos = target.adapterPosition
            
            if (fromPos < 0 || toPos < 0 || fromPos >= items.size || toPos >= items.size) {
                return false
            }
            
            // Swap items
            Collections.swap(items, fromPos, toPos)
            notifyItemMoved(fromPos, toPos)
            
            return true
        }
        
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // No swipe-to-delete
        }
        
        override fun isLongPressDragEnabled(): Boolean = false
        
        override fun isItemViewSwipeEnabled(): Boolean = false
        
        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_DRAG -> {
                    viewHolder?.let {
                        draggedPosition = it.adapterPosition
                        // Elevate the dragged item
                        it.itemView.elevation = 16f
                        it.itemView.alpha = 0.9f
                    }
                }
                ItemTouchHelper.ACTION_STATE_IDLE -> {
                    if (draggedPosition >= 0) {
                        draggedPosition = -1
                        // Recalculate layout and notify
                        recalculateLayout()
                        notifyDataSetChanged()
                        onLayoutChanged(buildCurrentLayout())
                    }
                }
            }
        }
        
        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Reset elevation and alpha
            viewHolder.itemView.elevation = 0f
            viewHolder.itemView.alpha = 1f
        }
        
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            
            // Add visual feedback during drag
            if (isCurrentlyActive && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                // Draw drop zone indicators
                drawDropZones(c, recyclerView, viewHolder)
            }
        }
        
        private fun drawDropZones(c: Canvas, recyclerView: RecyclerView, draggedHolder: RecyclerView.ViewHolder) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * context.resources.displayMetrics.density
                color = ContextCompat.getColor(context, R.color.pro_cyan)
                alpha = 100
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            }
            
            // Draw indicator around other items
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                val holder = recyclerView.getChildViewHolder(child)
                if (holder != draggedHolder) {
                    val rect = RectF(
                        child.left.toFloat() + 4f,
                        child.top.toFloat() + 4f,
                        child.right.toFloat() - 4f,
                        child.bottom.toFloat() - 4f
                    )
                    c.drawRoundRect(rect, 16f, 16f, paint)
                }
            }
        }
    }
}

/**
 * Custom GridLayoutManager.SpanSizeLookup for dashboard.
 */
class DashboardSpanSizeLookup(
    private val adapter: DashboardAdapter
) : GridLayoutManager.SpanSizeLookup() {
    override fun getSpanSize(position: Int): Int {
        return adapter.getSpanSize(position)
    }
}
