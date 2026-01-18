package com.radiacode.ble.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a panel's position and size in the dashboard grid.
 * 
 * Grid is 2 columns wide, unlimited rows.
 * Each cell is ~80dp tall (configurable).
 */
data class DashboardGridItem(
    val panelType: PanelType,
    var column: Int,        // 0 or 1 (left or right)
    var row: Int,           // Starting row (0-indexed)
    var colSpan: Int,       // 1 or 2 (width in columns)
    var rowSpan: Int        // 1, 2, 3, ... (height in rows)
) {
    /** Check if this item occupies a specific cell */
    fun occupiesCell(col: Int, r: Int): Boolean {
        return col >= column && col < column + colSpan &&
               r >= row && r < row + rowSpan
    }
    
    /** Check if this item overlaps with another */
    fun overlapsWith(other: DashboardGridItem): Boolean {
        // No overlap if completely to the left, right, above, or below
        if (column + colSpan <= other.column) return false
        if (other.column + other.colSpan <= column) return false
        if (row + rowSpan <= other.row) return false
        if (other.row + other.rowSpan <= row) return false
        return true
    }
    
    /** Get the ending row (exclusive) */
    val endRow: Int get() = row + rowSpan
    
    /** Get the ending column (exclusive) */
    val endColumn: Int get() = column + colSpan
    
    /** Check if this panel is half-width (side-by-side capable) */
    val isHalfWidth: Boolean get() = colSpan == 1
    
    /** Check if this panel is full-width */
    val isFullWidth: Boolean get() = colSpan == 2
    
    /** Serialize to JSON */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("panelType", panelType.name)
            put("column", column)
            put("row", row)
            put("colSpan", colSpan)
            put("rowSpan", rowSpan)
        }
    }
    
    companion object {
        /** Deserialize from JSON */
        fun fromJson(json: JSONObject): DashboardGridItem {
            return DashboardGridItem(
                panelType = PanelType.valueOf(json.getString("panelType")),
                column = json.getInt("column"),
                row = json.getInt("row"),
                colSpan = json.getInt("colSpan"),
                rowSpan = json.getInt("rowSpan")
            )
        }
    }
}

/**
 * Types of panels that can be placed on the dashboard.
 * Each has minimum and maximum size constraints.
 */
enum class PanelType(
    val displayName: String,
    val minColSpan: Int,
    val maxColSpan: Int,
    val minRowSpan: Int,
    val maxRowSpan: Int
) {
    DELTA_DOSE("Delta Dose Rate", 1, 2, 1, 3),
    DELTA_COUNT("Delta Count Rate", 1, 2, 1, 3),
    INTELLIGENCE("Intelligence Report", 1, 2, 1, 3),
    DOSE_CHART("Real Time Dose Rate", 1, 2, 2, 4),
    COUNT_CHART("Real Time Count Rate", 1, 2, 2, 4),
    ISOTOPE_ID("Isotope Identification", 1, 2, 2, 4);
    
    /** Check if a given size is valid for this panel type */
    fun isValidSize(colSpan: Int, rowSpan: Int): Boolean {
        return colSpan in minColSpan..maxColSpan && 
               rowSpan in minRowSpan..maxRowSpan
    }
}

/**
 * The complete dashboard layout configuration.
 * Contains all panel positions and provides layout manipulation methods.
 */
