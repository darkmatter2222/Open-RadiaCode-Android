# Agent Workflow Notes

This file documents the repeatable build + deploy loop and development guidelines for this repo.

## Git Workflow

**Repository:** `https://github.com/darkmatter2222/Open-RadiaCode-Android.git`

After completing a todo list:

1. Create a new branch with a descriptive name: `git checkout -b feature/short-description`
2. Stage all changes: `git add -A`
3. Commit with a meaningful message: `git commit -m "feat: description of changes"`
4. Switch back to main: `git checkout main`
5. Merge the feature branch: `git merge feature/short-description`
6. Push both branches to origin:
   - `git push origin feature/short-description`
   - `git push origin main`

This ensures all changes are tracked with proper branch history on GitHub.

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

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
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

1. **SimpleWidgetProvider** - Compact, values only
2. **ChartWidgetProvider** - Values + sparkline charts
3. **RadiaCodeWidgetProvider** - Legacy with trend indicators

### Widget Layout Rules

- Use `@drawable/widget_background` for outer container
- Use `@drawable/widget_card_background` for inner cards
- Avoid `fontFamily="monospace"` (can cause inflation issues)
- Avoid `android:gravity="baseline"` (not supported in RemoteViews)
- Use fixed heights for ImageViews when possible
- Keep layouts simple - complex nesting can fail on some launchers

### Widget Updates

Widgets are updated from `RadiaCodeForegroundService`:

```kotlin
RadiaCodeWidgetProvider.updateAll(this)
SimpleWidgetProvider.updateAll(this)
ChartWidgetProvider.updateAll(this)
```

### Sparkline Generation

Chart sparklines are generated as Bitmaps:
- Use `Paint.ANTI_ALIAS_FLAG` for smooth lines
- Include subtle background with rounded corners
- Add glow effect behind main line
- Use gradient fill under the line
- Handle empty data gracefully (show placeholder)

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

## UI Specification

See [docs/UI.md](docs/UI.md) for the canonical UI/UX spec including:
- Screen layouts
- Component specifications
- Interaction patterns
- Navigation structure
