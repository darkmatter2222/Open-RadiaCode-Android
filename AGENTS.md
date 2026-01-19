# Agent Workflow Notes

This file documents the repeatable build + deploy loop and development guidelines for this repo.

## Versioning

**Current Version:** See `app/build.gradle.kts` → `versionName`

- **Format:** `MAJOR.MINOR` (e.g., `0.01`, `0.02`, `1.00`)
- **Minor versions:** Agents increment minor version for each build (e.g., `0.01` → `0.02`)
- **Major versions:** Only increment on GitHub releases (e.g., `0.99` → `1.00`)
- **Starting version:** `0.01`

When building, always check the current version and increment the minor:
```kotlin
// In app/build.gradle.kts
versionName = "0.XX"  // Increment XX each build
```

## Git Workflow

**Repository:** `https://github.com/darkmatter2222/Open-RadiaCode-Android.git`

After completing a todo list:

1. Create a new branch with a descriptive name: `git checkout -b feature/short-description`
2. Stage all changes: `git add -A`
3. Commit with a meaningful message: `git commit -m "feat: description of changes"`
4. **Always push the feature branch to origin:** `git push origin feature/short-description`

**Merging to main is ONLY done when instructed by the user.** When the user requests a merge:

5. Switch back to main: `git checkout main`
6. Merge the feature branch: `git merge feature/short-description`
7. Push main to origin: `git push origin main`

**Key principle:** We always commit and push to origin on a feature branch after completing work. The branch is always available on GitHub. Merging onto main requires explicit user instruction.

## Prerequisites

- Android SDK installed at `C:\Users\ryans\AppData\Local\Android\Sdk` (or set `ANDROID_SDK_ROOT`)
- JDK 17 installed (or use Android Studio embedded JBR)
- Phone connected over USB with **USB debugging** enabled

If you hit `JAVA_HOME is not set` when running Gradle, use Android Studio's embedded JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Build + Install (Fast Loop)

From repo root:

```powershell
# Build debug APK
./gradlew assembleDebug

# Install to connected device (do this EVERY build)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# IMPORTANT: Restart the app after install to load new code
adb shell am force-stop com.radiacode.ble
adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1

# IMPORTANT: Copy APK to Installer folder (do this EVERY build)
Copy-Item app/build/outputs/apk/debug/app-debug.apk -Destination Installer/OpenRadiaCode-v0.XX.apk
```

**ALWAYS** after a successful build:
1. Install the APK to the connected phone via `adb install -r`
2. **Restart the app** - the install does NOT restart it automatically
3. Copy the APK to the `Installer/` folder with the version number in the filename

## Installer Folder

The `Installer/` folder contains distributable APK files:
- Location: `<repo>/Installer/`
- Naming convention: `OpenRadiaCode-v{VERSION}.apk`
- Example: `OpenRadiaCode-v0.01.apk`

After every build, copy the APK:
```powershell
Copy-Item app/build/outputs/apk/debug/app-debug.apk -Destination "Installer/OpenRadiaCode-v$VERSION.apk"
```

## Debug Logs

```powershell
# View app logs
adb logcat -v time -s RadiaCode

# Clear log buffer first
adb logcat -c

# View all error-level logs
adb logcat *:E
```

Optional checks:

```powershell
# Check device connection
adb devices

# Verify package installed
adb shell pm list packages | findstr /i com.radiacode.ble

# Launch app via adb
adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1
```

## App Identity

- **Launcher name:** Open RadiaCode
- **Package:** `com.radiacode.ble`

> **Speech-to-text note:** The correct name is **RadiaCode** (capital R, capital C, no space).
> Common transcription error: "RadioCode" — this is INCORRECT.

## Design System

### Color Theme (Pro Dark)

The app uses a professional dark theme. Always use these colors:

| Name | Hex | Usage |
|------|-----|-------|
| `pro_background` | `#0D0D0F` | Main background |
| `pro_surface` | `#1A1A1E` | Cards, dialogs, widgets |
| `pro_border` | `#2A2A2E` | Subtle borders |
| `pro_muted` | `#6E6E78` | Disabled/secondary text |
| `pro_text_secondary` | `#9E9EA8` | Labels |

### Accent Colors

