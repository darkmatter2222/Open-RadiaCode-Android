# Open RadiaCode — UI/UX Specification

> **Canonical reference.** Implementation must match this spec. Update spec or fix code if they diverge.

## Vision

A **professional-grade radiation monitoring dashboard** built for data scientists and serious users. The UI should rival commercial analytics dashboards — think Bloomberg Terminal meets modern data visualization.

## Design Philosophy

1. **Data-dense**: Show maximum information without clutter
2. **Glanceable hierarchy**: Critical metrics huge, supporting data smaller but present
3. **Dark-first**: Deep blacks (#0D0D0F), muted surfaces (#1A1A1E), vibrant accent data
4. **Gradient charts**: Line charts use gradient fills, not flat colors
5. **Always show context**: Axes, grid lines, legends — users need reference points
6. **Future-ready**: Architecture supports ML predictions, anomaly detection, geolocation overlays

## Color Palette

```
Background:       #0D0D0F (near-black)
Surface:          #1A1A1E (card backgrounds)
Surface Elevated: #242428 (hover/selected states)
Border:           #2E2E33 (subtle card borders)

Primary Accent:   #00E5FF (cyan) — dose rate
Secondary Accent: #E040FB (magenta) — count rate  
Tertiary Accent:  #69F0AE (green) — positive trends
Warning:          #FFD740 (amber) — thresholds
Error:            #FF5252 (red) — alerts/high readings

Text Primary:     #FFFFFF
Text Secondary:   #B0B0B8
Text Muted:       #6E6E78
```

## Typography

```
Hero Metric:      48sp, bold, monospace (tabular figures)
Large Metric:     32sp, bold, monospace
Medium Metric:    20sp, medium
Label:            12sp, medium, uppercase tracking
Body:             14sp, regular
Caption:          11sp, regular, muted
```

## Component Library

### 1. ProChartView

Professional line chart with:
- **Gradient fill** under line (accent color → transparent)
- **Y-axis** with auto-scaled labels (left side)
- **X-axis** with time labels (bottom)
- **Grid lines** (subtle horizontal lines at Y intervals)
- **Threshold line** (dashed, warning color)
- **Peak marker** (dot at highest point)
- **Crosshair** on long-press with tooltip

Visual specs:
- Line stroke: 2.5dp
- Gradient: 40% opacity at line → 0% at bottom
- Grid lines: 8% opacity
- Axis text: 10sp, muted color
- Chart area padding: 48dp left (Y-axis), 24dp bottom (X-axis), 16dp top/right

### 2. MetricCardView

Large stat display with trend indicator and mini sparkline:
```
┌─────────────────────────────┐
│ DOSE RATE            ▲ +2% │  ← Label + trend
│ 0.057                      │  ← Hero value (48sp)
│ μSv/h                      │  ← Unit
│ ───────────────────        │  ← Mini sparkline (32dp tall)
└─────────────────────────────┘
```

### 3. StatRowView

Horizontal metrics strip showing min/avg/max/delta:
```
┌─────────┬─────────┬─────────┬─────────┐
│ MIN     │ AVG     │ MAX     │ Δ/min   │
│ 0.054   │ 0.056   │ 0.061   │ +0.002  │
└─────────┴─────────┴─────────┴─────────┘
```

## Screen Layouts

### Dashboard (default)

```
┌────────────────────────────────────────┐
│ ≡  Open RadiaCode              ● LIVE  │
├────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐     │
│  │ DOSE RATE   │  │ COUNT RATE  │     │
│  │ 0.057       │  │ 8.2         │     │
│  │ μSv/h    ▲  │  │ cps      ─  │     │
│  │ ~~~~~~~~~~~ │  │ ~~~~~~~~~~~ │     │
│  └─────────────┘  └─────────────┘     │
│                                        │
│  ┌──────────────────────────────────┐ │
│  │ DOSE RATE — Last 1m              │ │
│  │ 0.06│  ~~~~~~~~~~~~~~~~~~~~~~~~  │ │
│  │ 0.05│  ~~~~~~~~~~~~~~~~~~~~~~~~  │ │
│  │     └──────────────────────────  │ │
│  │ Min 0.054 • Avg 0.056 • Max 0.061│ │
│  └──────────────────────────────────┘ │
│                                        │
│  ┌──────────────────────────────────┐ │
│  │ COUNT RATE — Last 1m             │ │
│  │ [chart with axes]                 │ │
│  │ Min 8.0 • Avg 8.1 • Max 8.3       │ │
│  └──────────────────────────────────┘ │
│                                        │
│  SESSION: 5m 32s │ 332 samples        │
└────────────────────────────────────────┘
```

### Charts (deep analysis)

- Larger charts (200dp height each)
- Time window chips: 10s | 1m | 10m | 1h
- Tap chart for full-screen focus

### Device

- Connection status indicator (pulsing dot)
- Device address
- Auto-connect toggle
- Actions: Find / Reconnect / Stop

### Settings

- Units toggle
- Threshold slider
- Smoothing selector
- Time window default

### Logs

- Export CSV button

## Future Features (Architecture Support)

- **Anomaly markers** on charts (ML-detected)
- **Prediction overlay** (forecast as dashed line)
- **Geolocation** (map heatmap)
- **Multi-device** comparison
- **Alert rules** with notifications
