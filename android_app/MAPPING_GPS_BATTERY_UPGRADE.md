# Mapping + GPS Battery Upgrade (Design + Validation)

**Date:** 2026-01-19  
**App/package:** `com.radiacode.ble`  
**Device used for evidence:** Pixel 8 Pro (adb serial `37260DLJG003LN`)

## Purpose
This document captures the complete plan to keep *all* existing mapping and data-collection features while **dramatically reducing battery drain** caused by continuous, high-rate location usage and related background work.

This file is intended to be the single source of truth for the next session so code changes can be executed without needing the prior chat context.

## Non-Goals / Constraints
- Do not remove features.
- Keep mapping usable and accurate.
- Support background usage (app minimized / screen locked) via a foreground service when needed.
- Changes should be measurable and validated with on-device diagnostics.

## Key Clarifications (Terminology)
- **“5 second accuracy” is not accuracy.** It is the requested *update interval* (`minTimeMs`).
- *Accuracy* is typically measured in meters and depends on sensors (GNSS vs network/Wi‑Fi), environment, and device state.

## Evidence Summary (What the device showed)
### 1) Location requests are aggressive and continuous
On-device `dumpsys location` indicated `com.radiacode.ble` holding active high-accuracy GPS requests with:
- ~5 second interval (min/max 5s)
- high accuracy
- sustained active duration (order of ~1h)

### 2) Foreground service is active
`com.radiacode.ble/.RadiaCodeForegroundService` runs as a foreground service and starts background location tracking.

### 3) Notification churn is extreme
`batterystats --checkin` attributed a very high number of notification posts:
- `NotificationManagerService:post:com.radiacode.ble` count on the order of **tens of thousands**.

### 4) Audio wakelock attribution exists
`batterystats --checkin` also showed notable `AudioMix` attribution, consistent with frequent sound playback.

## Code Hotspots (Root causes in repo)
### Location requests (hard-coded 5s)
The app currently requests GPS (and also network) location updates at **5 seconds** in multiple places:
- Background/service tracking:
  - `app/src/main/java/com/radiacode/ble/RadiaCodeForegroundService.kt`
  - `startBackgroundLocationTracking()` calls:
    - `requestLocationUpdates(GPS_PROVIDER, 5000L, 10f, ...)`
    - `requestLocationUpdates(NETWORK_PROVIDER, 5000L, 10f, ...)`
- Map UI tracking:
  - `app/src/main/java/com/radiacode/ble/ui/MapCardView.kt` calls the same 5000L/10f updates.
- Fullscreen map tracking:
  - `app/src/main/java/com/radiacode/ble/FullscreenMapActivity.kt` also requests updates.

**Problem:** multiple independent requesters can keep GNSS hot and increase wakeups.

### Notification update frequency
In `RadiaCodeForegroundService.handleDeviceReading(...)`:
- For every reading (approximately ~1Hz), background work includes:
  - `updateNotificationFromState()`
  - which calls `nm.notify(...)` via `notifyUpdate(...)` even when text hasn’t materially changed.

**Problem:** frequent `notify()` calls cause system work/wakeups and are visible in batterystats.

### Sound playback frequency
`handleDeviceReading(...)` calls `SoundManager.play(..., DATA_TICK)`.
- Even with throttling, frequent SoundPool usage can keep the audio stack active and contribute to `AudioMix`.

## Design Goal
Implement a **two-mode location system** that preserves mapping fidelity:
- **Foreground (interactive):** maximum responsiveness/accuracy.
- **Background (locked/minimized):** lightweight tracking that preserves the map track while reducing battery.

## Proposed Architecture
### 1) Single “Location Owner” (Required)
Create a single location engine (e.g., `LocationController`) that is the *only* component allowed to request location updates.

- UI components (MapCardView / FullscreenMapActivity) should subscribe to the controller for updates.
- The foreground service should also subscribe, rather than owning its own `LocationManager` listener.

**Outcome:** prevents multiple concurrent GPS requests.

### 2) Switch from raw LocationManager GPS to Fused Location Provider (Recommended)
Prefer Google Play Services FLP (`FusedLocationProviderClient`) for better power behavior:
- The system can choose between GNSS/Wi‑Fi/cell and can batch updates.
- Still supports high accuracy when necessary.

