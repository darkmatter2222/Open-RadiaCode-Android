# ML for Isotope Identification

A machine learning system for identifying radioactive isotopes from gamma-ray spectra captured by Radiacode scintillation detectors.

## Project Status

✅ **Completed:** Synthetic gamma spectra generation system  
✅ **Completed:** Vega ML model architecture (CNN-FCNN hybrid)  
✅ **Completed:** Training pipeline with GPU support  
✅ **Completed:** Inference engine  
🔲 **Next:** Generate large training dataset (10,000-100,000 samples)  
🔲 **Future:** Real-time inference on Radiacode devices

---

## Overview

This project aims to build a neural network that can identify radioactive isotopes from gamma spectra. Since collecting real gamma spectra requires radioactive sources and is expensive/regulated, we generate **synthetic training data** based on realistic physics models.

### Target Hardware
- **Training:** NVIDIA RTX 5090 GPU (requires PyTorch nightly with CUDA 12.8)
- **Inference:** Radiacode 101, 102, 103, 103G, 110 scintillation detectors

### Data Format
- **Input:** 2D spectrograms (time intervals × 1023 energy channels)
- **Output:** Multi-label isotope classification with activity estimation

---

## Quick Start

### Installation

```bash
# Create virtual environment
python -m venv .venv
.venv\Scripts\activate  # Windows
# or: source .venv/bin/activate  # Linux/Mac

# Install dependencies
pip install numpy scipy pillow

# Install PyTorch (nightly for RTX 5090/Blackwell support)
pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu128
```

### Generate Synthetic Data

```bash
# Generate 10 test samples
python -m synthetic_spectra.generate_spectra
```

### Train the Model

```bash
# Quick test run (5 epochs, small dataset)
python training/vega/run_training.py --test

# Full training
python training/vega/run_training.py --epochs 100 --batch-size 32
```

### Run Inference

```bash
# Run inference on synthetic data
python inference/run_inference.py --model models/vega_best.pt --data data/synthetic
```

---

## Vega Model Architecture

**Vega** is a CNN-FCNN hybrid model optimized for gamma spectrum isotope identification, based on research showing 99%+ accuracy on similar tasks.

### Architecture Details
| Component | Configuration |
|-----------|---------------|
| Input | 1023 energy channels |
| CNN Backbone | 3 ConvBlocks [64, 128, 256 channels] |
| Kernel Size | 7 (captures spectral features) |
| FC Layers | [512, 256] with dropout |
| Output Heads | Dual: Classification (82 isotopes) + Regression (activity) |
| Total Parameters | 34.5M |
| Activation | LeakyReLU + BatchNorm |

### Training Features
- **Mixed Precision (AMP):** Faster training on modern GPUs
- **Multi-task Learning:** Simultaneous isotope ID + activity estimation
- **Loss Function:** BCE (classification) + Huber (regression)
- **LR Scheduling:** ReduceLROnPlateau with early stopping

---

## Synthetic Spectra Generation

### Features
- **82 isotopes** with accurate gamma emission lines
- **Realistic physics:** Gaussian peaks, Poisson noise, Compton continuum, environmental background
- **Multiple detector models:** Radiacode 101, 102, 103, 103G, 110 with correct FWHM and energy ranges
- **Configurable variation:** Activity levels, measurement durations, isotope combinations

### Sample Distribution
| Type | Proportion | Description |
|------|------------|-------------|
| Single isotope | 40% | One source + background |
| Dual isotope | 30% | Two sources blended |
| Multi isotope | 20% | 3-5 sources combined |
| Background only | 10% | Environmental only |

### Scaling Up
Edit `synthetic_spectra/generate_spectra.py` to generate larger datasets:
```python
generate_training_batch(
    n_samples=100000,  # Generate 100k samples
    output_dir=Path("data/synthetic/spectra"),
    detector_type="radiacode_103"
)
```

---

## Project Structure

