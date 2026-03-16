# Agent Workflow Notes

This file documents the repeatable build + deploy loop and development guidelines for this repo.

---
description: "Autonomous execution agent for this repo. Default behavior: proceed without asking, iterating until the objective is complete, while respecting explicit safety gates."
tools: ["repo_read", "repo_write", "terminal", "search", "build", "tests", "lint"]
---

# Agent Operating Contract (Read First)

## Mission
Complete the user’s objective end-to-end. Work autonomously: plan, implement, build, install to the phone, validate via logs/dumpsys, fix issues, and repeat until done.
## CRITICAL: No Emojis

**Emojis are ILLEGAL in this app. Never use emojis anywhere:**
- No emojis in UI text, titles, labels, or buttons
- No emojis in notifications or toast messages
- No emojis in code comments or string resources
- No emoji characters in any user-facing content

Use text labels, icons from drawable resources, or Unicode symbols (like bullet points •) instead.
## Default Behavior: Keep Going
Keep iterating until ALL acceptance criteria are satisfied:
- App builds successfully.
- On-device install succeeds and the app is relaunched after each install.
- Validation commands/logs required by the task are captured and show expected improvements.
- No functional regressions (mapping, background service, widgets, alerts).
- Documentation is updated for any behavior/usage changes.

When something fails:
1) read the error output,
2) identify the root cause,
3) apply a fix,
4) re-run the relevant check(s),
5) repeat until green.

Note: “run indefinitely” means “do not stop early”; stop only when complete or genuinely blocked by a STOP condition.

## MANDATORY: Unified Deploy Rule (NO EXCEPTIONS)

**Phone deploy and Git push are ALWAYS done together. No exceptions.**

Whenever you deploy to the phone, you MUST also commit and push to the repo.
Whenever you commit/push to the repo, you MUST also deploy to the phone.

### Full Deploy Command Sequence

Run this COMPLETE sequence for every deploy:

```powershell
# 1. Build
./gradlew assembleDebug

# 2. Install to phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Restart app (install does NOT restart automatically)
adb shell am force-stop com.radiacode.ble
adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1

# 4. Copy APK to Installer folder
Copy-Item app/build/outputs/apk/debug/app-debug.apk -Destination "Installer/OpenRadiaCode-v$VERSION.apk"

# 5. Commit all changes
git add -A
git commit -m "feat/fix: description of changes"

# 6. Push to origin
git push origin HEAD

# 7. If user requested merge to main:
git checkout main
git pull origin main
git merge <feature-branch> -m "Merge <feature-branch>: description"
git push origin main
```

### Quick Reference (Copy-Paste Ready)

For a typical deploy cycle on current branch:
```powershell
./gradlew assembleDebug; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am force-stop com.radiacode.ble; adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1; git add -A; git commit -m "feat: description"; git push origin HEAD
```

For deploy + merge to main:
```powershell
./gradlew assembleDebug; adb install -r app/build/outputs/apk/debug/app-debug.apk; adb shell am force-stop com.radiacode.ble; adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1; git add -A; git commit -m "feat: description"; git push origin HEAD; git checkout main; git pull origin main; git merge - -m "Merge: description"; git push origin main
```

## Required Device Loop (This Repo)
After every successful build, ALWAYS:
1) `adb install -r` the new APK to the connected phone
2) force-stop and relaunch the app (install does not restart it)
3) check logs for errors/regressions (at minimum: `adb logcat -v time -s RadiaCode` and error-level logs)
4) **commit and push to git** (MANDATORY - see Unified Deploy Rule above)

## Git Workflow (This Repo)
- Work on a feature branch.
- Make small, reviewable commits when milestones are stable.
- Always push the feature branch to origin when the task is complete.
- **Always deploy to phone when pushing** (MANDATORY - see Unified Deploy Rule above)
- Never merge to `main` unless explicitly instructed.

## STOP Conditions (Only reasons to pause)
Stop and ask the user only if required:
1) Credentials, secrets, tokens, or access you do not have
2) Actions that affect production, billing, or external infrastructure
3) Destructive operations (mass deletes, history rewrites/force-push)
4) Legal/compliance uncertainty (licenses/copyright)
5) Ambiguity that would change a public API/behavior in a breaking way

If a STOP condition is hit:
- Explain the issue briefly
- Propose the safest default
- Ask for exactly what’s needed to proceed

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

## Completion Summary Format