(If Play Services dependency is undesirable, a “LocationManager-only” plan can be implemented, but FLP is the standard for best battery behavior.)

### 3) Two-mode behavior
#### Foreground mode (App visible / map open)
Goal: UI feels “live”.
- Priority: High accuracy
- Desired interval: 1000–2000 ms (aligns with ~1Hz readings)
- Min update interval: 500–1000 ms
- Min displacement: 0–2 m (tune; can be 0 if you want smoothness)
- Batching: off (or minimal), to keep UI responsive

#### Background mode (App minimized / screen locked)
Goal: preserve mapping track with lower battery.
Options (choose based on requirements):

A) **Movement-driven background (best balance)**
- Priority: Balanced power most of the time
- Min displacement: 10 m (or slightly higher if acceptable)
- Interval: 2–10 s (not the primary lever)
- Batching: allow batching (max delay 30–120 s) so phone can sleep
- Escalate to high accuracy in short bursts when movement is detected or when user enabled “recording mode”.

B) **Passive + occasional refresh (closest to “cheap variable”)**
- Subscribe to passive updates (or low power)
- If location becomes stale, request a single `getCurrentLocation()` or a short high-accuracy burst

C) **Always-on high accuracy while locked (highest battery)**
- Avoid unless explicitly required; no API makes this “free”.

### 4) Mapping points vs reading frequency (Interpolation strategy)
Your radiation readings arrive about ~1Hz. Location updates may be slower.

To avoid “losing” readings on the map:
- Always record each reading timestamp.
- Attach location in one of these ways:

1) **Hold-last-fix** (simple, robust):
- Each 1Hz reading uses the most recent location fix.

2) **Linear interpolation between fixes** (smoother paths, but estimated):
- For readings between fix A and fix B, interpolate based on time.

3) **Nearest-fix / snap strategy**:
- Assign readings to the nearest real fix to avoid “inventing” straight lines.

Given the hexagon aggregation approach, **hold-last-fix is usually sufficient** and avoids false precision.

## Additional Battery Fixes (High impact, preserve features)
These are not optional if we want “battery no longer gets killed”:

### Notification de-dup + rate limit
- Only call `nm.notify(NOTIF_ID, ...)` when the title/text meaningfully changes **or** at a minimum cadence.
- Suggested: update at most every 5–15 seconds while connected (tunable).
- Keep the same foreground notification and controls.

Expected outcome: huge reduction in `NotificationManagerService:post` count.

### Audio gating in background
- Keep “data tick” feature, but don’t keep audio pipeline hot while locked unless user explicitly wants that.
- Suggested behavior:
  - Foreground: allow data ticks
  - Background: either disable ticks or heavily throttle

### Widget update cadence
- Widgets are being updated very frequently (throttled to ~1s in service).
- Preserve widget functionality but reduce update cadence while background/locked.

## Validation Plan (On-device)
### Before/After commands (PowerShell)
Use the connected device serial.

```powershell
$s='37260DLJG003LN'

# Confirm device
adb -s $s devices

# Snapshot batterystats (package scope)
adb -s $s shell dumpsys batterystats --checkin com.radiacode.ble > before_batterystats_checkin.txt

# Location state (look for mFixInterval / provider requests)
adb -s $s shell dumpsys location > before_location.txt

# Wakelocks/power snapshot
adb -s $s shell dumpsys power > before_power.txt

# Service state
adb -s $s shell dumpsys activity services com.radiacode.ble > before_services.txt
```

Run a controlled 10–20 minute scenario:
- Scenario A: app in foreground with map open (walking/driving)
- Scenario B: app background / screen locked (walking/driving)
- Scenario C: stationary background

Then capture the same artifacts:
```powershell
adb -s $s shell dumpsys batterystats --checkin com.radiacode.ble > after_batterystats_checkin.txt
adb -s $s shell dumpsys location > after_location.txt
adb -s $s shell dumpsys power > after_power.txt
adb -s $s shell dumpsys activity services com.radiacode.ble > after_services.txt
```

