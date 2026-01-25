# Widget System V2 - Complete Redesign

> **Status:** ✅ Implemented  
> **Created:** January 14, 2026  
> **Version:** 0.12  
> **Priority:** Critical - Complete overhaul

---

## Problem Statement

The current widget configuration system has critical issues:

1. **Preview ≠ Widget**: The preview shows one thing, the widget renders differently (horizontal vs stacked layout, color mismatches, extra UI elements appearing)
2. **Confusing Templates**: "Dual Sparkline", "Compact Text", "Chart Focus", "Data Dense" etc. are unclear and don't map to user expectations
3. **Bad Real Estate Usage**: Widgets waste space, especially "Compact Text" which shows just one number
4. **Inconsistent UI**: Device color bars, dots, and confusing status indicators appear unexpectedly
5. **Not User Friendly**: Too many cryptic options (Bollinger Bands, Dynamic Color by Value) without clear purpose

---

## Design Principles

1. **PREVIEW = WIDGET**: The preview must be a pixel-perfect representation. Use the EXACT same rendering code for both.
2. **Simple Configuration**: Progressive disclosure - basic options first, advanced options expand
3. **Perfect Real Estate**: Every pixel matters. Beautiful, dense, useful information display
4. **Stunning Aesthetics**: Professional dark theme, smooth animations, delightful interactions
5. **10X Simplicity**: Reduce cognitive load. Make it obvious what each option does.

---

## New Configuration UI

### Primary Controls (Always Visible)

```
┌─────────────────────────────────────────────────────────────┐
│  LIVE PREVIEW                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │   [Exactly what widget will look like]                │  │
│  │   [Updates in real-time as you toggle options]        │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  📊 DATA SOURCES                                            │
│                                                             │
│  Device: [▼ RadiaCode-101 ▼]                               │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  DOSE RATE                               [● ON/OFF] │   │
│  │  ├─ Show Value                           [● ON/OFF] │   │
│  │  └─ Show Chart ─────────────── [▼ Sparkline ▼]     │   │
│  │                    (None | Sparkline | Line | Bar)  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  COUNT RATE                              [● ON/OFF] │   │
│  │  ├─ Show Value                           [● ON/OFF] │   │
│  │  └─ Show Chart ─────────────── [▼ None ▼]          │   │
│  │                    (None | Sparkline | Line | Bar)  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  Show Timestamp                             [● ON/OFF]      │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  🎨 APPEARANCE                                              │
│                                                             │
│  Color Theme: [Cyan/Magenta ▼] [Ocean ▼] [Forest ▼] ...   │
│                                                             │
│  Chart Time Window: [30s] [1m] [5m] [15m]                  │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  ⚙️ ADVANCED (tap to expand)                                │
│  └─ Show Bollinger Bands                    [○ OFF]        │
│  └─ Dynamic Color by Value                  [○ OFF]        │
│  └─ Show Anomaly Badge                      [○ OFF]        │
│  └─ Show Trend Indicators                   [○ OFF]        │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│      [   CANCEL   ]          [  CREATE WIDGET  ]           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Widget Layout System

### Unified Widget Layout

Instead of multiple widget providers with different layouts, we have ONE unified layout that adapts based on configuration:

```xml
<!-- widget_unified.xml - The ONLY widget layout -->
<LinearLayout orientation="vertical">
    
    <!-- Header (always present, compact) -->
    <LinearLayout orientation="horizontal">
        <View id="statusIndicator" width="8dp" height="8dp" />
        <TextView id="timestamp" />
    </LinearLayout>
    
    <!-- Content Area (dynamic based on config) -->
    <LinearLayout id="contentArea" orientation="horizontal">
        
        <!-- Dose Section (visible if showDose) -->
        <LinearLayout id="doseSection" orientation="vertical">
            <TextView id="doseValue" />
            <TextView id="doseUnit" />
            <ImageView id="doseChart" />  <!-- visible if doseChartType != NONE -->
        </LinearLayout>
        
        <!-- Count Section (visible if showCount) -->
        <LinearLayout id="countSection" orientation="vertical">
            <TextView id="countValue" />
            <TextView id="countUnit" />
            <ImageView id="countChart" />  <!-- visible if countChartType != NONE -->
        </LinearLayout>
        
    </LinearLayout>
    
</LinearLayout>
```

---

## Configuration Data Model

```kotlin
data class WidgetConfigV2(
    val widgetId: Int,
    
    // Device Binding
    val deviceId: String?,  // null = default/aggregate
    
    // Dose Rate Config
    val showDose: Boolean = true,
    val showDoseValue: Boolean = true,
    val doseChartType: ChartDisplayType = ChartDisplayType.SPARKLINE,
    
    // Count Rate Config  
    val showCount: Boolean = true,
    val showCountValue: Boolean = true,
    val countChartType: ChartDisplayType = ChartDisplayType.NONE,
    
    // Display Options
    val showTimestamp: Boolean = true,
    val colorTheme: ColorTheme = ColorTheme.CYAN_MAGENTA,
    val chartTimeWindowSeconds: Int = 60,
    
    // Advanced (collapsed by default)
    val showBollingerBands: Boolean = false,
    val dynamicColorEnabled: Boolean = false,
    val showAnomalyBadge: Boolean = false,
    val showTrendIndicators: Boolean = false
)