After completing a task and deploying, always end your response with a concise summary block:

```
**Version:** X.XX
**Branch:** feature/description
**Deployed:** Yes (phone restarted)
**Pushed:** Yes
```

This provides immediate visibility into the deployment state without scrolling through logs.

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
---

## Vega AI Voice System

Vega is the app's radiological awareness AI companion. She provides voice guidance, warnings, and context throughout the user experience.

### Vega API Gateway

**Base URL:** `http://99.122.58.29:443`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tts/synthesize` | POST | Text-to-Speech (returns WAV audio) |
| `/api/tts/synthesize/b64` | POST | TTS with base64 response (JSON with `audio_base64`, `duration_seconds`) |
| `/api/tts/health` | GET | TTS service health check |
| `/api/llm/chat` | POST | LLM chat completion |
| `/api/llm/generate` | POST | LLM text generation |
| `/api/llm/health` | GET | LLM service health check |

### Vega TTS API

**Endpoint:** `http://99.122.58.29:443/api/tts/synthesize`  
**Method:** POST  
**Content-Type:** `application/json`  
**Body:** `{"text": "Your message here"}`  
**Response:** WAV audio file

### Pre-Baked Audio Pattern (Preferred)

For modal dialogs and predictable UI flows, **pre-bake audio files** instead of making runtime API calls:

1. **Generate audio during development:**
   ```powershell
   # Write JSON to temp file (avoids shell escaping issues)
   $json = '{"text":"Your warning text here."}'
   $json | Out-File -FilePath "request.json" -Encoding utf8 -NoNewline
   
   # Call Vega TTS API
   curl.exe -X POST "http://99.122.58.29:443/api/tts/synthesize" `
     -H "Content-Type: application/json" `
     -d "@request.json" `
     -o "app/src/main/res/raw/vega_your_audio.wav"
   
   # Clean up
   Remove-Item "request.json"
   ```

2. **Store in res/raw:**
   - Location: `app/src/main/res/raw/`
   - Naming: `vega_<purpose>.wav` (e.g., `vega_gps_warning.wav`, `vega_intro.wav`)

3. **Play via MediaPlayer:**
   ```kotlin
   mediaPlayer = MediaPlayer.create(context, R.raw.vega_gps_warning)?.apply {
       setAudioAttributes(
           AudioAttributes.Builder()
               .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
               .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
               .build()
       )
       start()
   }
   ```

### Text Guidelines for TTS

When writing text for Vega to speak:
- **Avoid hyphens** in compound words (write "high accuracy" not "high-accuracy")
- **Use periods** for natural pauses
- **Spell out abbreviations** the first time ("GPS" is fine, but avoid "µSv/h")
- **Keep sentences concise** for clarity

### Real-Time TTS (VegaTTS.kt)

For dynamic content where pre-baking isn't possible, use `VegaTTS.speak()`:
```kotlin
VegaTTS.speak(context, "Dynamic message about ${reading} microsieverts per hour")
```

Only use real-time TTS when the content is truly dynamic (e.g., actual readings, user-specific data).

---

## Modal Dialog Design Patterns

### Standard Modal Structure

All modals follow this visual hierarchy:

```
┌─────────────────────────────────────────────────────┐
│  ⚠️  Title Text                                     │  ← Icon + Title row
├─────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────┐  │
│  │  ▁▂▃▅▆▇▅▃▂▁▂▃▅▆▇▅▃▂▁▂▃▅▆▇                    │  │  ← Waveform visualizer
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  Body text explaining the situation. Keep it        │  ← Warning/info text
│  concise but complete.                              │
│                                                     │
│                        [Cancel]  [Primary Action]   │  ← Button row (right-aligned)
└─────────────────────────────────────────────────────┘
```

### Required Visual Effects

#### 1. Background Blur (Android 12+)
```kotlin
window?.apply {
    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setDimAmount(0.7f)  // 70% dim
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setBackgroundBlurRadius(25)  // Blur radius in pixels
    }
}
```

#### 2. Card Styling
```kotlin
private fun createCardBackground(density: Float): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * density  // 16dp corners
        setColor(Color.parseColor("#1A1A1E"))  // pro_surface
        setStroke((1 * density).toInt(), Color.parseColor("#2A2A2E"))  // pro_border
    }
}
```

#### 3. Waveform Visualizer Integration

Every modal with Vega voice MUST include a `WaveformVisualizerView`:

```kotlin
// Container with subtle border
val waveformContainer = FrameLayout(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        (80 * density).toInt()  // 80dp height for modals (140dp for full-screen intros)
    ).apply {
        bottomMargin = (16 * density).toInt()
    }
    background = createWaveformBackground(density)  // #121216 fill, #2A2A2E border
}

