# Agent Operating Instructions

This document provides context and rules for AI coding agents working in this monorepo.

---

## Repository Overview

This monorepo contains three interconnected projects for radiation detection and isotope identification:

| Folder | Purpose | Tech Stack |
|--------|---------|------------|
| `android_app/` | Mobile app for RadiaCode BLE devices | Kotlin, Android, Gradle |
| `middleware/` | AI microservices (TTS, LLM, Isotope ID) | Python, FastAPI, Docker, PyTorch |
| `vega_ml/` | ML training pipeline for isotope identification | Python, PyTorch, NumPy |

### Data Flow

```
RadiaCode Device
      │ BLE
      ▼
┌─────────────┐      HTTP/JSON       ┌────────────────────┐
│ Android App │ ──────────────────▶  │ Middleware (Vega)  │
│ (Kotlin)    │ ◀──────────────────  │ - Isotope API      │
└─────────────┘      predictions     │ - LLM API          │
                                     │ - TTS API          │
                                     └────────────────────┘
                                              ▲
                                              │ model files
                                     ┌────────┴───────────┐
                                     │ vega_ml (Training) │
                                     │ - Synthetic data   │
                                     │ - CNN model        │
                                     └────────────────────┘
```

---

## Per-Project Agent Files

Each subfolder has its own agent documentation with project-specific rules:

- **Android App:** [android_app/AGENTS.md](android_app/AGENTS.md) — build/deploy loop, versioning, ADB commands
- **Middleware:** [middleware/agent.md](middleware/agent.md) — Docker deployment, API patterns, GPU services
- **ML Training:** [vega_ml/agents.md](vega_ml/agents.md) — synthetic data generation, model architecture, training

**Always read the relevant subfolder's agent file before making changes there.**

---

## General Rules (All Projects)

### 1. No Emojis
Emojis are prohibited in all user-facing text, UI, notifications, and code comments across the entire repo.

### 2. Git Workflow
- Work on feature branches: `git checkout -b feature/short-description`
- Make atomic commits with clear messages
- Push feature branches to origin
- **Never merge to `main` unless explicitly instructed**

### 3. Secrets & Environment Files
- `.env` files contain secrets — **never commit them**
- Use `.env.example` as templates
- SSH keys, API tokens, and credentials stay local

### 4. Testing Before Commit
- **Android:** Build succeeds, app installs and runs on device
- **Middleware:** Docker containers build and start, health endpoints respond
- **ML:** Training script runs without errors, inference produces valid output

---

## Project-Specific Quick Reference

### Android App (`android_app/`)

```powershell
cd android_app

# Build
./gradlew assembleDebug

# Install to phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Restart app (install does NOT restart)
adb shell am force-stop com.radiacode.ble
adb shell monkey -p com.radiacode.ble -c android.intent.category.LAUNCHER 1

# View logs
adb logcat -v time -s RadiaCode
```

**Key files:**
- `app/build.gradle.kts` — version, dependencies
- `app/src/main/` — Kotlin source code
- `Installer/` — distributable APKs

### Middleware (`middleware/`)

```powershell
cd middleware/vega-isotope-identification  # or vega-tts, vega-llm

# Deploy to server
.\deploy.ps1

# Test locally
python client_sample.py
```

**Services:**
| Service | Port | Description |
|---------|------|-------------|
| vega-tts | 8000 | Text-to-Speech (Chatterbox) |
| vega-llm | 8001 | Chat/LLM (Qwen) |
| vega-isotope-identification | 8020 | Isotope ID (CNN model) |
| vega-ingress | 8080 | API gateway with logging |

### ML Training (`vega_ml/`)

```powershell
cd vega_ml

# Generate synthetic training data
python -m synthetic_spectra.generate_spectra

# Train model
python training/vega/run_training.py --epochs 100

# Run inference
python inference/run_inference.py --model models/vega_best.pt
```

**Key directories:**
- `synthetic_spectra/` — data generation code
- `training/vega/` — model architecture and training loop
- `models/` — saved checkpoints
- `data/synthetic/` — generated training data (git-ignored)

---

## Hardware Requirements

| Component | Requirement |
|-----------|-------------|
| Android Device | Android 8.0+, Bluetooth LE |
| RadiaCode | 101, 102, 103, 103G, or 110 |
| Training GPU | NVIDIA RTX 3090/4090/5090 (24GB VRAM) |
| Inference Server | Ubuntu 22.04, Docker, NVIDIA Container Toolkit |

---

## Common Tasks

### Add a new isotope to the system
1. Add gamma lines to `vega_ml/synthetic_spectra/ground_truth/isotope_data.py`
2. Regenerate training data
3. Retrain model
4. Deploy updated model to middleware
5. Update Android app isotope list if needed

### Deploy updated model
1. Train in `vega_ml/`, checkpoint saved to `models/`
2. Copy model to `middleware/vega-isotope-identification/models/`
3. Run `deploy.ps1` to push to server

### Release new Android version
1. Increment version in `android_app/app/build.gradle.kts`
2. Build and test on device
3. Copy APK to `android_app/Installer/`
4. Commit, push, and (if instructed) merge to main

---

## Stop Conditions

Pause and ask the user only if:
1. **Credentials/secrets needed** that you don't have access to
2. **Production/billing impact** — actions affecting live services
3. **Destructive operations** — force push, mass deletes
4. **Breaking API changes** — modifications to public interfaces
5. **Ambiguous requirements** — unclear what the user wants

Otherwise, keep iterating until the task is complete.
