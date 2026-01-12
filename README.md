# RadiaCodeAndroidDataCollection

Android app (Kotlin) that connects to a RadiaCode 110 over BLE and displays live readings.

App name: **Open RadiaCode**.

> **Speech-to-text note:** The correct name is **RadiaCode** (capital R, capital C, no space).
> Common transcription error: "RadioCode" — this is INCORRECT.

Key goal: appliance-style operation — once a preferred device is set, a foreground service keeps connecting and polling in the background, with a home screen widget showing the latest reading.

## Features

### Dashboard
- **Real-time charts** with zoom, pan, and configurable time windows (30s to 1h)
- **Delta cards** showing change rates with statistical sparklines
- **Spike detection** with configurable markers and percentage annotations
- **Statistical analysis** with z-score highlighting and trend indicators

### Smart Alerts
- Configure up to **10 custom alerts**
- **Threshold-based**: trigger when dose/count goes above or below a value
- **Statistical alerts**: trigger when readings deviate beyond 1σ, 2σ, or 3σ from the mean
- **Duration requirements**: condition must persist for N seconds before triggering
- **Cooldown periods**: prevent alert spam with configurable quiet periods
- **Push notifications** with vibration and quick app access

### Widget
- Home screen widget with live dose and count rates
- Statistical trend indicators (▲ ▼) with σ notation
- Color-coded values based on statistical significance
- Connection status indicator

### Settings
- **Chart Settings**: Time window, smoothing, spike markers, spike percentages
- **Display Settings**: Unit selection (µSv/h, nSv/h, CPS, CPM)
- **Alerts**: Dose threshold, Smart Alerts configuration
- **Advanced**: Pause live updates

## Build + deploy loop (Windows)

See [AGENTS.md](AGENTS.md) for the canonical build/install/debug loop.

Quick version:

- `./gradlew assembleDebug`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb logcat -v time -s RadiaCode`

## App usage

1) Open the app.
2) Open the navigation drawer → **Device** → **Find devices**.
3) Tap your RadiaCode to set it as the preferred device.

After that:

- The foreground service auto-connects and keeps polling.
- A persistent notification shows status and has a **Stop** action.
- The home screen widget (add it from the launcher) shows the last saved reading.

Notes:

- You can’t simulate `BOOT_COMPLETED` / `USER_UNLOCKED` via `adb shell am broadcast` (Android blocks protected broadcasts). Validate boot auto-start by rebooting the phone.
- The foreground service is `exported=false` by design; it starts from inside the app once the preferred device is set.

## Protocol notes

This app implements the RadiaCode BLE protocol (service/characteristics + request/response framing):

- Service UUID: `e63215e5-7003-49d8-96b0-b024798fb901`
- Write: `e63215e6-7003-49d8-96b0-b024798fb901` (18-byte chunks)
- Notify: `e63215e7-7003-49d8-96b0-b024798fb901` (length-prefixed responses)

It polls `VS.DATA_BUF (0x100)` via `COMMAND.RD_VIRT_STRING (0x0826)` and decodes the newest `RealTimeData`.

## UI spec

The canonical UI/UX spec is in [docs/UI.md](docs/UI.md).