| Name | Hex | Usage |
|------|-----|-------|
| `pro_cyan` | `#00E5FF` | Dose rate values, primary accent |
| `pro_magenta` | `#E040FB` | Count rate values, secondary accent |
| `pro_green` | `#69F0AE` | Connected status, positive |
| `pro_red` | `#FF5252` | Disconnected, errors, alerts |
| `pro_yellow` | `#FFD600` | Warnings |

### UI Guidelines

- Use monospace fonts for numeric values
- Cards have 16dp corner radius, 1dp border
- Consistent 12-16dp padding in containers
- Status dots: ● (connected/green) or ○ (disconnected/red)

## Widget Development

### Widget Types

1. **UnifiedWidgetProvider** - The ONLY widget provider (V2 system)
   - Uses `WidgetRenderer` for all rendering
   - Same rendering code for preview AND widget
   - Configuration via `WidgetConfigActivityV2`

### Widget Layout Rules (RemoteViews Gotchas)

**CRITICAL: "Can't load widget" errors are caused by RemoteViews incompatibilities.**

RemoteViews only supports a LIMITED subset of Views and methods. Common pitfalls:

#### ❌ DO NOT USE:
- `<View>` elements - **NOT ALLOWED AT ALL** in RemoteViews! Use `FrameLayout` or `Space` instead
- `fontFamily="monospace"` - Can cause inflation issues on some devices
- `android:gravity="baseline"` - NOT supported in RemoteViews
- `View` with `setBackgroundResource()` via `setInt()` - Use ImageView instead
- Complex nested layouts - Keep it simple
- Custom views - Only standard Android views work
- `ConstraintLayout` - Not supported by RemoteViews

#### ✅ SAFE ALTERNATIVES:
- For spacers: Use `FrameLayout` or `Space` instead of plain `View`
- For status indicators: Use `ImageView` + `setImageViewResource()` instead of `View` + `setBackgroundResource()`
- For backgrounds: Use `setImageViewResource()` or static drawables
- For text: `setTextViewText()`, `setTextColor()` work reliably
- For visibility: `setViewVisibility()` works on all supported views

#### Supported RemoteViews Methods:
```kotlin
// SAFE - These work reliably:
views.setTextViewText(R.id.myText, "value")
views.setTextColor(R.id.myText, color)
views.setViewVisibility(R.id.myView, View.VISIBLE)
views.setImageViewResource(R.id.myImage, R.drawable.icon)
views.setImageViewBitmap(R.id.myImage, bitmap)
views.setOnClickPendingIntent(R.id.root, pendingIntent)

// DANGEROUS - Can fail on some devices:
views.setInt(R.id.view, "setBackgroundResource", drawable)  // ❌ Use ImageView instead
```

### Widget Updates

Widgets are updated from `RadiaCodeForegroundService`:

```kotlin
UnifiedWidgetProvider.updateAll(this)
```

### Sparkline Generation

Chart sparklines are generated as Bitmaps:
- Use `Paint.ANTI_ALIAS_FLAG` for smooth lines
- Include subtle background with rounded corners
- Add glow effect behind main line
- Use gradient fill under the line
- Handle empty data gracefully (show placeholder)

## Performance Optimization (Critical Lessons)

### BLE Polling Thread - Keep It Fast!

**CRITICAL:** The BLE polling callbacks (`onDeviceReading`, `onDeviceStateChanged`) run on the BLE thread. Any blocking work here will slow down the entire poll cycle.

**Root cause of slow UI updates:** When `handleDeviceReading()` was doing heavy work synchronously, the poll cycle went from ~1s to ~4-5s:
- `Prefs.addMapDataPoint()` parses/rebuilds 86,400-entry strings
- Widget bitmap rendering (5 providers)
- CSV file I/O
- Alert evaluation with Prefs reads

**Solution:** Move ALL heavy work to `executor.execute{}` background thread:
```kotlin
private fun handleDeviceReading(deviceId: String, uSvPerHour: Float, cps: Float, timestampMs: Long) {
    // ONLY synchronous: broadcast to UI
    sendBroadcast(Intent(ACTION_READING).putExtras(...))
    
    // EVERYTHING else in background
    executor.execute {
        requestWidgetUpdate()           // Widget rendering
        updateNotificationFromState()   // Notification update  
        appendReadingCsvIfNew(...)      // File I/O
        Prefs.addMapDataPoint(...)      // Heavy SharedPrefs work
        alertEvaluator.evaluate(...)    // Alert evaluation
    }
}
```