```
ml-for-isotope-identification/
├── README.md                    # This file
├── agents.md                    # AI agent context documentation
├── .gitignore                   # Git ignore rules
│
├── synthetic_spectra/           # Spectrum generation package
│   ├── __init__.py
│   ├── config.py                # Detector configurations
│   ├── generator.py             # Main generation logic
│   ├── generate_spectra.py      # CLI batch generation
│   ├── ground_truth/
│   │   ├── isotope_data.py      # 82 isotopes database
│   │   └── decay_chains.py      # Decay chain definitions
│   └── physics/
│       └── spectrum_physics.py  # Physics calculations
│
├── training/                    # Training infrastructure
│   └── vega/                    # Vega model package
│       ├── __init__.py
│       ├── isotope_index.py     # Isotope ↔ index mapping
│       ├── model.py             # VegaModel architecture
│       ├── dataset.py           # PyTorch Dataset/DataLoader
│       ├── train.py             # Training loop & utilities
│       └── run_training.py      # CLI training script
│
├── inference/                   # Inference engine
│   ├── vega_inference.py        # VegaInference class
│   └── run_inference.py         # CLI inference script
│
├── models/                      # Saved model checkpoints
│   ├── vega_best.pt             # Best validation loss
│   ├── vega_final.pt            # Final epoch
│   └── vega_history.json        # Training metrics
│
└── data/                        # Generated data (git-ignored)
    └── synthetic/
        └── spectra/
```

---

## Technical Details

### Detector Specifications
| Model | Crystal | FWHM @ 662 keV | Energy Range | Channels |
|-------|---------|----------------|--------------|----------|
| Radiacode 101 | CsI(Tl) | 9.0% | 20-3000 keV | 1024 |
| Radiacode 102 | CsI(Tl) | 9.5% | 20-3000 keV | 1024 |
| Radiacode 103 | CsI(Tl) | 8.4% | 20-3000 keV | 1024 |
| Radiacode 103G | GAGG(Ce) | 7.4% | 20-3000 keV | 1024 |
| Radiacode 110 | CsI(Tl) | 8.4% | 20-3000 keV | 1024 |

### Physics Model
- **Peak shape:** Gaussian with FWHM scaling as √(E/662)
- **Expected counts:** λ = A × t × I × ε × T
- **Noise:** Poisson counting statistics
- **Background:** Exponential continuum + environmental isotopes (K-40, Pb-214, Bi-214, etc.)

### Isotope Categories
- Natural background (K-40, Ra-226, Rn-222)
- Decay chains (U-238, Th-232, U-235)
- Calibration sources (Am-241, Cs-137, Co-60, Ba-133, Eu-152)
- Medical isotopes (Tc-99m, F-18, I-131, Ga-68)
- Industrial sources (Ir-192, Se-75)
- Reactor fallout (Cs-134, Cs-137, Sr-90)

---

## Development

### Dependencies
```
numpy>=1.24.0
scipy>=1.10.0
pillow>=9.0.0
torch>=2.11.0 (nightly with CUDA 12.8 for RTX 5090)
```

### GPU Support
The RTX 5090 (Blackwell architecture, sm_120) requires PyTorch nightly builds with CUDA 12.8:
```bash
pip install --pre torch --index-url https://download.pytorch.org/whl/nightly/cu128
```

### For AI Agents
See [agents.md](agents.md) for comprehensive documentation on:
- System architecture and design decisions
- Physics model implementation details
- Vega model architecture and training
- Configuration options and variation strategies

---

## TODO

- [x] ~~Push to repository~~ - Initial commit with generation system
- [x] ~~Create PyTorch DataLoader for training~~
- [x] ~~Implement CNN-FCNN model architecture (Vega)~~
- [x] ~~Create training script with logging~~
- [x] ~~Implement inference module~~
- [ ] Generate large training dataset (100k samples)
- [ ] Train model to convergence
- [ ] Add data augmentation pipeline
- [ ] Add model evaluation metrics & confusion matrix
- [ ] Implement real-time inference module
- [ ] Create Radiacode device integration

---

## License

[TBD]

---

## Acknowledgments

- Radiacode for device specifications
- IAEA Nuclear Data Services for isotope data
- NNDC at Brookhaven National Laboratory
- Wang et al. research on CNN-FCNN for gamma spectroscopy