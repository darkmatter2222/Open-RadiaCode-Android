# Copilot Instructions

Rules and conventions for GitHub Copilot when working in this repository.

---

## Repository Structure

This is a monorepo with three projects:

| Folder | Purpose | Stack |
|--------|---------|-------|
| `android_app/` | RadiaCode BLE mobile app | Kotlin, Android, Gradle |
| `middleware/` | AI microservices (TTS, LLM, Isotope ID) | Python, FastAPI, Docker |
| `vega_ml/` | ML training pipeline | Python, PyTorch |

Detailed per-project agent files:
- `android_app/AGENTS.md` — Android build/deploy loop, UI patterns, architecture
- `middleware/agent.md` — Docker deployment, API patterns
- `vega_ml/agents.md` — Synthetic data, model training

---

## Universal Rules

### No Emojis
Emojis are prohibited in all user-facing text, UI strings, notifications, Toast messages, dialog text, and code comments. Use plain text and standard Unicode symbols (arrows, dashes, bullets) instead.

### Git Workflow
- Work on feature branches (`feature/short-description`)
- Atomic commits with clear messages
- Always push after changes
- Never merge to `main` unless explicitly instructed
- Never commit `.env` files or secrets

---

## Android App Conventions (`android_app/`)

### Build & Deploy

```powershell
cd android_app
.\gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.radiacode.ble
adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1
```

**Never run `adb shell pm clear`** — it wipes SharedPreferences (sessions, settings, calibration data).

### Dose Unit System

The app supports two dose rate units: uSv/h (microsieverts) and nSv/h (nanosieverts).

```kotlin
// Check preference
Prefs.isDoseNanoMode(context)  // true = nSv/h

// Convert for display (raw data is always uSv/h)
val display = if (Prefs.isDoseNanoMode(ctx)) raw * 1000f else raw
val unit = if (Prefs.isDoseNanoMode(ctx)) "nSv/h" else "\u00B5Sv/h"
```

**Rule:** All screens that show dose rate values MUST respect this preference. Data is stored in raw uSv/h; conversion happens only at the display layer.

Affected components: Dashboard, SessionListActivity, MapCardView, FullscreenMapActivity, Widgets.

### Count Unit System

```kotlin
Prefs.isCountCpmMode(context)  // true = CPM, false = CPS
// CPM = CPS * 60
```

### Audio / Waveform

Use PCM-driven waveform rendering, NOT the Android Visualizer API:
- Load WAV file bytes, skip 44-byte header, convert to `ShortArray`
- Track `MediaPlayer.currentPosition` at 30 FPS to render waveform slice
- See `VegaFeatureInfoDialog.kt` for the reference implementation

### Session Management

- Sessions auto-start on first BLE reading (`sessionAutoStarted` flag prevents duplicates)
- `SessionManager.addDataPoint()` stores readings as CSV
- `SessionListActivity` refreshes every 3 seconds while a session is active
- Auto-stop session in `onDestroy()` (not `onPause`, which fires on rotation)

### Design System

- Dark theme: background `#0D0D0D`, cards `#1A1A1A`, primary green `#00E676`
- Monospace font (`monospace`) for all numeric readouts
- Chart colors: green `#00E676`, red `#FF1744`, amber `#FFD740`, muted text `#808080`
- Trend arrows use z-score significance: >1 sigma = single arrow, >2 sigma = double arrow
- Show percentage when |trend| >= 0.1%

### Hexagon Map

- `HexGrid.kt`: pure-math lat/lng to axial hex coords (flat-top, 25m cells)
- `MapCardView.kt`: rendering, overlays, scale bar, live dose badge
- `FullscreenMapActivity.kt`: landscape fullscreen with stats panel
- All map components respect `Prefs.isDoseNanoMode()` for unit display

### Modal Dialogs (Vega AI)

Vega AI voice modals follow a strict pattern:
1. Full-bleed dialog (transparent background, rounded card)
2. Blur overlay behind content
3. Pre-baked WAV audio (mono, 24kHz, 16-bit) played via `MediaPlayer`
4. PCM waveform visualization synced to playback position
5. Auto-scrolling text content
6. Clean up MediaPlayer and animations in `onStop()`

---

## Middleware Conventions (`middleware/`)

### Deployment

```powershell
cd middleware/<service-name>
.\deploy.ps1
```

### Services

| Service | Port | Purpose |
|---------|------|---------|
| vega-tts | 8000 | Text-to-Speech (Chatterbox) |
| vega-llm | 8001 | Chat/LLM (Qwen) |
| vega-isotope-identification | 8020 | Isotope CNN model |
| vega-ingress | 8080 | API gateway with logging |

### API Patterns
- All endpoints accept/return JSON
- Health check: `GET /health`
- GPU services need NVIDIA Container Toolkit

---

## ML Training Conventions (`vega_ml/`)

### Data Pipeline

```powershell
cd vega_ml
python -m synthetic_spectra.generate_spectra  # Generate data
python training/vega/run_training.py --epochs 100  # Train
python inference/run_inference.py --model models/vega_best.pt  # Infer
```

### Key Rules
- Isotope energies defined in `synthetic_spectra/ground_truth/isotope_data.py`
- Synthetic data goes to `data/synthetic/` (git-ignored)
- Models saved to `models/` directory
- Requires NVIDIA GPU with 24GB VRAM for training

---

## Testing Requirements

Before committing any change:
- **Android:** Build succeeds (`gradlew assembleDebug`), app installs and runs
- **Middleware:** Docker containers build and start, health endpoints respond
- **ML:** Training script completes without errors, inference produces valid output
