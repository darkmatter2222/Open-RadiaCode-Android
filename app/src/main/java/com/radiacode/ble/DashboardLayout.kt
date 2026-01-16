package com.radiacode.ble

/**
 * Represents the configurable dashboard layout.
 * Users can drag and rearrange dashboard cards.
 */
data class DashboardLayout(
    val items: List<DashboardItem>,
    val version: Int = 1
) {
    fun toJson(): String {
        val itemsJson = items.joinToString(",") { it.toJson() }
        return """{"items":[$itemsJson],"version":$version}"""
    }

    companion object {
        fun fromJson(json: String): DashboardLayout? {
            return try {
                val version = json.substringAfter("\"version\":").substringBefore("}").toIntOrNull() ?: 1
                val itemsStr = json.substringAfter("\"items\":[").substringBefore("],\"version\"")
                
                val items = mutableListOf<DashboardItem>()
                if (itemsStr.isNotEmpty() && itemsStr != "]") {
                    // Parse each item object
                    var remaining = itemsStr
                    while (remaining.contains("{")) {
                        val start = remaining.indexOf("{")
                        var depth = 0
                        var end = start
                        for (i in start until remaining.length) {
                            when (remaining[i]) {
                                '{' -> depth++
                                '}' -> {
                                    depth--
                                    if (depth == 0) {
                                        end = i
                                        break
                                    }
                                }
                            }
                        }
                        val itemJson = remaining.substring(start, end + 1)
                        DashboardItem.fromJson(itemJson)?.let { items.add(it) }
                        remaining = if (end + 1 < remaining.length) remaining.substring(end + 1) else ""
                    }
                }
                
                DashboardLayout(items = items, version = version)
            } catch (e: Exception) {
                android.util.Log.e("DashboardLayout", "Failed to parse: $json", e)
                null
            }
        }

        /**
         * Creates the default dashboard layout.
         * Row 0: Dose Card + CPS Card (side-by-side)
         * Row 1: Intelligence Report (full width)
         * Row 2: Real Time Dose Rate (full width)
         * Row 3: Real Time Count Rate (full width)
         * Row 4: Isotope Identification (full width)
         */
        fun createDefault(): DashboardLayout {
            return DashboardLayout(
                items = listOf(
                    DashboardItem(
                        id = DashboardCardType.DOSE_CARD.id,
                        row = 0,
                        column = 0,
                        spanFull = false,
                        compactMode = false
                    ),
                    DashboardItem(
                        id = DashboardCardType.CPS_CARD.id,
                        row = 0,
                        column = 1,
                        spanFull = false,
                        compactMode = false
                    ),
                    DashboardItem(
                        id = DashboardCardType.INTELLIGENCE.id,
                        row = 1,
                        column = 0,
                        spanFull = true,
                        compactMode = false
                    ),
                    DashboardItem(
                        id = DashboardCardType.DOSE_CHART.id,
                        row = 2,
                        column = 0,
                        spanFull = true,
                        compactMode = false
                    ),
                    DashboardItem(
                        id = DashboardCardType.CPS_CHART.id,
                        row = 3,
                        column = 0,
                        spanFull = true,
                        compactMode = false
                    ),
                    DashboardItem(
                        id = DashboardCardType.ISOTOPE.id,
                        row = 4,
                        column = 0,
                        spanFull = true,
                        compactMode = false
                    )
                ),
                version = 1
            )
        }
    }
}

/**
 * Represents a single dashboard item/card position.
 */
data class DashboardItem(
    val id: String,           // Card type ID from DashboardCardType
    val row: Int,             // Row index (0-based)
    val column: Int,          // 0 = left/full, 1 = right (max 2 columns)
    val spanFull: Boolean,    // true = takes full width
    val compactMode: Boolean  // true = hide StatRowView for charts
) {
    fun toJson(): String {
        return """{"id":"$id","row":$row,"column":$column,"spanFull":$spanFull,"compactMode":$compactMode}"""
    }

    companion object {
        fun fromJson(json: String): DashboardItem? {
            return try {
                val id = json.substringAfter("\"id\":\"").substringBefore("\"")
                val row = json.substringAfter("\"row\":").substringBefore(",").toInt()
                val column = json.substringAfter("\"column\":").substringBefore(",").toInt()
                val spanFull = json.substringAfter("\"spanFull\":").substringBefore(",").toBoolean()
                val compactMode = json.substringAfter("\"compactMode\":").substringBefore("}").toBoolean()
                
                DashboardItem(
                    id = id,
                    row = row,
                    column = column,
                    spanFull = spanFull,
                    compactMode = compactMode
                )
            } catch (e: Exception) {
                android.util.Log.e("DashboardItem", "Failed to parse: $json", e)
                null
            }
        }
    }
}

/**
 * Enumeration of all draggable dashboard card types.
 */
enum class DashboardCardType(
    val id: String,
    val displayName: String,
    val canBeCompact: Boolean,  // Can hide extra content when side-by-side
    val canBeSideBySide: Boolean, // Can be placed next to another card
    val defaultHeightDp: Int    // Default height in dp (-1 for wrap_content)
) {
    DOSE_CARD(
        id = "dose_card",
        displayName = "Dose Rate",
        canBeCompact = false,
        canBeSideBySide = true,
        defaultHeightDp = 160
    ),
    CPS_CARD(
        id = "cps_card",
        displayName = "Count Rate",
        canBeCompact = false,
        canBeSideBySide = true,
        defaultHeightDp = 160
    ),
    INTELLIGENCE(
        id = "intelligence",
        displayName = "Intelligence Report",
        canBeCompact = false,
        canBeSideBySide = true,
        defaultHeightDp = -1  // wrap_content
    ),
    DOSE_CHART(
        id = "dose_chart",
        displayName = "Real Time Dose Rate",
        canBeCompact = true,  // Can hide StatRowView when compact
        canBeSideBySide = true,
        defaultHeightDp = 232  // Chart 160dp + Stats 52dp + margins
    ),
    CPS_CHART(
        id = "cps_chart",
        displayName = "Real Time Count Rate",
        canBeCompact = true,  // Can hide StatRowView when compact
        canBeSideBySide = true,
        defaultHeightDp = 232
    ),
    ISOTOPE(
        id = "isotope",
        displayName = "Isotope Identification",
        canBeCompact = false,
        canBeSideBySide = false,  // Always full width
        defaultHeightDp = -1  // wrap_content
    );

    companion object {
        fun fromId(id: String): DashboardCardType? {
            return values().find { it.id == id }
        }
    }
}
