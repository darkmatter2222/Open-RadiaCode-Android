package com.radiacode.ble

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.Collections

/**
 * Activity for customizing dashboard card layout.
 * Users can drag to reorder cards and toggle side-by-side mode.
 */
class DashboardConfigActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DashboardConfigAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    
    private var hasChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard_config)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Customize Dashboard"
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Load current layout
        val layout = Prefs.getDashboardLayout(this)
        adapter = DashboardConfigAdapter(layout.items.toMutableList()) {
            hasChanges = true
        }
        recyclerView.adapter = adapter
        
        // Setup drag & drop
        val callback = DashboardConfigTouchCallback(adapter) { itemTouchHelper }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
        
        adapter.setItemTouchHelper(itemTouchHelper)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dashboard_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> {
                showResetConfirmation()
                true
            }
            R.id.action_save -> {
                saveAndFinish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset Layout")
            .setMessage("Reset dashboard to default layout? This cannot be undone.")
            .setPositiveButton("Reset") { _, _ ->
                Prefs.resetDashboardLayout(this)
                val defaultLayout = DashboardLayout.createDefault()
                adapter.updateItems(defaultLayout.items)
                hasChanges = true
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveAndFinish() {
        // Build new layout from adapter order
        val newItems = adapter.getItems().mapIndexed { index, item ->
            // Recalculate positions based on order
            item.copy(row = index, column = 0, spanFull = true)
        }
        
        // Reflow layout for side-by-side items
        val reflowedItems = reflowLayout(newItems)
        
        Prefs.setDashboardLayout(this, DashboardLayout(items = reflowedItems))
        setResult(RESULT_OK)
        finish()
    }
    
    private fun reflowLayout(items: List<DashboardItem>): List<DashboardItem> {
        val result = mutableListOf<DashboardItem>()
        var currentRow = 0
        var columnInRow = 0
        
        for (item in items) {
            val cardType = DashboardCardType.fromId(item.id)
            val canBeSideBySide = cardType?.canBeSideBySide ?: false
            
            // Check if this item should be side-by-side with the previous one
            val previousItem = result.lastOrNull()
            val prevCardType = previousItem?.let { DashboardCardType.fromId(it.id) }
            val prevCanBeSideBySide = prevCardType?.canBeSideBySide ?: false
            
            // Special case: DOSE_CARD and CPS_CARD should be side-by-side
            val shouldPairWithPrevious = when {
                item.id == DashboardCardType.CPS_CARD.id && 
                    previousItem?.id == DashboardCardType.DOSE_CARD.id -> true
                item.id == DashboardCardType.DOSE_CARD.id && 
                    previousItem?.id == DashboardCardType.CPS_CARD.id -> true
                else -> false
            }
            
            if (shouldPairWithPrevious && columnInRow == 1) {
                // Stack with previous item
                result.add(item.copy(
                    row = currentRow - 1, // Same row as previous
                    column = 1,
                    spanFull = false,
                    compactMode = cardType?.canBeCompact ?: false
                ))
                // Update previous item to not span full
                val prevIndex = result.size - 2
                if (prevIndex >= 0) {
                    val prevItem = result[prevIndex]
                    result[prevIndex] = prevItem.copy(spanFull = false)
                }
            } else {
                // New row
                if (columnInRow > 0) {
                    columnInRow = 0
                }
                result.add(item.copy(
                    row = currentRow,
                    column = 0,
                    spanFull = true,
                    compactMode = false
                ))
                currentRow++
                columnInRow = 1
            }
        }
        
        return result
    }

    override fun onBackPressed() {
        if (hasChanges) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Save before leaving?")
                .setPositiveButton("Save") { _, _ -> saveAndFinish() }
                .setNegativeButton("Discard") { _, _ -> super.onBackPressed() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}

/**
 * Adapter for dashboard configuration list.
 */
class DashboardConfigAdapter(
    private val items: MutableList<DashboardItem>,
    private val onItemsChanged: () -> Unit
) : RecyclerView.Adapter<DashboardConfigAdapter.ViewHolder>() {

    private var itemTouchHelper: ItemTouchHelper? = null
    
    fun setItemTouchHelper(helper: ItemTouchHelper) {
        this.itemTouchHelper = helper
    }

    fun getItems(): List<DashboardItem> = items.toList()
    
    fun updateItems(newItems: List<DashboardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun moveItem(fromPos: Int, toPos: Int) {
        if (fromPos < toPos) {
            for (i in fromPos until toPos) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPos downTo toPos + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPos, toPos)
        onItemsChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val cardType = DashboardCardType.fromId(item.id)
        
        holder.cardName.text = cardType?.displayName ?: item.id
        holder.cardDescription.text = when (cardType) {
            DashboardCardType.DOSE_CARD -> "Current dose rate metric card"
            DashboardCardType.CPS_CARD -> "Current count rate metric card"
            DashboardCardType.INTELLIGENCE -> "AI analysis and predictions"
            DashboardCardType.DOSE_CHART -> "Real-time dose rate chart with statistics"
            DashboardCardType.CPS_CHART -> "Real-time count rate chart with statistics"
            DashboardCardType.ISOTOPE -> "Isotope identification panel"
            null -> "Unknown card"
        }
        
        // Set accent color based on card type
        val accentColor = when (cardType) {
            DashboardCardType.DOSE_CARD, DashboardCardType.DOSE_CHART -> 
                ContextCompat.getColor(holder.itemView.context, R.color.pro_cyan)
            DashboardCardType.CPS_CARD, DashboardCardType.CPS_CHART -> 
                ContextCompat.getColor(holder.itemView.context, R.color.pro_magenta)
            DashboardCardType.INTELLIGENCE -> 
                ContextCompat.getColor(holder.itemView.context, R.color.pro_amber)
            DashboardCardType.ISOTOPE -> 
                ContextCompat.getColor(holder.itemView.context, R.color.pro_green)
            null -> ContextCompat.getColor(holder.itemView.context, R.color.pro_text_muted)
        }
        holder.accentBar.setBackgroundColor(accentColor)
        
        // Drag handle
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardName: TextView = itemView.findViewById(R.id.cardName)
        val cardDescription: TextView = itemView.findViewById(R.id.cardDescription)
        val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        val accentBar: View = itemView.findViewById(R.id.accentBar)
    }
}

/**
 * ItemTouchHelper callback for reordering dashboard cards.
 */
class DashboardConfigTouchCallback(
    private val adapter: DashboardConfigAdapter,
    private val getItemTouchHelper: () -> ItemTouchHelper
) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // No swipe action
    }

    override fun isLongPressDragEnabled(): Boolean = false
    
    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.apply {
                elevation = 8f
                alpha = 0.9f
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.apply {
            elevation = 0f
            alpha = 1f
        }
    }
}