### SharedPreferences - Avoid Large String Parsing on UI Thread

**Problem:** `Prefs.getMapDataPoints()` and `Prefs.getRecentReadings()` parse thousands of entries from comma-separated strings. This is O(n) and blocks the calling thread.

**Symptoms:**
- Poll cycle takes 3-5 seconds instead of 1 second
- UI feels sluggish
- Hexagon map updates slowly

**Solutions:**
1. Move heavy Prefs reads/writes to background threads
2. Use broadcast receivers for immediate UI updates (don't poll Prefs)
3. Include relevant data in broadcasts (e.g., lat/lng for map updates)

### Broadcasts for Immediate UI Updates

**Pattern:** Instead of having UI components poll SharedPreferences, include all needed data in the broadcast:

```kotlin
// In service - include location in broadcast
val i = Intent(ACTION_READING)
    .putExtra(EXTRA_USV_H, uSvPerHour)
    .putExtra(EXTRA_CPS, cps)
    .putExtra(EXTRA_LATITUDE, location.latitude)   // Include location!
    .putExtra(EXTRA_LONGITUDE, location.longitude)
sendBroadcast(i)

// In activity - use broadcast data directly
readingReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        // Update hexagon grid immediately - no Prefs polling needed!
        hexagonData.getOrPut(hexId) { mutableListOf() }.add(reading)
    }
}
```

## Key Architecture Points

### Boot Startup

The app auto-starts on boot via `BootReceiver`:
- Listens for `BOOT_COMPLETED` and `USER_UNLOCKED`
- Checks if `preferred_address` is set in Prefs
- Starts `RadiaCodeForegroundService` if configured

**Important:** Cannot test boot via `adb shell am broadcast` - Android blocks protected broadcasts. Must reboot phone to test.

### Auto-Connect Flow

1. App opens → `MainActivity.onCreate()`
2. Checks if preferred device exists
3. Starts `RadiaCodeForegroundService`
4. Service connects to BLE device
5. Service polls `VS.DATA_BUF` every ~1 second
6. Updates widgets and broadcasts to MainActivity

### Data Storage

All data goes through `Prefs` class:
- `setLastReading()` / `getLastReading()` - Current reading
- `addRecentReading()` / `getRecentReadings()` - Rolling buffer for charts
- Unit preferences: `getDoseUnit()`, `getCountUnit()`
- Smart alerts stored as JSON in SharedPreferences

## Testing Notes

### Widget Testing
- After layout changes, remove and re-add widget
- "Can't load widget" usually means layout inflation error
- Check logcat for `RemoteViews` errors

### Boot Testing
- Must physically reboot device
- Check logcat for `BootReceiver` and `RadiaCode` tags
- Service should start ~10-30 seconds after boot

### BLE Connection
- Ensure official RadiaCode app is disconnected
- Device shouldn't be paired in Android Bluetooth settings
- Service auto-reconnects on connection drop

## Documentation Updates

**IMPORTANT:** When making changes to features, always update:

1. **README.md** - Feature lists, usage instructions, architecture
2. **docs/UI.md** - UI/UX specifications when changing interface
3. **AGENTS.md** - Build/deploy considerations, design system
4. **RADIACODE_PROTOCOL.md** - BLE/USB protocol when changing device communication

## RadiaCode Protocol Reference

📖 See **[RADIACODE_PROTOCOL.md](RADIACODE_PROTOCOL.md)** for the complete device communication protocol:

- **Commands**: All COMMAND enum values for sending requests
- **Virtual Strings (VS)**: DATA_BUF, SPECTRUM, CONFIGURATION, etc.
- **VSFRs**: Device settings registers (brightness, language, alarms)
- **DATA_BUF Records**: Byte-level format for each record type
- **Battery/Temperature**: Encoded in GRP_RareData (eid=0, gid=3)
  - Temperature: `(raw_u16 - 2000) / 100.0` → Celsius
  - Battery: `raw_u16 / 100` → 0-100%
- **Events**: Power, alarms, charging, temperature warnings
- **Spectrum**: Energy calibration and count data formats

This documentation was derived from the [cdump/radiacode](https://github.com/cdump/radiacode) Python library.

## UI Specification

See [docs/UI.md](docs/UI.md) for the canonical UI/UX spec including:
- Screen layouts
- Component specifications
- Interaction patterns
- Navigation structure