### What “success” looks like
- `dumpsys location`: no constant high-accuracy GNSS request in background unless explicitly required.
- `batterystats --checkin`:
  - far fewer `NotificationManagerService:post:com.radiacode.ble` events
  - reduced GPS/location active time
  - reduced `AudioMix` time when locked (if ticks are gated)
- No functional regressions:
  - mapping still plots radiation data sensibly
  - background mapping still produces tracks/hex updates
  - widgets still update reasonably

## Study Results (On-device)
**Build tested:** `0.95` (debug)

### Study protocol
- Reset `batterystats` at the start of the run for cleaner attribution.
- Scenario timing used for this run:
  - ~6 minutes foreground (MainActivity visible)
  - ~10 minutes background + screen off

Note: `FullscreenMapActivity` is `exported=false`, so it cannot be launched directly via `adb shell am start ...` (SecurityException). Foreground time in this run used MainActivity (with the map card available).

### Artifacts captured
- Before: `before_batterystats_checkin.txt`, `before_location.txt`, `before_power.txt`, `before_services.txt`
- After (post-reset study run): `after_batterystats_checkin.txt`, `after_location.txt`, `after_power.txt`, `after_services.txt`, `after_logcat_radiacode.txt`, `after_logcat_errors.txt`

### Key deltas (high signal)
- Notifications:
  - Before `batterystats --checkin`: `NotificationManagerService:post:com.radiacode.ble` showed **~35k** events.
  - After (study run): `NotificationManagerService:post:com.radiacode.ble` is **0** in `after_batterystats_checkin.txt`.
- Audio:
  - Before: `AudioMix` attribution for `com.radiacode.ble` was very high.
  - After (study run): `AudioMix` attribution for `com.radiacode.ble` is **0**, consistent with data tick gating while locked.
- Location:
  - `after_location.txt` shows `gps provider: ProviderRequest[OFF]` at capture time (no active, constant GNSS request visible at the end of the background/screen-off period).

These improvements directly target the three biggest battery offenders identified in the original evidence: GNSS “always-on” request behavior, notification churn, and audio pipeline wake attribution.

## Implementation Order (Next session)
1) Introduce `LocationController` and make it the single location requester.
2) Implement foreground/background modes and transitions (process lifecycle + screen on/off).
3) Switch to FLP (or implement mode-aware `LocationManager` provider selection if FLP is not acceptable).
4) Implement reading-to-location attachment strategy (hold-last-fix; optional interpolation).
5) Add notification de-dup + rate limiting.
6) Gate data tick audio in background.
7) Adjust widget update cadence by mode.
8) Run validation plan and document results.

## Notes
- No code changes were made as part of creating this document.

## Agent Execution Requirement (Do Not Stop Early)
When executing this plan as an agent:
- Do not stop until **all** items in “Implementation Order (Next session)” are implemented.
- Do not stop until the “Validation Plan (On-device)” has been run and logs/artifacts demonstrate expected improvements with **no functional regressions**.
- Iterate in a loop: change code → build/install to phone → inspect logs/dumpsys → repeat until everything is working as intended.

## Next Session Prompt (Copy/Paste)

Use this prompt verbatim in a new chat session (this repo) to resume implementation without relying on prior chat history:

```text
In this repo, read and follow MAPPING_GPS_BATTERY_UPGRADE.md first. Do NOT assume prior chat context exists.

Goals:
- Preserve all existing features (mapping, background service, widgets, alerts).
- Dramatically reduce battery drain by implementing the two-mode location system and related optimizations described in that document.

Constraints:
- Implement code changes according to the “Implementation Order” in the doc.
- Ensure there is only ONE location requester in the app (LocationController).
- Replace raw LocationManager GPS 5s loops with a fused/adaptive approach (or mode-aware LocationManager if FLP is not acceptable).
- Add notification de-dup + rate limiting (reduce NotificationManagerService:post spam).
- Gate data tick audio in background.
- Adjust widget update cadence by mode.

Validation:
- After implementing, run the “Validation Plan (On-device)” commands in the doc and summarize before/after outcomes.

Workflow:
- Create a new feature branch, commit, and push to origin when done.
```
