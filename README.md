# Open RadiaCode Android

Android app (Kotlin) that connects to a RadiaCode 110 over BLE and displays live readings.

App name: **Open RadiaCode**

> **Speech-to-text note:** The correct name is **RadiaCode** (capital R, capital C, no space).
> Common transcription error: "RadioCode" — this is INCORRECT.

## Versioning

- **Format:** `MAJOR.MINOR` (e.g., `0.01`, `0.02`, `1.00`)
- **Minor versions:** Incremented for each build
- **Major versions:** Only increment on GitHub releases
- **Current version:** See `app/build.gradle.kts` → `versionName`

## Download & Installation

Pre-built APK files are available in the [`Installer/`](Installer/) folder of this repository.

### Step 1: Download the APK

1. Go to the [`Installer/`](Installer/) folder in this repository
2. Click on the latest `OpenRadiaCode-v{VERSION}.apk` file (e.g., `OpenRadiaCode-v0.01.apk`)
3. Click the **Download** button (or "Download raw file" icon)
4. The APK will download to your phone or computer

> **Tip:** If downloading on a computer, transfer the APK to your Android device via USB, email, or cloud storage.

### Step 2: Enable Installation from Unknown Sources

Android blocks app installations from outside the Play Store by default. You need to enable "Install unknown apps" for your browser or file manager:

**On Android 8.0+ (Oreo and newer):**
1. When you try to install the APK, Android will prompt you to allow installation
2. Tap **Settings** on the prompt
3. Toggle **"Allow from this source"** ON
4. Go back and continue the installation

**Or manually enable it:**
1. Open **Settings** → **Apps** (or **Apps & notifications**)
2. Tap the **⋮** menu → **Special access** → **Install unknown apps**
3. Find your browser (Chrome, Firefox, etc.) or file manager
4. Toggle **"Allow from this source"** ON

**On older Android versions (7.x and below):**
1. Open **Settings** → **Security**
2. Enable **"Unknown sources"**
3. Confirm the warning prompt

### Step 3: Install the APK

1. Open your **Downloads** folder or file manager
2. Tap the `OpenRadiaCode-v{VERSION}.apk` file
3. Tap **Install**
4. Wait for installation to complete
5. Tap **Open** to launch the app

### Step 4: Grant Permissions

