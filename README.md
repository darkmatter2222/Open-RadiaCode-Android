# RadiaCode Data Collection & Isotope Identification

A complete system for radiation detection, data collection, and AI-powered isotope identification using RadiaCode scintillation detectors.

## Projects

This monorepo contains three interconnected applications:

### 1. Open RadiaCode Android App (`android_app/`)

A Kotlin Android app that connects to RadiaCode devices over Bluetooth LE.

**Features:**
- Real-time dose rate and count rate monitoring
- Live charts with zoom, pan, and statistical analysis
- Home screen widgets with customizable themes
- Smart alerts with threshold and statistical triggers
- Live GPS mapping of radiation readings
- Real-time isotope identification via backend API
- Background service with auto-connect on boot

**[Full documentation](android_app/README.md)**

### 2. Vega Middleware (`middleware/`)

A suite of AI microservices deployed on GPU servers via Docker.

| Service | Port | Description |
|---------|------|-------------|
| **vega-tts** | 8000 | Text-to-Speech with voice cloning (Chatterbox TTS) |
| **vega-llm** | 8001 | Chat and text generation (Qwen) |
| **vega-isotope-identification** | 8020 | CNN-based isotope identification |
| **vega-ingress** | 8080 | API gateway with request logging |

**[Full documentation](middleware/README.md)**

### 3. Vega ML Training (`vega_ml/`)

Machine learning pipeline for training isotope identification models.

**Components:**
- Synthetic gamma spectra generator (82 isotopes)
- Physics-based simulation (Gaussian peaks, Poisson noise, Compton continuum)
- CNN-FCNN hybrid model (VegaModel, 34.5M parameters)
- Training pipeline with mixed precision and multi-task learning

**[Full documentation](vega_ml/README.md)**

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User's Phone                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Open RadiaCode Android App                  │   │
│  │  - Dashboard with live charts                           │   │
│  │  - Widgets, alerts, GPS mapping                         │   │
│  │  - Isotope identification UI                            │   │
│  └─────────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────────┘
                           │ BLE                    │ HTTPS
                           ▼                        ▼
                    ┌─────────────┐         ┌──────────────────┐
                    │  RadiaCode  │         │  Vega Middleware │
                    │   Device    │         │  (GPU Server)    │
                    │  (101-110)  │         │                  │
                    └─────────────┘         │  ┌────────────┐  │
                                            │  │ Isotope ID │  │
                                            │  │   (CNN)    │  │
                                            │  └────────────┘  │
                                            │  ┌────────────┐  │
                                            │  │    LLM     │  │
                                            │  └────────────┘  │
                                            │  ┌────────────┐  │
                                            │  │    TTS     │  │
                                            │  └────────────┘  │
                                            └──────────────────┘
                                                     ▲
                                                     │ model files
                                            ┌────────┴─────────┐
                                            │    Vega ML       │
                                            │  Training        │
                                            │  (RTX 5090)      │
                                            └──────────────────┘
```

## Quick Start

### Android App

1. Download the latest APK from [`android_app/Installer/`](android_app/Installer/)
2. Enable "Install from unknown sources" on your Android device
3. Install and grant Bluetooth/Location permissions
4. Pair with your RadiaCode device

### Middleware (Server Deployment)

```powershell
# Deploy isotope identification service
cd middleware/vega-isotope-identification
cp .env.example .env  # Edit with your server details
.\deploy.ps1

# Deploy TTS service
cd ../vega-tts
cp .env.example .env
.\deploy.ps1
```

### ML Training

```bash
cd vega_ml

# Create virtual environment
python -m venv .venv
.venv\Scripts\activate  # Windows

# Install dependencies
pip install numpy scipy pillow
pip install --pre torch --index-url https://download.pytorch.org/whl/nightly/cu128

# Generate training data
python -m synthetic_spectra.generate_spectra

# Train model
python training/vega/run_training.py --epochs 100
```

## Supported Hardware

### RadiaCode Devices
| Model | Crystal | FWHM @ 662 keV | Status |
|-------|---------|----------------|--------|
| RadiaCode 101 | CsI(Tl) | 9.0% | Supported |
| RadiaCode 102 | CsI(Tl) | 9.5% | Supported |
| RadiaCode 103 | CsI(Tl) | 8.4% | Supported |
| RadiaCode 103G | GAGG(Ce) | 7.4% | Supported |
| RadiaCode 110 | CsI(Tl) | 8.4% | Primary |

### GPU Requirements
- **Training:** NVIDIA RTX 3090/4090/5090 (24GB VRAM recommended)
- **Inference:** RTX 3090 or equivalent for real-time isotope ID

## Isotopes Supported

The system can identify 82 isotopes across categories:

- **Natural Background:** K-40, Ra-226, Rn-222, Th-232, U-238
- **Medical:** Tc-99m, F-18, I-131, Ga-68, Lu-177
- **Industrial:** Ir-192, Se-75, Am-241, Cs-137, Co-60
- **Calibration:** Ba-133, Eu-152, Na-22, Mn-54, Co-57
- **Reactor Fallout:** Cs-134, Sr-90, Zr-95, Ru-106

## Development

### Prerequisites
- Android Studio (for Android app)
- Python 3.10+ (for ML and middleware)
- Docker with NVIDIA Container Toolkit (for deployment)
- ADB (for device testing)

### Project Structure
```
RadiaCodeAndroidDataCollection/
├── android_app/          # Kotlin Android application
│   ├── app/src/          # Source code
│   ├── Installer/        # Distributable APKs
│   └── AGENTS.md         # Android-specific agent instructions
│
├── middleware/           # AI microservices
│   ├── vega-tts/         # Text-to-Speech service
│   ├── vega-llm/         # LLM chat service
│   ├── vega-isotope-identification/  # CNN inference service
│   ├── vega-ingress/     # API gateway
│   └── agent.md          # Middleware agent instructions
│
├── vega_ml/              # ML training pipeline
│   ├── synthetic_spectra/  # Data generation
│   ├── training/         # Model and training code
│   ├── inference/        # Inference engine
│   ├── models/           # Saved checkpoints
│   └── agents.md         # ML agent instructions
│
├── AGENTS.md             # Root agent instructions
└── README.md             # This file
```

### Agent Instructions

For AI coding agents, see [AGENTS.md](AGENTS.md) for operating rules and per-project documentation.

## License

See individual project folders for license information.
