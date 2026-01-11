# Open RadioCode — UI spec

This document is the canonical UI/UX spec for the app. If the implementation diverges, update this spec or fix the UI to match.

## Goals

- **Utility-first**: show the *essential* information immediately, no wasted space.
- **Clean hierarchy**: one primary reading card; secondary details in collapsible/secondary screens.
- **Menu-driven**: controls live behind a navigation menu (not as a wall of buttons).
- **Fast-glance**: supports “check and go” usage.

## Navigation model

Single-activity, drawer-style navigation (hamburger menu).

Sections:
- **Dashboard** (default)
- **Charts**
- **Device**
- **Display**
- **Logs**

## Dashboard screen

Purpose: fastest possible glance.

Layout:
- Top app bar: app name + hamburger menu.
- Status line: connection/service status + last update age.
- **Primary reading card**:
  - Large dose value + unit
  - Medium count rate value + unit
- **Trend**: a *small* dose sparkline (compact height). No giant charts.

Interactions:
- Tap sparkline → opens full-screen chart focus.

## Charts screen

Purpose: analysis view.

Layout:
- Two compact charts (dose + count). Still *compact* and consistent.
- Stats row per chart: min/avg/max.
- Overlays:
  - min–max band
  - avg line
  - peak marker
  - optional dose threshold line

## Device screen

Purpose: manage connectivity.

Controls:
- Preferred device display (address).
- Auto-connect toggle.
- Actions:
  - Find devices
  - Reconnect now
  - Stop service

## Display screen

Purpose: presentation settings (not frequent actions).

Controls (tap row cycles):
- Window (10s / 1m / 10m / 1h)
- Smoothing (off / 5s / 30s)
- Units (μSv/h ↔ nSv/h; cps ↔ cpm)
- Dose threshold (off / presets)
- Pause live (on/off)

## Logs screen

Purpose: export data.

Controls:
- Share readings CSV

## Data model (UI-facing)

- **Dose**: base stored as μSv/h; can be displayed as nSv/h.
- **Count rate**: base stored as cps; can be displayed as cpm.
- **Last update**: timestamp shown as age seconds.
- **Threshold**: stored as μSv/h; converted on display.

## Style rules

- Use Material 3 components and the existing theme.
- Avoid hard-coded colors; rely on theme attributes.
- Avoid “button walls”; prefer a drawer menu + simple list rows.