// Waveform view
waveformView = WaveformVisualizerView(context).apply {
    layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    ).apply {
        // 2dp inset from container border
        leftMargin = (2 * density).toInt()
        rightMargin = (2 * density).toInt()
        topMargin = (2 * density).toInt()
        bottomMargin = (2 * density).toInt()
    }
}
waveformContainer.addView(waveformView)
```

#### 4. PCM-Driven Audio Visualization (Preferred)

The `Visualizer` API requires `RECORD_AUDIO` permission and fails silently on API 34+. Use **PCM-driven waveform** instead, which reads audio data directly from WAV resources:

```kotlin
// 1. Load PCM samples from WAV resource (skip 44-byte header)
private fun loadPcmFromResource(resId: Int): ShortArray? {
    return try {
        context.resources.openRawResource(resId).use { input ->
            val allBytes = input.readBytes()
            if (allBytes.size < 46) return null
            val dataOffset = 44
            val shorts = ShortArray((allBytes.size - dataOffset) / 2)
            for (i in shorts.indices) {
                val lo = allBytes[dataOffset + i * 2].toInt() and 0xFF
                val hi = allBytes[dataOffset + i * 2 + 1].toInt()
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            shorts
        }
    } catch (e: Exception) { null }
}

// 2. Track playback position at 30 FPS via Handler
private fun startPcmTracking() {
    waveformView.setUsingRealAudio(true)
    pcmTrackingRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            val pcm = pcmShorts ?: return
            if (!mp.isPlaying) return
            
            val posMs = mp.currentPosition
            val samplePos = (posMs.toLong() * 24000 / 1000).toInt()
                .coerceIn(0, pcm.size - 1)
            val windowSize = 24000 / 30  // ~800 samples per frame
            val windowStart = samplePos.coerceIn(0, (pcm.size - windowSize).coerceAtLeast(0))
            
            // Build 256-byte waveform display buffer
            val displaySize = 256
            val waveform = ByteArray(displaySize)
            val step = maxOf(1, windowSize / displaySize)
            for (i in 0 until displaySize) {
                val idx = (windowStart + i * step).coerceIn(0, pcm.size - 1)
                waveform[i] = ((pcm[idx].toInt() / 256) + 128).coerceIn(0, 255).toByte()
            }
            waveformView.updateWaveform(waveform)
            
            // Build 32-band frequency magnitudes for FFT
            val numBands = 32
            val fft = ByteArray(numBands * 2)
            val samplesPerBand = maxOf(1, windowSize / numBands)
            for (b in 0 until numBands) {
                val bStart = windowStart + b * samplesPerBand
                var sum = 0L
                for (j in 0 until samplesPerBand) {
                    val idx = (bStart + j).coerceIn(0, pcm.size - 1)
                    sum += kotlin.math.abs(pcm[idx].toInt())
                }
                val avg = (sum / samplesPerBand).toInt()
                fft[b * 2] = (avg * 127 / 32768).coerceIn(0, 127).toByte()
                fft[b * 2 + 1] = 0
            }
            waveformView.updateFft(fft)
            
            handler.postDelayed(this, 33)  // 30 FPS
        }
    }
    handler.post(pcmTrackingRunnable!!)
}
```

**Why PCM over Visualizer:**
- No permissions required (reads raw resource bytes)
- Works on all API levels (no API 34 compatibility issues)
- Perfectly synchronized with playback position
- WAV files are mono, 24kHz, 16-bit PCM with 44-byte headers

**Fallback:** If WAV resource is missing or fails to load, use simulated waveform:
```kotlin
waveformView.setUsingRealAudio(false)
// Generate random sine wave animation
```

### Button Styling

```kotlin
private fun createButtonBackground(density: Float, isPrimary: Boolean): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20 * density  // Pill-shaped
        if (isPrimary) {
            setStroke((1 * density).toInt(), Color.parseColor("#00E5FF"))  // Cyan border
            setColor(Color.parseColor("#1A2A30"))  // Subtle cyan tint
        } else {
            setStroke((1 * density).toInt(), Color.parseColor("#3A3A3E"))
            setColor(Color.parseColor("#1A1A1E"))
        }
    }
}
```

### Modal Title Colors by Type

| Modal Type | Icon | Title Color |
|------------|------|-------------|
| Warning | ⚠️ | `#FFD600` (pro_yellow) |
| Error/Alert | 🚨 | `#FF5252` (pro_red) |
| Info/Welcome | 💡 | `#00E5FF` (pro_cyan) |
| Success | ✅ | `#69F0AE` (pro_green) |