enum class ChartDisplayType {
    NONE,       // No chart, value only
    SPARKLINE,  // Minimal trend line
    LINE,       // Line chart with subtle grid
    BAR         // Bar chart
}

enum class ColorTheme(
    val displayName: String,
    val doseColor: Int,     // Primary metric color
    val countColor: Int,    // Secondary metric color
    val backgroundColor: Int,
    val textColor: Int
) {
    CYAN_MAGENTA("Default", 0xFF00E5FF, 0xFFE040FB, 0xFF1A1A1E, 0xFFFFFFFF),
    OCEAN("Ocean", 0xFF40C4FF, 0xFF4FC3F7, 0xFF0D1B2A, 0xFFFFFFFF),
    FOREST("Forest", 0xFF69F0AE, 0xFF81C784, 0xFF1B2E1B, 0xFFFFFFFF),
    FIRE("Fire", 0xFFFF5722, 0xFFFFAB91, 0xFF2A1B0D, 0xFFFFFFFF),
    AMBER("Amber", 0xFFFFD740, 0xFFFFE082, 0xFF2A2100, 0xFFFFFFFF),
    PURPLE("Purple", 0xFFB388FF, 0xFFCE93D8, 0xFF1A0D2A, 0xFFFFFFFF),
    GRAYSCALE("Grayscale", 0xFFBDBDBD, 0xFF9E9E9E, 0xFF1A1A1A, 0xFFFFFFFF)
}
```

---

## Critical Implementation: Unified Renderer

The KEY to preview matching widget is using THE SAME RENDERING CODE for both.

```kotlin
object WidgetRenderer {
    /**
     * Renders widget content to either:
     * - A View (for preview in config activity)
     * - RemoteViews (for actual home screen widget)
     * 
     * CRITICAL: Same logic, same calculations, same layouts.
     */
    
    fun renderToRemoteViews(context: Context, config: WidgetConfigV2, widgetSize: Size): RemoteViews {
        // Use identical logic as renderToPreview
    }
    
    fun renderToPreview(context: Context, config: WidgetConfigV2, previewContainer: ViewGroup) {
        // Use identical logic as renderToRemoteViews
    }
    
    // Shared chart bitmap generation
    fun generateChartBitmap(
        type: ChartDisplayType,
        values: List<Float>,
        color: Int,
        bgColor: Int,
        width: Int,
        height: Int,
        showBollinger: Boolean
    ): Bitmap {
        // Single implementation used by both preview and widget
    }
}
```

---

## Layout Configurations by State

### Dose Only, No Chart
```
┌─────────────────────────────┐
│ ● 12:34:56                  │
│  0.057 μSv/h                │
└─────────────────────────────┘
```

### Dose Only, With Sparkline
```
┌─────────────────────────────┐
│ ● 12:34:56                  │
│  0.057 μSv/h  [~~~~~]       │
└─────────────────────────────┘
```

### Both Metrics, No Charts
```
┌─────────────────────────────┐
│ ● 12:34:56                  │
│  0.057 μSv/h │ 8.2 cps      │
└─────────────────────────────┘
```

### Both Metrics, Both Sparklines (Default)
```
┌─────────────────────────────┐
│ ● 12:34:56                  │
│  0.057 μSv/h  [~~~~~]       │
│  8.2 cps      [~~~~~]       │
└─────────────────────────────┘
```

### Both Metrics, Dose Chart Only
```
┌─────────────────────────────┐
│ ● 12:34:56                  │
│  0.057 μSv/h  [~~~~~]       │
│  8.2 cps                    │
└─────────────────────────────┘
```

---

## Files to Create/Replace

### Delete (Old System)
- `LayoutTemplate` enum - REMOVE
- Old template chip logic - REMOVE
- Three separate widget providers complexity - SIMPLIFY

### Create (New System)
1. `WidgetConfigV2.kt` - New data model
2. `WidgetRenderer.kt` - Unified rendering engine
3. `widget_unified.xml` - Single adaptive layout
4. `activity_widget_config_v2.xml` - New configuration UI
5. Update `WidgetConfigActivity.kt` - Simplified logic

---

## Migration Strategy

1. Keep old widget providers functional during transition
2. New widgets use V2 system
3. Eventually deprecate old providers

---

## Success Criteria

- [ ] Preview is 100% identical to widget output
- [ ] Configuration is intuitive for casual users
- [ ] All widget real estate is well utilized
- [ ] No mysterious UI elements (random dots, bars, etc.)
- [ ] Beautiful, professional appearance
- [ ] Works reliably across all Android launchers