When you first run the app, grant these permissions when prompted:
- **Bluetooth** - Required to connect to your RadiaCode device
- **Location** - Required by Android for BLE scanning (the app doesn't track your location)
- **Notifications** - For radiation alerts and background service status

### Troubleshooting

| Problem | Solution |
|---------|----------|
| "App not installed" error | Make sure you don't have a conflicting version. Uninstall any old version first. |
| Can't find the APK | Check your browser's download folder, usually `/Download/` |
| "Installation blocked" | Enable "Install unknown apps" for your browser (Step 2) |
| App crashes on launch | Ensure your Android version is 8.0 or higher |

### Updating the App

To update to a newer version:
1. Download the new APK from the `Installer/` folder
2. Install it over the existing app (your settings will be preserved)
3. If installation fails, uninstall the old version first, then install the new one

## Overview

Key goal: appliance-style operation — once a preferred device is set, a foreground service keeps connecting and polling in the background, with home screen widgets showing the latest readings. The app automatically connects on boot and maintains connection in the background.

## Features

### Dashboard
- **Real-time charts** with zoom, pan, and configurable time windows (30s to 1h)
- **Delta cards** showing change rates with statistical sparklines
- **Spike detection** with configurable markers and percentage annotations
- **Statistical analysis** with z-score highlighting and trend indicators
- **Tap charts to focus** - full-screen chart view with enhanced detail

### Smart Alerts
- Configure up to **10 custom alerts**
- **Threshold-based**: trigger when dose/count goes above or below a value
- **Statistical alerts**: trigger when readings deviate beyond 1σ, 2σ, or 3σ from the mean
- **Duration requirements**: condition must persist for N seconds before triggering
- **Cooldown periods**: prevent alert spam with configurable quiet periods
- **Push notifications** with vibration and quick app access
- **Color-coded severity**: Green (info), Yellow (warning), Red (critical)

### Home Screen Widgets
Three widget styles to choose from:

1. **RadiaCode Simple** - Compact widget showing:
   - Connection status indicator (green dot = connected)
   - Dose rate with configurable units (µSv/h or nSv/h)
   - Count rate with configurable units (CPS or CPM)
   - Last update timestamp

2. **RadiaCode Charts** - Full-featured widget with:
   - Connection status indicator
   - Dose rate with live sparkline chart
   - Count rate with live sparkline chart
   - Real-time updates every second
   - Gradient-filled chart visualization with glow effects

3. **RadiaCode (Legacy)** - Original widget with trend indicators

All widgets:
- Update in real-time as readings come in
- Respect your unit preferences from Settings
- Show connection status with color-coded indicator
- Tap to open the app

### Auto-Connect & Boot Start
- **Auto-connect on app launch**: When you open the app, it automatically connects to your preferred device
- **Boot-time startup**: The app starts a background service when your phone boots up
- **Persistent connection**: Maintains BLE connection even when app is in background
- **Automatic reconnection**: If connection drops, automatically attempts to reconnect
- **No manual intervention needed**: Once configured, the app works like an appliance

### Settings
- **Chart Settings**: Time window, smoothing, spike markers, spike percentages
- **Display Settings**: Unit selection (µSv/h, nSv/h, CPS, CPM)
- **Alerts**: Dose threshold, Smart Alerts configuration
- **Advanced**: Pause live updates

## Design Theme

The app uses a **professional dark theme** optimized for radiation monitoring:

### Color Palette
| Color | Hex | Usage |
|-------|-----|-------|
| Background | `#0D0D0F` | Main background (near black) |
| Surface | `#1A1A1E` | Cards, widgets |
| Border | `#2A2A2E` | Subtle borders |
| Muted | `#6E6E78` | Secondary text |
| Secondary | `#9E9EA8` | Labels |

### Accent Colors
| Color | Hex | Usage |
|-------|-----|-------|
| Cyan | `#00E5FF` | Dose rate values and charts |
| Magenta | `#E040FB` | Count rate values and charts |
| Green | `#69F0AE` | Connected status, positive trends |
| Red | `#FF5252` | Disconnected status, alerts |
| Yellow | `#FFD600` | Warnings |

### Typography
- Monospace fonts for numeric values
- Bold labels for section headers
- Subtle letter spacing on category labels

## Build + Deploy (Windows)

See [AGENTS.md](AGENTS.md) for the complete build/install/debug workflow.

Quick version:

```powershell
# Set Java home (if needed)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -v time -s RadiaCode
```

## App Usage

### First Time Setup
1. Open the app
2. Open the navigation drawer → **Device** → **Find devices**
3. Tap your RadiaCode to set it as the preferred device

### After Setup
- The foreground service auto-connects and keeps polling
- A persistent notification shows status and has a **Stop** action
- Add home screen widgets from the launcher to see readings at a glance
- The app will automatically start and connect when your phone boots

### Changing Units
1. Open Settings (gear icon)
2. Scroll to Display Settings
3. Select preferred Dose unit (µSv/h or nSv/h)
4. Select preferred Count unit (CPS or CPM)
5. Widgets update automatically

## Architecture

### Key Components

| Component | Description |
|-----------|-------------|
| `MainActivity` | Main dashboard with charts and cards |
| `RadiaCodeForegroundService` | Persistent background service for BLE communication |
| `BootReceiver` | Starts service on device boot (BOOT_COMPLETED, USER_UNLOCKED) |
| `SimpleWidgetProvider` | Compact home screen widget |
| `ChartWidgetProvider` | Widget with sparkline charts |
| `RadiaCodeWidgetProvider` | Legacy widget with trend indicators |
| `Prefs` | SharedPreferences wrapper for settings and readings |
| `SmartAlertsManager` | Handles alert evaluation and notifications |

### Data Flow

```
RadiaCodeForegroundService
    ↓ (BLE polling every ~1s)
Prefs.setLastReading() + Prefs.addRecentReading()
    ↓
*WidgetProvider.updateAll()  →  Home screen widgets
    ↓
LocalBroadcast  →  MainActivity (real-time charts)
```

### Boot Startup Flow

```
Android Boot Complete
    ↓
BootReceiver (BOOT_COMPLETED / USER_UNLOCKED)
    ↓
Check: preferred_address set?
    ↓ (yes)
Start RadiaCodeForegroundService
    ↓
Service connects to RadiaCode
    ↓
Begin polling and updating widgets
```

## Protocol Notes

This app implements the RadiaCode BLE protocol:

- **Service UUID**: `e63215e5-7003-49d8-96b0-b024798fb901`
- **Write Characteristic**: `e63215e6-7003-49d8-96b0-b024798fb901` (18-byte chunks)
- **Notify Characteristic**: `e63215e7-7003-49d8-96b0-b024798fb901` (length-prefixed responses)

It polls `VS.DATA_BUF (0x100)` via `COMMAND.RD_VIRT_STRING (0x0826)` and decodes the newest `RealTimeData`.

## Troubleshooting

### Device not found during scan
- Ensure the official RadiaCode app is not connected
- Check that the device isn't paired in Android Bluetooth settings
- Make sure the RadiaCode is powered on

### Widget shows "No data"
- Open the app to establish connection
- Check that the service is running (notification visible)
- Verify preferred device is set in Device settings

### Boot auto-start not working
- Can't test via `adb shell am broadcast` (Android blocks protected broadcasts)
- Reboot the phone to test boot startup
- Ensure a preferred device address is saved

### Connection drops frequently
- Move closer to the RadiaCode device
- Check RadiaCode battery level
- The service will automatically attempt reconnection

## UI Specification

The canonical UI/UX spec is in [docs/UI.md](docs/UI.md).

## License

See LICENSE file for details.