### Complete Modal Checklist

When creating a new modal dialog:

- [ ] Extend `Dialog` with `R.style.Theme_RadiaCode_Dialog_FullScreen`
- [ ] Add blur effect (`setBackgroundBlurRadius(25)`) for Android 12+
- [ ] Add dim effect (`setDimAmount(0.7f)`)
- [ ] Use `#1A1A1E` card background with `#2A2A2E` border
- [ ] Include `WaveformVisualizerView` if Vega speaks
- [ ] Use PCM-driven waveform tracking (not Visualizer API)
- [ ] Pre-bake audio file if content is static
- [ ] Right-align buttons (Cancel left, Primary right)
- [ ] Use appropriate title color for modal type
- [ ] Clean up MediaPlayer and animations in `onStop()`
- [ ] Prevent dismiss on outside touch (`setCanceledOnTouchOutside(false)`)

### Reference Implementations

- **Full-screen intro:** `VegaIntroDialog.kt` (scrolling text, ambient audio, 140dp waveform)
- **Feature info modal:** `VegaFeatureInfoDialog.kt` (PCM-driven waveform, scrolling text, pre-baked audio)
- **Warning modal:** `VegaGpsWarningDialog.kt` (compact, blur, 80dp waveform)

---

## Dose Unit System

The app supports two dose rate display units: microsieverts per hour (uSv/h) and nanosieverts per hour (nSv/h). **All display layers must respect the user's preference.**

### Preference API

```kotlin
// Check current mode
Prefs.isDoseNanoMode(context)        // true = nSv/h, false = uSv/h
Prefs.getDoseUnit(context)           // DoseUnit.USV_H or DoseUnit.NSV_H

// Conversion: stored values are ALWAYS raw uSv/h
// Display: multiply by 1000 when nano mode is active
val displayValue = if (Prefs.isDoseNanoMode(ctx)) rawUsvH * 1000f else rawUsvH
val unitLabel = if (Prefs.isDoseNanoMode(ctx)) "nSv/h" else "\u00B5Sv/h"
```

### Where Unit Conversion Must Be Applied

Every screen that shows dose rate values MUST check `isDoseNanoMode`:

| Component | File | Status |
|-----------|------|--------|
| Dashboard MetricCardView | `DashboardFragment.kt` | Done |
| Dashboard chart titles | `DashboardFragment.kt` | Done |
| Dashboard stat rows | `DashboardFragment.kt` | Done |
| Map live dose badge | `MapCardView.kt` | Done |
| Map scale bar | `MapCardView.kt` (ScaleBarView) | Done |
| Map hexagon details dialog | `MapCardView.kt` | Done |
| Fullscreen map stats panel | `FullscreenMapActivity.kt` | Done |
| Fullscreen map hex details | `FullscreenMapActivity.kt` | Done |
| Session list / adapter | `SessionListActivity.kt` | Done |
| Session details dialog | `SessionListActivity.kt` | Done |
| Session comparison | `SessionListActivity.kt` | Done |
| Widgets | `WidgetRenderer.kt` | Done |

**Rule:** Data is always stored in raw uSv/h. Conversion happens at the display layer only, never in storage or broadcast.

### Count Unit System

Similar pattern exists for count rate:
```kotlin
Prefs.isCountCpmMode(context)  // true = CPM, false = CPS
// CPM = CPS * 60
```

---

## Session Management

### Session Auto-Start

Sessions auto-start when the first BLE reading arrives. The `sessionAutoStarted` flag in `MainActivity` prevents duplicate phantom sessions:

```kotlin
private var sessionAutoStarted = false

// In readingReceiver (called on every BLE reading):
if (!sessionAutoStarted && !SessionManager.hasActiveSession(ctx)) {
    SessionManager.startSession(ctx, deviceId)
    sessionAutoStarted = true
}
SessionManager.addDataPoint(ctx, uSvH, cps, lat, lng, deviceId)
```