class DashboardLayout(
    private val items: MutableList<DashboardGridItem> = mutableListOf()
) {
    companion object {
        const val GRID_COLUMNS = 2
        const val CELL_HEIGHT_DP = 80
        
        /** Create the default dashboard layout */
        fun createDefault(): DashboardLayout {
            return DashboardLayout(mutableListOf(
                DashboardGridItem(PanelType.DELTA_DOSE, column = 0, row = 0, colSpan = 1, rowSpan = 2),
                DashboardGridItem(PanelType.DELTA_COUNT, column = 1, row = 0, colSpan = 1, rowSpan = 2),
                DashboardGridItem(PanelType.INTELLIGENCE, column = 0, row = 2, colSpan = 2, rowSpan = 2),
                DashboardGridItem(PanelType.DOSE_CHART, column = 0, row = 4, colSpan = 2, rowSpan = 3),
                DashboardGridItem(PanelType.COUNT_CHART, column = 0, row = 7, colSpan = 2, rowSpan = 3),
                DashboardGridItem(PanelType.ISOTOPE_ID, column = 0, row = 10, colSpan = 2, rowSpan = 3)
            ))
        }
        
        /** Deserialize from JSON string */
        fun fromJson(jsonString: String): DashboardLayout {
            val jsonArray = JSONArray(jsonString)
            val items = mutableListOf<DashboardGridItem>()
            for (i in 0 until jsonArray.length()) {
                items.add(DashboardGridItem.fromJson(jsonArray.getJSONObject(i)))
            }
            return DashboardLayout(items)
        }
    }
    
    /** Get all items (read-only) */
    fun getItems(): List<DashboardGridItem> = items.toList()
    
    /** Get item by panel type */
    fun getItem(panelType: PanelType): DashboardGridItem? {
        return items.find { it.panelType == panelType }
    }
    
    /** Calculate the total number of rows needed */
    fun getTotalRows(): Int {
        return items.maxOfOrNull { it.endRow } ?: 0
    }
    
    /** Find the item at a specific grid cell */
    fun getItemAt(column: Int, row: Int): DashboardGridItem? {
        return items.find { it.occupiesCell(column, row) }
    }
    
    /** Find adjacent panel sharing the same row (for side-by-side sync) */
    fun getAdjacentInRow(item: DashboardGridItem): DashboardGridItem? {
        if (item.colSpan != 1) return null // Only half-width panels can have adjacents
        
        return items.find { other ->
            other != item &&
            other.colSpan == 1 &&
            other.row == item.row &&
            other.column != item.column
        }
    }
    
    /** Move an item to a new position */
    fun moveItem(item: DashboardGridItem, newColumn: Int, newRow: Int): Boolean {
        val oldColumn = item.column
        val oldRow = item.row
        
        // Temporarily update position
        item.column = newColumn
        item.row = newRow
        
        // Check for collisions with other items
        val hasCollision = items.any { other -> 
            other != item && item.overlapsWith(other) 
        }
        
        // Check bounds
        val outOfBounds = newColumn < 0 || 
                          newColumn + item.colSpan > GRID_COLUMNS ||
                          newRow < 0
        
        if (hasCollision || outOfBounds) {
            // Revert
            item.column = oldColumn
            item.row = oldRow
            return false
        }
        
        return true
    }
    
    /** Resize an item, optionally syncing adjacent panel */
    fun resizeItem(item: DashboardGridItem, newColSpan: Int, newRowSpan: Int, syncAdjacent: Boolean = true): Boolean {
        // Validate against panel type constraints
        if (!item.panelType.isValidSize(newColSpan, newRowSpan)) {
            return false
        }
        
        // Check if new size would go out of bounds
        if (item.column + newColSpan > GRID_COLUMNS) {
            return false
        }
        
        val oldColSpan = item.colSpan
        val oldRowSpan = item.rowSpan
        
        // Temporarily update size
        item.colSpan = newColSpan
        item.rowSpan = newRowSpan
        
        // Check for collisions
        val hasCollision = items.any { other ->
            other != item && item.overlapsWith(other)
        }
        
        if (hasCollision) {
            // Revert
            item.colSpan = oldColSpan
            item.rowSpan = oldRowSpan
            return false
        }
        
        // Sync adjacent panel's rowSpan if side-by-side
        if (syncAdjacent && item.colSpan == 1) {
            val adjacent = getAdjacentInRow(item)
            if (adjacent != null && adjacent.panelType.isValidSize(adjacent.colSpan, newRowSpan)) {
                adjacent.rowSpan = newRowSpan
            }
        }
        
        return true
    }
    
    /** Swap positions of two items */
    fun swapItems(item1: DashboardGridItem, item2: DashboardGridItem) {
        val tempCol = item1.column
        val tempRow = item1.row
        val tempColSpan = item1.colSpan
        val tempRowSpan = item1.rowSpan
        
        item1.column = item2.column
        item1.row = item2.row
        item1.colSpan = item2.colSpan
        item1.rowSpan = item2.rowSpan
        
        item2.column = tempCol
        item2.row = tempRow
        item2.colSpan = tempColSpan
        item2.rowSpan = tempRowSpan
    }
    
    /** Compact the layout by removing empty rows */
    fun compact() {
        // Sort by row
        val sorted = items.sortedBy { it.row }
        
        var currentRow = 0
        var lastEndRow = 0
        
        for (item in sorted) {
            if (item.row > lastEndRow) {
                // There's a gap, shift this item up
                val shift = item.row - lastEndRow
                item.row -= shift
            }
            lastEndRow = maxOf(lastEndRow, item.endRow)
        }
    }
    
    /** Serialize to JSON string */
    fun toJson(): String {
        val jsonArray = JSONArray()
        items.forEach { jsonArray.put(it.toJson()) }
        return jsonArray.toString()
    }
    
    /** Check if layout equals default */
    fun isDefault(): Boolean {
        val default = createDefault()
        if (items.size != default.items.size) return false
        
        return items.all { item ->
            val defaultItem = default.getItem(item.panelType)
            defaultItem != null &&
            item.column == defaultItem.column &&
            item.row == defaultItem.row &&
            item.colSpan == defaultItem.colSpan &&
            item.rowSpan == defaultItem.rowSpan
        }
    }
    
    /** Create a deep copy */
    fun copy(): DashboardLayout {
        return DashboardLayout(items.map { 
            it.copy() 
        }.toMutableList())
    }
}
