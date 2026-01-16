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
│ DELTA DOSE RATE              ▲ +2σ │  ← Label + trend (σ notation)
│ 0.057                              │  ← Hero value (48sp)
│ μSv/h                              │  ← Unit
│ ───────────────────                │  ← Mini sparkline with mean line
│                                     │  ← Color segments by z-score
└─────────────────────────────────────┘
```

Statistical features:
- Sparkline segments colored by z-score (intensity = deviation from mean)
- Dotted mean line for reference
- Trend arrows with σ notation: ▲▲ (+>2σ), ▲ (+>1σ), ─ (within σ), ▼ (->1σ), ▼▼ (->2σ)

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

### Isotope Identification Panel

Located below the count rate chart on the dashboard:

```
┌──────────────────────────────────────────┐
│ ISOTOPE ID         📊  ⚙️  ● Streaming  │  ← Header with chart/settings buttons
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ [SCAN] ○ Real-time                   │ │  ← Mode selector
│ └──────────────────────────────────────┘ │
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ Multi-line / Stacked / Bars          │ │  ← Chart type (toggle)
│ │                                       │ │
│ │ ▬▬▬▬▬▬▬▬▬▬ Cs-137 (42%)              │ │  ← Chart area with legend
│ │ ▬▬▬▬▬▬▬ K-40 (31%)                   │ │
│ │ ▬▬▬▬ Ra-226 (18%)                    │ │
│ │ ▬▬ Co-60 (9%)                        │ │
│ │                                       │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ TOP: Cs-137 • 42% probability            │  ← Quick view of top result
└──────────────────────────────────────────┘
```

**Chart Types:**
- **Multi-line**: Time series showing top 5 isotope probabilities as separate lines
- **Stacked Area**: Cumulative fraction view over time
- **Animated Bars**: Horizontal bars with mini sparkline history

**Display Modes:**
- **Probability**: 0-100% confidence for each isotope
- **Fraction**: Relative contribution (adds to 100%)

**Supported Isotopes (15):**

| Category | Isotopes |
|----------|----------|
| Natural/Background | K-40, Th-232, U-238, Ra-226 |
| Medical | Tc-99m, I-131, F-18 |
| Industrial | Co-60, Am-241, Ir-192, Ba-133, Eu-152 |
| Fission | Cs-137, Cs-134, Na-22 |

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

Settings are organized into expandable sections:

#### Chart Settings (cyan header)
- Time window: 10s / 1m / 10m / 1h
- Smoothing: Off / 3s / 5s / 10s / 30s
- Spike markers: On/Off (shows triangle indicators at significant deltas)
- Spike percentages: On/Off (shows percentage labels above spike markers)

#### Display Settings (magenta header)
- Units: µSv/h • CPS / nSv/h • CPS / µSv/h • CPM / nSv/h • CPM

#### Alerts (amber header)
- Dose threshold: Off / 0.05 / 0.10 / 0.50 / 1.00 µSv/h
- Smart Alerts: Opens AlertConfigActivity wizard

#### Advanced (muted header, collapsed by default)
- Pause live updates: On/Off (freezes charts for inspection)

### Smart Alerts Configuration

Full wizard for configuring 0-10 statistical alerts:
- List view with swipe-to-delete
- FAB to add new alert
- Each alert has:
  - Name
  - Metric: Dose Rate / Count Rate
  - Condition: Above threshold / Below threshold / Outside σ band
  - Threshold value (for above/below) or σ level (1σ/2σ/3σ)
  - Duration requirement (seconds condition must persist)
  - Cooldown period (seconds between repeated alerts)

### Logs

- Export CSV button

## Future Features (Architecture Support)

- **Anomaly markers** on charts (ML-detected)
- **Prediction overlay** (forecast as dashed line)
- **Geolocation** (map heatmap)
- **Multi-device** comparison

## Implemented Features

### Statistical Analysis
- Real-time mean and standard deviation calculation
- Z-score based coloring for sparklines
- Trend indicators using σ notation
- Statistical alerts that adapt to baseline

### Smart Alerts
- Up to 10 configurable alerts
- Threshold-based (above/below)
- Statistical (outside N standard deviations)
- Duration requirements (sustained condition)
- Cooldown periods (prevent spam)
- Push notifications with vibration

### Chart Enhancements
- Spike markers (triangles at significant deltas)
- Configurable spike percentages
- Zoom and pan with pinch/drag gestures
- Rolling average overlay

### Widget
- Home screen widget with delta-style cards
- Statistical trend display
- Color-coded values
- Connection status indicator

### Isotope Identification
- Real-time spectrum reading from RadiaCode device
- Energy calibration using device coefficients (keV to channel conversion)
- ROI-based isotope detection algorithm with sigmoid scoring
- 15 common isotopes across 4 categories
- Three chart visualization types:
  - Multi-line time series
  - Stacked area fractions
  - Animated horizontal bars with sparklines
- Scan mode for one-shot analysis
- Real-time streaming mode for continuous identification
- Configurable probability vs fraction display
- Isotope settings activity to enable/disable individual isotopes