**Key rules:**
- Set `sessionAutoStarted = false` only in `onCreate()` (not `onResume`)
- Auto-stop session in `onDestroy()` (not `onPause`, which fires on rotation)
- Never call `pm clear` during development -- it resets all SharedPreferences including sessions

### Live Session List Refresh

`SessionListActivity` uses a `Handler` to refresh every 3 seconds while any session is active:

```kotlin
private val refreshHandler = Handler(Looper.getMainLooper())
private val refreshRunnable = object : Runnable {
    override fun run() {
        loadSessions()
        adapter.notifyDataSetChanged()
        // Only keep refreshing if there's an active session
        if (sessions.any { it.isActive }) {
            refreshHandler.postDelayed(this, 3000L)
        }
    }
}

// Start in onResume, stop in onPause
override fun onResume() {
    super.onResume()
    loadSessions()
    if (sessions.any { it.isActive }) {
        refreshHandler.postDelayed(refreshRunnable, 3000L)
    }
}

override fun onPause() {
    super.onPause()
    refreshHandler.removeCallbacks(refreshRunnable)
}
```

---

## Chart System

### ProChartView Features

`ProChartView` is the time-series chart component used for dose rate and count rate. Features:
- Gradient fill under line
- Auto-scaled Y-axis
- Time-labeled X-axis
- Threshold lines (dashed)
- Peak markers
- Delta spike markers with percentage labels
- Rolling average line
- Bollinger bands
- Forecast bands
- Pinch-to-zoom and pan
- Sticky tap markers

### Trend Arrows (MetricCardView + Chart Panels)

Trend arrows appear in two places:
1. **MetricCardView** (DELTA DOSE RATE / DELTA COUNT RATE cards) -- built-in rendering
2. **Chart title bars** (REAL TIME DOSE RATE / REAL TIME COUNT RATE) -- separate TextViews

Both use the same z-score logic:

```kotlin
// absZ = absolute z-score (how many std devs from mean)
val (arrow, color) = when {
    absZ > 2f && zScore > 0 -> "\u25B2\u25B2" to pro_green    // Very high (>2 sigma)
    absZ > 1f && zScore > 0 -> "\u25B2" to pro_green           // High (>1 sigma)
    absZ > 2f && zScore < 0 -> "\u25BC\u25BC" to pro_red       // Very low (<-2 sigma)
    absZ > 1f && zScore < 0 -> "\u25BC" to pro_red             // Low (<-1 sigma)
    else -> "\u2500" to pro_text_muted                          // Stable (within 1 sigma)
}
// Shows percentage when |trend| >= 0.1f
```

Controlled by `Prefs.isShowTrendArrowsEnabled()` (default: ON).

### Chart Trend Arrow XML

Each chart panel has a `TextView` for the trend arrow in the title bar:
```xml
<TextView
    android:id="@+id/doseChartTrend"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginEnd="8dp"
    android:textSize="13sp"
    android:fontFamily="monospace"
    android:textStyle="bold"
    android:visibility="gone" />
```

---

## Hexagon Map System

### Architecture

| Component | File | Purpose |
|-----------|------|---------|
| MapFragment | `MapFragment.kt` | Tab UI, GPS tier selector, export, sessions button |
| MapCardView | `MapCardView.kt` | Core map rendering, hexagon overlays, scale bar |
| HexGrid | `HexGrid.kt` | Pure math: lat/lng to axial hex coords (flat-top, 25m cells) |
| FullscreenMapActivity | `FullscreenMapActivity.kt` | Landscape fullscreen map with stats panel |
| ScaleBarView | `MapCardView.kt` (inner class) | Color scale bar with min/max labels |

### Data Flow

1. `MapFragment.addReading(uSvH, cps)` called by `MainActivity` on every sensor reading
2. `MapCardView.addReading()` converts lat/lng to hex cell ID via `HexGrid`
3. Readings stored in `hexagonData` map keyed by hex cell ID
4. `HexagonOverlay` draws filled hexagons with green-yellow-red interpolation
5. `HexagonTapOverlay` detects taps and shows statistics dialog

### Unit Awareness

All map display components respect `Prefs.isDoseNanoMode()`:
- **Live dose badge**: `updateLiveDoseRate()` converts value and updates unit label
- **Scale bar**: `ScaleBarView.formatValue()` converts min/max labels
- **Hex details dialog**: Local `fmtDose()` helper converts all values
- **Fullscreen stats panel**: `updateStatisticsPanel()` formats with units

---