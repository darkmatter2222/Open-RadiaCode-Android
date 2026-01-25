# Vega 2D Isotope Identification System - Complete Technical Guide

**Version:** 2.0 (2D Model)  
**Last Updated:** January 2025  
**Architecture:** 2D-CNN with Temporal Feature Extraction

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Architecture Overview](#2-system-architecture-overview)
3. [Data Format Specification](#3-data-format-specification)
4. [Synthetic Data Generation](#4-synthetic-data-generation)
5. [Model Architecture](#5-model-architecture)
6. [Training Procedures](#6-training-procedures)
7. [Inference System](#7-inference-system)
8. [Output Interpretation](#8-output-interpretation)
9. [Isotope Reference](#9-isotope-reference)
10. [Decay Chain Analysis](#10-decay-chain-analysis)
11. [Threshold Selection Guide](#11-threshold-selection-guide)
12. [Example Workflows](#12-example-workflows)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Executive Summary

### What This System Does

The Vega 2D system identifies **radioactive isotopes** from gamma-ray spectra captured by Radiacode scintillation detectors. Given a spectrum measurement, it outputs:

1. **Presence predictions** - Which of 82 isotopes are present (with probability 0-1)
2. **Activity estimates** - Estimated radioactivity in Becquerels (Bq) for each detected isotope

### Why 2D?

Unlike traditional 1D approaches that collapse temporal data, the Vega 2D model treats spectra as **images** with:
- **Y-axis:** 60 time intervals (1 second each)
- **X-axis:** 1023 energy channels (20 keV - 3000 keV)

This preserves crucial temporal information:
- **Decay patterns** - Short-lived isotopes show decreasing counts over time
- **Activity fluctuations** - Real sources have statistical variations
- **Noise characteristics** - Poisson statistics create time-varying patterns
- **Equilibrium dynamics** - Daughter isotope ingrowth over time

### Key Specifications

| Parameter | Value |
|-----------|-------|
| Input Shape | `(60, 1023)` - 60 time intervals × 1023 channels |
| Output Classes | 82 isotopes |
| Model Parameters | ~59 million |
| Inference Time | <100ms on GPU, ~500ms on CPU |
| Typical F1 Score | >96% |

---

## 2. System Architecture Overview

### Directory Structure

```
ml-for-isotope-identification/
├── synthetic_spectra/                  # Data generation
│   ├── generate_spectra_v3.py          # Main generation script
│   ├── generator.py                    # SpectrumGenerator class
│   ├── config.py                       # Detector configurations
│   └── ground_truth/
│       ├── isotope_data.py             # 82 isotope definitions
│       └── decay_chains.py             # Decay chain relationships
│
├── training/vega/                      # Training infrastructure
│   ├── model_2d.py                     # Vega2DModel architecture
│   ├── dataset_2d.py                   # 2D data loading
│   ├── train_2d.py                     # Training loop
│   └── isotope_index.py                # Isotope ↔ index mapping
│
├── inference/                          # Inference system
│   └── vega_portable_inference_2d.py   # Self-contained inference
│
├── models/                             # Saved checkpoints
│   ├── vega_2d_best.pt                 # Best validation model
│   └── vega_2d_final.pt                # Final epoch model
│
└── data/synthetic/                     # Generated training data
    └── spectra/                        # .npy spectrum files
```

### Data Flow

```
[Radiacode Detector] → [Raw Counts Array] → [Normalization] → [Vega 2D Model]
                                                                    ↓
[Results Display] ← [Activity Estimation] ← [Sigmoid(logits)] ← [Dual Heads]
```

---

## 3. Data Format Specification

### 3.1 Input Spectrum Format

**Shape:** `(num_time_intervals, 1023)` or ideally `(60, 1023)`

**Data Type:** `float32` or `float64`

**Value Range:** 
- Raw counts: integers 0 to ~thousands
- Normalized: 0.0 to 1.0 (divided by max value)

**Channel Mapping:**
```python
def channel_to_energy(channel: int) -> float:
    """Convert channel index to energy in keV."""
    E_MIN, E_MAX = 20.0, 3000.0
    return E_MIN + channel * (E_MAX - E_MIN) / 1023

def energy_to_channel(energy_kev: float) -> int:
    """Convert energy in keV to channel index."""
    E_MIN, E_MAX = 20.0, 3000.0
    channel = int((energy_kev - E_MIN) / (E_MAX - E_MIN) * 1023)
    return max(0, min(1022, channel))
```

**Example Channel Mappings:**
| Energy (keV) | Channel | Notable Isotope |
|--------------|---------|-----------------|
| 59.5 | 14 | Am-241 |
| 122.1 | 35 | Co-57 |
| 356.0 | 116 | Ba-133 |
| 661.7 | 221 | Cs-137 |
| 1173.2 | 397 | Co-60 |
| 1274.5 | 432 | Na-22 |
| 1332.5 | 452 | Co-60 |
| 1460.8 | 496 | K-40 (background) |

### 3.2 Time Dimension Handling

The model **requires exactly 60 time intervals**. Input spectra are handled as follows:

```python
def _pad_or_truncate(spectrum: np.ndarray, target_rows: int = 60) -> np.ndarray:
    """Ensure spectrum has exactly 60 rows."""
    current_rows = spectrum.shape[0]
    
    if current_rows == target_rows:
        return spectrum
    elif current_rows > target_rows:
        # Truncate - take LAST N intervals (most recent data)
        return spectrum[-target_rows:]
    else:
        # Pad with zeros at the BEGINNING
        padding = np.zeros((target_rows - current_rows, spectrum.shape[1]))
        return np.vstack([padding, spectrum])
```

**Important:** When truncating, the **most recent 60 seconds** are kept (last rows), not the first.

### 3.3 Normalization

Before inference, spectra should be normalized to [0, 1]:

```python
def normalize(spectrum: np.ndarray) -> np.ndarray:
    """Normalize spectrum to [0, 1] range."""
    max_val = spectrum.max()
    if max_val > 0:
        return spectrum / max_val
    return spectrum
```

**Why normalize?**
- Neural networks work best with standardized inputs
- Prevents high-activity samples from dominating gradients
- Allows model to focus on spectral shape rather than absolute counts

---

## 4. Synthetic Data Generation

### 4.1 Overview

Training data is generated synthetically because:
1. Real radioactive sources require permits and safety protocols
2. ML requires 100,000+ samples
3. Ground truth labels are perfect with synthetic data
4. Can systematically vary all parameters

### 4.2 Generation Command

```bash
# Generate 200,000 training samples
python -m synthetic_spectra.generate_spectra_v3 \
    --num_samples 200000 \
    --output_dir "O:/master_data_collection/isotopev2" \
    --detector radiacode_103 \
    --workers 8 \
    --activity_min 1.0 \
    --activity_max 100.0
```

### 4.3 Generation Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--num_samples` | 200000 | Total samples to generate |
| `--output_dir` | data/synthetic | Output directory |
| `--detector` | radiacode_103 | Detector model to simulate |
| `--workers` | CPU_count-1 | Parallel workers |
| `--activity_min` | 1.0 | Minimum source activity (Bq) |
| `--activity_max` | 100.0 | Maximum source activity (Bq) |
| `--seed` | None | Random seed for reproducibility |

### 4.4 Sample Scenario Distribution

The v3 generator creates diverse, realistic scenarios:

| Scenario | Fraction | Description |
|----------|----------|-------------|
| `background_only` | 15% | No isotopes - just environmental background |
| `single_calibration` | 20% | One calibration source (Cs-137, Co-60, etc.) |
| `single_medical` | 8% | One medical isotope (Tc-99m, I-131, etc.) |
| `single_industrial` | 5% | One industrial source (Ir-192, Se-75, etc.) |
| `uranium_chain` | 10% | U-238 + daughters in equilibrium |
| `thorium_chain` | 10% | Th-232 + daughters in equilibrium |
| `norm` | 7% | 2-4 NORM isotopes (K-40, Ra-226, etc.) |
| `fallout` | 5% | Reactor fallout signature (Cs-137 + Cs-134) |
| `mixed` | 10% | Random 2-3 isotope combination |
| `complex_mix` | 5% | 4-6 isotopes from various categories |
| `weak_source` | 5% | Very low activity (0.1-5 Bq) |

### 4.5 Isotope Pools

```python
# Calibration sources (individual, well-characterized)
CALIBRATION_ISOTOPES = [
    "Cs-137", "Co-60", "Am-241", "Ba-133", 
    "Eu-152", "Na-22", "Co-57", "Mn-54"
]

# Medical isotopes (short-lived, hospital settings)
MEDICAL_ISOTOPES = [
    "Tc-99m", "I-131", "I-123", "F-18", 
    "Ga-67", "Ga-68", "In-111", "Lu-177", "Tl-201"
]

# Industrial sources (sealed sources, gauges)
INDUSTRIAL_ISOTOPES = [
    "Ir-192", "Se-75", "Zn-65", "Co-58", "Cd-109"
]

# Natural decay chains (always appear together)
URANIUM_238_CHAIN = ["U-238", "Ra-226", "Pb-214", "Bi-214"]
THORIUM_232_CHAIN = ["Th-232", "Ac-228", "Pb-212", "Bi-212", "Tl-208"]

# Reactor fallout signature
FALLOUT_SIGNATURE = ["Cs-137", "Cs-134"]  # Indicates reactor origin
```

### 4.6 Background Model

Every synthetic spectrum includes realistic environmental background:

1. **Exponential continuum**: `B(E) = B₀ × exp(-E / E_char)`
2. **K-40** (potassium-40): 1460.8 keV - from soil, building materials
3. **Radon progeny** (Pb-214, Bi-214): From atmospheric radon
4. **Thorium progeny** (Pb-212, Tl-208, Ac-228): From soil

Background intensity is randomized (0.3× to 3.0× baseline).

### 4.7 Physics Model

Each gamma peak is generated as:

```python
# Gaussian peak generation
FWHM = FWHM_662 * sqrt(E / 662)  # Resolution scales with energy
sigma = FWHM / 2.355

expected_counts = activity_bq * time_seconds * branching_ratio * efficiency

# Poisson noise applied to expected counts
observed_counts = np.random.poisson(expected_counts)
```

### 4.8 Output Files

Each sample generates:
- `{uuid}_spectrum.npy` - NumPy array (60, 1023)
- `{uuid}_spectrum.png` - Visualization (optional)

Plus a global `labels.json`:
```json
{
  "abc123-def456": {
    "isotopes": [
      {"name": "Cs-137", "activity_bq": 45.2, "category": "CALIBRATION"}
    ],
    "background_isotopes": ["K-40", "Pb-214", "Bi-214"],
    "detector": "radiacode_103",
    "duration_seconds": 60,
    "num_intervals": 60,
    "background_scale": 1.2,
    "generation_timestamp": "2025-01-24T12:34:56"
  }
}
```

---

## 5. Model Architecture

### 5.1 Architecture Overview

```
Vega2DModel (59M parameters)
│
├─ Input: (batch, 1, 60, 1023)  [Grayscale image representation]
│
├─ ConvBlock2D #1
│   ├─ Conv2d(1→32, kernel=(3,7), padding=(1,3))
│   ├─ BatchNorm2d(32)
│   ├─ LeakyReLU(0.01)
│   ├─ Conv2d(32→32, kernel=(3,7), padding=(1,3))
│   ├─ BatchNorm2d(32)
│   ├─ LeakyReLU(0.01)
│   ├─ MaxPool2d((2,2))  → (batch, 32, 30, 511)
│   └─ Dropout2d(0.3)
│
├─ ConvBlock2D #2
│   ├─ Conv2d(32→64, kernel=(3,7), padding=(1,3))
│   ├─ ...same structure...
│   └─ MaxPool2d((2,2))  → (batch, 64, 15, 255)
│
├─ ConvBlock2D #3
│   ├─ Conv2d(64→128, kernel=(3,7), padding=(1,3))
│   ├─ ...same structure...
│   └─ MaxPool2d((2,2))  → (batch, 128, 7, 127)
│
├─ Flatten  → (batch, 113792)
│
├─ FC Block #1
│   ├─ Linear(113792→512)
│   ├─ BatchNorm1d(512)
│   ├─ LeakyReLU(0.01)
│   └─ Dropout(0.3)
│
├─ FC Block #2
│   ├─ Linear(512→256)
│   ├─ BatchNorm1d(256)
│   ├─ LeakyReLU(0.01)
│   └─ Dropout(0.3)
│
└─ Dual Output Heads
    ├─ Classifier: Linear(256→82) → logits (for BCEWithLogitsLoss)
    └─ Regressor: Linear(256→82) → ReLU → normalized activity [0,1]
```

### 5.2 Configuration Parameters

```python
@dataclass
class Vega2DConfig:
    # Input dimensions
    num_channels: int = 1023          # Energy channels
    num_time_intervals: int = 60      # Time dimension
    
    # Output
    num_isotopes: int = 82
    
    # CNN architecture
    conv_channels: List[int] = [32, 64, 128]
    kernel_size: Tuple[int, int] = (3, 7)  # (time, energy)
    pool_size: Tuple[int, int] = (2, 2)
    
    # FC layers
    fc_hidden_dims: List[int] = [512, 256]
    
    # Regularization
    dropout_rate: float = 0.3
    leaky_relu_slope: float = 0.01
    
    # Activity scaling
    max_activity_bq: float = 1000.0
```

### 5.3 Kernel Size Rationale

The kernel `(3, 7)` is asymmetric:
- **3 in time dimension**: Captures short temporal correlations (3 seconds)
- **7 in energy dimension**: Captures spectral features wider than peak FWHM

This asymmetry reflects the different nature of the two dimensions.

### 5.4 Dual-Head Design

The model has **two output heads**:

1. **Classifier Head** (presence detection)
   - Output: 82 logits (raw scores)
   - Loss: `BCEWithLogitsLoss` (sigmoid applied internally)
   - Interpretation: `sigmoid(logit) > threshold` → isotope present

2. **Regressor Head** (activity estimation)
   - Output: 82 values in [0, 1] (normalized activity)
   - Loss: `HuberLoss` (robust to outliers)
   - Interpretation: `output × max_activity_bq` = estimated Bq

### 5.5 Loss Function

```python
total_loss = cls_weight * BCEWithLogitsLoss(logits, presence_labels)
           + reg_weight * HuberLoss(pred_activities, true_activities)

# Default weights
cls_weight = 1.0   # Classification dominates
reg_weight = 0.1   # Activity estimation is secondary
```

---

## 6. Training Procedures

### 6.1 Quick Start

```bash
# Test run (5 epochs)
python -m training.vega.train_2d --test

# Full training
python -m training.vega.train_2d \
    --epochs 50 \
    --batch-size 32 \
    --data-dir "O:/master_data_collection/isotopev2"

# Without mixed precision (if GPU issues)
python -m training.vega.train_2d --no-amp
```

### 6.2 Training Configuration

```python
@dataclass
class TrainingConfig2D:
    # Data paths
    data_dir: str = "O:/master_data_collection/isotopev2"
    model_dir: str = "models"
    
    # Training hyperparameters
    epochs: int = 50
    batch_size: int = 32
    learning_rate: float = 1e-3
    weight_decay: float = 1e-5
    
    # Loss weights
    classification_weight: float = 1.0
    regression_weight: float = 0.1
    
    # Mixed precision
    use_amp: bool = True
    
    # Early stopping
    early_stopping_patience: int = 10
    
    # Learning rate scheduler
    lr_scheduler_patience: int = 5
    lr_scheduler_factor: float = 0.5
    
    # Data loading
    num_workers: int = 4
```

### 6.3 Data Splits

```python
# Default splits in dataset_2d.py
train_ratio = 0.8   # 80% training
val_ratio = 0.1     # 10% validation
test_ratio = 0.1    # 10% test
```

### 6.4 Training Loop

Each epoch:
1. **Training phase**: Forward pass → loss → backward → optimizer step
2. **Validation phase**: Compute metrics without gradients
3. **Checkpointing**: Save if validation loss improved
4. **LR Scheduling**: Reduce LR if plateau detected
5. **Early stopping**: Stop if no improvement for N epochs

### 6.5 Metrics Tracked

| Metric | Description |
|--------|-------------|
| `loss` | Combined BCE + Huber loss |
| `cls_loss` | Binary cross-entropy (classification) |
| `reg_loss` | Huber loss (activity regression) |
| `exact_match` | % samples with all 82 isotopes correct |
| `precision` | TP / (TP + FP) |
| `recall` | TP / (TP + FN) |
| `f1` | Harmonic mean of precision and recall |

### 6.6 Expected Results

After 50 epochs on 200K samples:

| Metric | Expected Value |
|--------|----------------|
| F1 Score | >96% |
| Precision | >97% |
| Recall | >94% |
| Exact Match | >88% |
| Training Time | ~4 hours (RTX 5090) |

### 6.7 Checkpoint Files

Training produces:
- `vega_2d_best.pt` - Best validation loss (use for inference)
- `vega_2d_final.pt` - Final epoch
- `vega_2d_epoch_{N}.pt` - Per-epoch checkpoints
- `vega_2d_history.json` - Training metrics over time

### 6.8 Checkpoint Contents

```python
checkpoint = {
    'epoch': epoch,
    'model_state_dict': model.state_dict(),
    'optimizer_state_dict': optimizer.state_dict(),
    'model_config': asdict(model_config),
    'training_config': asdict(config),
    'best_val_loss': best_val_loss,
    'history': history
}
```

---

## 7. Inference System

### 7.1 Portable Inference Script

The file `inference/vega_portable_inference_2d.py` is **completely self-contained** and can be deployed anywhere with just:
- Python 3.8+
- NumPy
- PyTorch

It embeds:
- Model architecture definition
- Isotope index (all 82 names)
- Key gamma lines for sample generation
- Sample spectrum generator for testing

### 7.2 Command Line Usage

```bash
# Run demo with synthetic spectra
python vega_portable_inference_2d.py --model vega_2d_best.pt

# Analyze a specific spectrum
python vega_portable_inference_2d.py \
    --model vega_2d_best.pt \
    --spectrum my_measurement.npy \
    --threshold 0.5

# Lower threshold for higher sensitivity
python vega_portable_inference_2d.py \
    --model vega_2d_best.pt \
    --spectrum unknown_sample.npy \
    --threshold 0.3

# JSON output
python vega_portable_inference_2d.py \
    --model vega_2d_best.pt \
    --spectrum sample.npy \
    --json
```

### 7.3 Programmatic Usage

```python
from vega_portable_inference_2d import Vega2DInference
import numpy as np

# Initialize inference engine
inference = Vega2DInference("vega_2d_best.pt")

# Load your spectrum (shape: any × 1023, will be padded/truncated to 60×1023)
spectrum = np.load("my_measurement.npy")

# Run inference
result = inference.predict(spectrum, threshold=0.5)

# Get human-readable summary
print(result.summary())

# Access individual predictions
for isotope in result.get_present_isotopes():
    print(f"{isotope.name}: {isotope.probability:.1%} confidence, {isotope.activity_bq:.1f} Bq")

# Get all 82 probabilities (even non-detected)
full_result = inference.predict(spectrum, threshold=0.0, return_all=True)

# Export to JSON
json_str = result.to_json()

# Export to dict
data = result.to_dict()
```

### 7.4 API Reference

#### Vega2DInference Class

```python
class Vega2DInference:
    def __init__(
        self,
        model_path: Union[str, Path],   # Path to .pt checkpoint
        isotope_index: Optional = None,  # Custom index (uses default)
        device: Optional = None          # 'cuda', 'cpu', or auto-detect
    ):
        ...
    
    def predict(
        self,
        spectrum: np.ndarray,           # (T, 1023) array
        threshold: float = 0.5,         # Detection threshold
        return_all: bool = False        # Include non-detected isotopes
    ) -> SpectrumPrediction:
        ...
    
    def predict_from_file(
        self,
        file_path: str,                 # Path to .npy file
        threshold: float = 0.5
    ) -> SpectrumPrediction:
        ...
    
    def predict_batch(
        self,
        spectra: List[np.ndarray],
        threshold: float = 0.5
    ) -> List[SpectrumPrediction]:
        ...
```

#### SpectrumPrediction Dataclass

```python
@dataclass
class SpectrumPrediction:
    isotopes: List[IsotopePrediction]   # All predictions
    num_present: int                    # Count above threshold
    confidence: float                   # Average probability of detected
    threshold_used: float               # Threshold used
    
    def get_present_isotopes(self) -> List[IsotopePrediction]:
        """Return only detected isotopes."""
    
    def summary(self) -> str:
        """Human-readable summary."""
    
    def to_dict(self) -> dict:
        """Convert to dictionary."""
    
    def to_json(self, indent=2) -> str:
        """Convert to JSON string."""
```

#### IsotopePrediction Dataclass

```python
@dataclass
class IsotopePrediction:
    name: str           # e.g., "Cs-137"
    probability: float  # 0.0 to 1.0
    activity_bq: float  # Estimated activity in Becquerels
    present: bool       # True if probability >= threshold
```

---

## 8. Output Interpretation

### 8.1 Understanding Predictions

Each prediction contains:

| Field | Type | Range | Meaning |
|-------|------|-------|---------|
| `probability` | float | 0.0-1.0 | Model's confidence isotope is present |
| `activity_bq` | float | 0-1000 | Estimated activity (only meaningful if present) |
| `present` | bool | T/F | Whether probability >= threshold |

### 8.2 Probability Interpretation

| Probability | Interpretation | Action |
|-------------|----------------|--------|
| >0.95 | **Very High Confidence** | Definitely present |
| 0.80-0.95 | **High Confidence** | Very likely present |
| 0.50-0.80 | **Moderate Confidence** | Probably present, verify |
| 0.30-0.50 | **Low Confidence** | Possibly present, investigate |
| <0.30 | **Very Low** | Likely absent |

### 8.3 Activity Estimation Accuracy

Activity estimates are **approximate** due to:
- Unknown source distance
- Unknown shielding
- Detector efficiency variations
- Normalization removes absolute count information

**Use activity estimates for:**
- Relative comparisons between isotopes
- Order-of-magnitude estimates
- Identifying dominant vs minor contributors

**Do NOT use for:**
- Regulatory compliance measurements
- Precise quantitative analysis
- Safety limit calculations

### 8.4 Single Isotope Detection

When **one isotope** is detected:

```json
{
  "isotopes": [
    {"name": "Cs-137", "probability": 0.98, "activity_bq": 45.2, "present": true}
  ],
  "num_present": 1,
  "confidence": 0.98
}
```

**Interpretation:**
- Clean calibration source or specific contamination
- Verify gamma lines match expected energies
- Single-isotope sources are common in:
  - Calibration checks
  - Medical procedures
  - Industrial gauges

### 8.5 Multiple Isotope Detection

When **multiple isotopes** are detected:

```json
{
  "isotopes": [
    {"name": "Cs-137", "probability": 0.95, "activity_bq": 32.1, "present": true},
    {"name": "Cs-134", "probability": 0.87, "activity_bq": 18.4, "present": true}
  ],
  "num_present": 2,
  "confidence": 0.91
}
```

**Interpretation:**
- Check for decay chain relationships (Section 10)
- Look for known signatures (fallout, NORM, equilibrium)
- Consider mixed-source scenarios

### 8.6 Background-Only (No Detection)

When **no isotopes** exceed threshold:

```json
{
  "isotopes": [],
  "num_present": 0,
  "confidence": 0.82
}
```

**Interpretation:**
- Spectrum shows only environmental background
- K-40 (1460 keV) may be visible but below threshold
- Natural radon daughters may contribute
- **This is normal** for most measurements!

### 8.7 Common Detection Patterns

#### Pattern 1: Calibration Source
```
Cs-137: 98% ───────────────────────
All others: <5%
```
Clean single-source signature. Typical for check sources.

#### Pattern 2: NORM Material
```
K-40: 75%  ─────────────────
Ra-226: 62% ───────────────
Th-232: 58% ──────────────
Bi-214: 71% ───────────────
```
Multiple natural isotopes at similar activities. Indicates rocks, soil, building materials.

#### Pattern 3: Decay Chain
```
U-238: 45%  ─────────────
Ra-226: 88% ──────────────────
Pb-214: 92% ───────────────────
Bi-214: 94% ────────────────────
```
Parent + daughters detected. Indicates secular equilibrium. See Section 10.

#### Pattern 4: Reactor Fallout
```
Cs-137: 95% ───────────────────
Cs-134: 72% ────────────────
```
Cs-137 + Cs-134 is the **fingerprint of reactor-origin material**.

---

## 9. Isotope Reference

### 9.1 Complete Isotope List (82 Total)

The model identifies these isotopes, sorted alphabetically (same order as model output indices):

```
Index | Isotope   | Category            | Primary Gamma (keV)
------|-----------|---------------------|--------------------
  0   | Ac-225    | U235_CHAIN          | 99.9
  1   | Ac-227    | U235_CHAIN          | 236.0
  2   | Ac-228    | TH232_CHAIN         | 911.2
  3   | Ag-110m   | ACTIVATION          | 657.8
  4   | Am-241    | CALIBRATION         | 59.5
  5   | Au-198    | MEDICAL             | 411.8
  6   | Ba-133    | CALIBRATION         | 356.0
  7   | Be-7      | COSMOGENIC          | 477.6
  8   | Bi-207    | CALIBRATION         | 569.7
  9   | Bi-210    | U238_CHAIN          | 46.5
 10   | Bi-211    | U235_CHAIN          | 351.1
 11   | Bi-212    | TH232_CHAIN         | 727.3
 12   | Bi-214    | U238_CHAIN          | 609.3
 13   | C-14      | COSMOGENIC          | (beta only)
 14   | Cd-109    | INDUSTRIAL          | 88.0
 15   | Ce-139    | ACTIVATION          | 165.9
 16   | Ce-141    | REACTOR_FALLOUT     | 145.4
 17   | Ce-144    | REACTOR_FALLOUT     | 133.5
 18   | Co-57     | CALIBRATION         | 122.1
 19   | Co-58     | ACTIVATION          | 810.8
 20   | Co-60     | CALIBRATION         | 1173.2, 1332.5
 21   | Cr-51     | ACTIVATION          | 320.1
 22   | Cs-134    | REACTOR_FALLOUT     | 604.7, 795.9
 23   | Cs-137    | CALIBRATION         | 661.7
 24   | Cu-64     | MEDICAL             | 1345.8
 25   | Eu-152    | CALIBRATION         | 121.8, 344.3
 26   | Eu-154    | CALIBRATION         | 123.1, 1274.4
 27   | Eu-155    | REACTOR_FALLOUT     | 86.5, 105.3
 28   | F-18      | MEDICAL             | 511.0
 29   | Fe-55     | ACTIVATION          | (X-rays)
 30   | Fe-59     | ACTIVATION          | 1099.3
 31   | Ga-67     | MEDICAL             | 93.3, 184.6
 32   | Ga-68     | MEDICAL             | 511.0
 33   | Ge-68     | CALIBRATION         | 511.0
 34   | H-3       | COSMOGENIC          | (beta only)
 35   | Hf-175    | ACTIVATION          | 343.4
 36   | Hf-181    | ACTIVATION          | 482.2
 37   | Hg-203    | INDUSTRIAL          | 279.2
 38   | I-123     | MEDICAL             | 159.0
 39   | I-125     | MEDICAL             | 35.5
 40   | I-131     | MEDICAL             | 364.5
 41   | In-111    | MEDICAL             | 171.3, 245.4
 42   | Ir-192    | INDUSTRIAL          | 316.5, 468.1
 43   | K-40      | NATURAL_BACKGROUND  | 1460.8
 44   | Kr-85     | REACTOR_FALLOUT     | 514.0
 45   | La-140    | REACTOR_FALLOUT     | 1596.2
 46   | Lu-177    | MEDICAL             | 208.4
 47   | Mn-54     | CALIBRATION         | 834.8
 48   | Mo-99     | MEDICAL             | 140.5, 739.5
 49   | Na-22     | CALIBRATION         | 511.0, 1274.5
 50   | Na-24     | ACTIVATION          | 1368.6, 2754.0
 51   | Nb-95     | REACTOR_FALLOUT     | 765.8
 52   | Np-237    | INDUSTRIAL          | 86.5
 53   | Pa-231    | U235_CHAIN          | 283.7
 54   | Pa-233    | U238_CHAIN          | 311.9
 55   | Pa-234m   | U238_CHAIN          | 1001.0
 56   | Pb-210    | U238_CHAIN          | 46.5
 57   | Pb-211    | U235_CHAIN          | 404.9
 58   | Pb-212    | TH232_CHAIN         | 238.6
 59   | Pb-214    | U238_CHAIN          | 351.9
 60   | Po-210    | U238_CHAIN          | (alpha only)
 61   | Pu-239    | INDUSTRIAL          | 413.7
 62   | Ra-223    | U235_CHAIN          | 269.5
 63   | Ra-224    | TH232_CHAIN         | 241.0
 64   | Ra-226    | U238_CHAIN          | 186.2
 65   | Rb-86     | ACTIVATION          | 1076.6
 66   | Rn-219    | U235_CHAIN          | 271.2
 67   | Rn-220    | TH232_CHAIN         | 549.7
 68   | Rn-222    | U238_CHAIN          | (alpha only)
 69   | Ru-103    | REACTOR_FALLOUT     | 497.1
 70   | Ru-106    | REACTOR_FALLOUT     | 511.9, 621.9
 71   | Sb-124    | ACTIVATION          | 602.7
 72   | Sb-125    | REACTOR_FALLOUT     | 427.9
 73   | Sc-46     | ACTIVATION          | 889.3
 74   | Se-75     | INDUSTRIAL          | 264.7, 279.5
 75   | Sr-85     | CALIBRATION         | 514.0
 76   | Sr-90     | REACTOR_FALLOUT     | (beta only)
 77   | Tc-99m    | MEDICAL             | 140.5
 78   | Th-227    | U235_CHAIN          | 236.0
 79   | Th-228    | TH232_CHAIN         | 84.4
 80   | Th-232    | PRIMORDIAL          | (chain daughters)
 81   | Th-234    | U238_CHAIN          | 63.3, 92.4
```

### 9.2 Key Gamma Lines Reference

```python
GAMMA_LINES = {
    # Calibration Sources
    "Cs-137": [(661.7, 0.851)],                    # Classic 662 keV
    "Co-60": [(1173.2, 0.999), (1332.5, 0.9998)],  # Dual peaks
    "Am-241": [(59.5, 0.359)],                     # Low energy
    "Ba-133": [(356.0, 0.623), (81.0, 0.329)],
    "Na-22": [(511.0, 1.798), (1274.5, 0.999)],    # Positron annihilation
    "Eu-152": [(121.8, 0.284), (344.3, 0.265), (1408.0, 0.210)],
    
    # Medical
    "Tc-99m": [(140.5, 0.890)],
    "I-131": [(364.5, 0.817)],
    "F-18": [(511.0, 1.934)],    # PET isotope
    
    # Background
    "K-40": [(1460.8, 0.107)],   # Always present
    
    # Decay Chains
    "Pb-214": [(351.9, 0.371), (295.2, 0.192)],
    "Bi-214": [(609.3, 0.461), (1120.3, 0.150)],
    "Tl-208": [(583.2, 0.845), (2614.5, 0.359)],
}
```

### 9.3 Isotope Categories

| Category | Description | Examples |
|----------|-------------|----------|
| `CALIBRATION` | Check sources, well-characterized | Cs-137, Co-60, Am-241 |
| `MEDICAL` | Hospital/imaging use, short-lived | Tc-99m, I-131, F-18 |
| `INDUSTRIAL` | Sealed sources, gauges | Ir-192, Se-75 |
| `NATURAL_BACKGROUND` | Always present in environment | K-40 |
| `PRIMORDIAL` | Existed since Earth formed | U-238, Th-232, U-235 |
| `U238_CHAIN` | Uranium-238 decay daughters | Ra-226, Pb-214, Bi-214 |
| `TH232_CHAIN` | Thorium-232 decay daughters | Ac-228, Pb-212, Tl-208 |
| `U235_CHAIN` | Uranium-235 decay daughters | Pa-231, Ac-227 |
| `REACTOR_FALLOUT` | Fission products | Cs-134, I-131, Sr-90 |
| `ACTIVATION` | Neutron-activated materials | Co-58, Fe-59, Zn-65 |
| `COSMOGENIC` | Cosmic ray produced | Be-7, Na-22, C-14 |

---

## 10. Decay Chain Analysis

### 10.1 Understanding Decay Chains

Radioactive isotopes decay into other isotopes, forming **decay chains**. The three major natural chains are:

1. **Uranium-238 Series** → ends at Pb-206 (stable)
2. **Thorium-232 Series** → ends at Pb-208 (stable)
3. **Uranium-235 Series** → ends at Pb-207 (stable)

### 10.2 Secular Equilibrium

In **secular equilibrium** (closed system, long time), all daughter activities equal the parent activity:

```
A_parent = A_daughter1 = A_daughter2 = ... = A_daughterN
```

This means detecting daughters implies parent presence!

### 10.3 Chain Signatures for Parent Inference

The system defines **ChainSignature** patterns to infer parent isotopes from detected daughters:

#### Rn-222 Progeny (Indicates Radon)
```python
required: {"Pb-214", "Bi-214"}
optional: {"Pb-210"}
inferred_parent: "Rn-222"
```
**When you see Pb-214 + Bi-214 → atmospheric radon is present**

#### Ra-226 Equilibrium (Indicates Uranium)
```python
required: {"Ra-226", "Pb-214", "Bi-214"}
optional: {"Pb-210", "Bi-210"}
inferred_parent: "U-238"
```
**When you see Ra-226 + daughters → U-238 decay chain in equilibrium**

#### Th-232 Equilibrium (Indicates Thorium)
```python
required: {"Ac-228", "Pb-212", "Bi-212"}
optional: {"Tl-208", "Ra-224"}
inferred_parent: "Th-232"
```
**When you see Ac-228 + Pb-212 + Bi-212 → Th-232 source material**

#### Rn-220 Progeny (Thoron Daughters)
```python
required: {"Pb-212", "Bi-212"}
optional: {"Tl-208"}
inferred_parent: "Rn-220"
```
**When you see Pb-212 + Bi-212 → thoron (Rn-220) is present**

### 10.4 Using Decay Chain Inference

```python
from synthetic_spectra.ground_truth.decay_chains import infer_parent_from_daughters

# After running inference, get detected isotope names
detected = {iso.name for iso in result.get_present_isotopes()}

# Infer parent isotopes
parents = infer_parent_from_daughters(detected)

for parent_name, signature, confidence in parents:
    print(f"Inferred: {parent_name} (confidence: {confidence:.1%})")
    print(f"  Based on: {signature.name}")
    print(f"  Required daughters: {signature.required_daughters}")
```

### 10.5 Interpreting Chain Detections

#### Example 1: Uranium Ore
```
Detected: U-238 (45%), Ra-226 (88%), Pb-214 (92%), Bi-214 (94%)
```
**Interpretation:**
- U-238 has low detection probability (weak gamma)
- Daughters are strong gamma emitters
- High confidence of uranium-bearing material
- In secular equilibrium

#### Example 2: Radon in Air
```
Detected: Pb-214 (78%), Bi-214 (82%)
NOT detected: Ra-226, U-238
```
**Interpretation:**
- Airborne radon daughters (deposited on detector)
- Parent Rn-222 is gas (no gamma)
- Ra-226/U-238 not present locally
- Common indoor measurement result

#### Example 3: Thorium Lantern Mantle
```
Detected: Th-232 (52%), Ac-228 (71%), Pb-212 (85%), Bi-212 (79%), Tl-208 (67%)
```
**Interpretation:**
- Complete Th-232 chain
- Tl-208's 2614 keV line is distinctive
- Indicates thoriated material

### 10.6 U-238 Decay Chain Detail

```
U-238 (4.47 Gy)
  ↓ α
Th-234 (24.1 d) [63.3, 92.4 keV]
  ↓ β
Pa-234m (1.17 min) [1001 keV]
  ↓ β
U-234 (245 ky)
  ↓ α
Th-230 (75.4 ky)
  ↓ α
Ra-226 (1600 y) [186.2 keV]
  ↓ α
Rn-222 (3.82 d) [gas, no gamma]
  ↓ α
Po-218 (3.1 min)
  ↓ α
Pb-214 (26.8 min) [351.9, 295.2 keV] ★ KEY INDICATOR
  ↓ β
Bi-214 (19.9 min) [609.3, 1120.3 keV] ★ KEY INDICATOR
  ↓ β
Po-214 (164 μs)
  ↓ α
Pb-210 (22.3 y) [46.5 keV]
  ↓ β
Bi-210 (5.01 d)
  ↓ β
Po-210 (138 d)
  ↓ α
Pb-206 (stable)
```

### 10.7 Th-232 Decay Chain Detail

```
Th-232 (14.0 Gy)
  ↓ α
Ra-228 (5.75 y) [no significant gamma]
  ↓ β
Ac-228 (6.15 h) [911.2, 338.3, 969.0 keV] ★ KEY INDICATOR
  ↓ β
Th-228 (1.91 y) [84.4 keV]
  ↓ α
Ra-224 (3.66 d) [241.0 keV]
  ↓ α
Rn-220 (55.6 s) [549.7 keV]
  ↓ α
Po-216 (0.145 s)
  ↓ α
Pb-212 (10.64 h) [238.6 keV] ★ KEY INDICATOR
  ↓ β
Bi-212 (60.6 min) [727.3 keV]
  ↓ α (35.94%)        ↓ β (64.06%)
Tl-208 (3.05 min)     Po-212 (0.3 μs)
[583.2, 2614.5 keV]     ↓ α
      ↓ β               ↙
         → Pb-208 (stable)
```

---

## 11. Threshold Selection Guide

### 11.1 What is the Threshold?

The threshold is the **probability cutoff** for declaring an isotope "present":
- `probability >= threshold` → **DETECTED**
- `probability < threshold` → **NOT DETECTED**

### 11.2 Threshold Trade-offs

| Threshold | Precision | Recall | False Positives | False Negatives |
|-----------|-----------|--------|-----------------|-----------------|
| 0.9 | Very High | Low | Very Few | Many |
| 0.7 | High | Moderate | Few | Some |
| **0.5** | **Balanced** | **Balanced** | **Balanced** | **Balanced** |
| 0.3 | Moderate | High | Some | Few |
| 0.1 | Low | Very High | Many | Very Few |

### 11.3 Recommended Thresholds by Scenario

| Scenario | Threshold | Rationale |
|----------|-----------|-----------|
| **General purpose** | 0.5 | Balanced performance |
| **Calibration verification** | 0.7 | High confidence needed |
| **Weak source detection** | 0.3 | Don't miss faint signals |
| **Safety screening** | 0.3 | Prioritize recall |
| **Research/survey** | 0.4 | Slightly favor sensitivity |
| **Regulatory reporting** | 0.6 | Minimize false positives |

### 11.4 Adjusting Threshold at Runtime

```python
# High-sensitivity scan
result_sensitive = inference.predict(spectrum, threshold=0.3)

# High-confidence confirmation
result_confident = inference.predict(spectrum, threshold=0.7)

# Compare
print(f"At 0.3: {result_sensitive.num_present} isotopes")
print(f"At 0.7: {result_confident.num_present} isotopes")
```

### 11.5 Multi-Threshold Analysis

```python
def analyze_at_multiple_thresholds(spectrum, inference):
    """Analyze spectrum at multiple thresholds."""
    thresholds = [0.3, 0.5, 0.7, 0.9]
    
    for t in thresholds:
        result = inference.predict(spectrum, threshold=t)
        names = [iso.name for iso in result.get_present_isotopes()]
        print(f"Threshold {t}: {names}")
```

**Example Output:**
```
Threshold 0.3: ['Cs-137', 'Cs-134', 'K-40', 'Pb-214']
Threshold 0.5: ['Cs-137', 'Cs-134', 'K-40']
Threshold 0.7: ['Cs-137', 'Cs-134']
Threshold 0.9: ['Cs-137']
```

**Interpretation:** Cs-137 is definitely present (>0.9), Cs-134 is very likely (>0.7), K-40 is probable (>0.5), Pb-214 is possible (>0.3).

---

## 12. Example Workflows

### 12.1 Basic Inference Workflow

```python
import numpy as np
from vega_portable_inference_2d import Vega2DInference

# 1. Initialize
inference = Vega2DInference("models/vega_2d_best.pt")

# 2. Load spectrum
spectrum = np.load("measurement.npy")
print(f"Spectrum shape: {spectrum.shape}")

# 3. Run inference
result = inference.predict(spectrum, threshold=0.5)

# 4. Display results
print(result.summary())

# 5. Export
with open("results.json", "w") as f:
    f.write(result.to_json())
```

### 12.2 Batch Processing Workflow

```python
from pathlib import Path

def process_directory(data_dir: str, model_path: str, threshold: float = 0.5):
    """Process all spectra in a directory."""
    inference = Vega2DInference(model_path)
    results = []
    
    for npy_file in Path(data_dir).glob("*.npy"):
        spectrum = np.load(npy_file)
        prediction = inference.predict(spectrum, threshold)
        
        results.append({
            "file": npy_file.name,
            "detected": [iso.name for iso in prediction.get_present_isotopes()],
            "confidence": prediction.confidence
        })
    
    return results

# Usage
results = process_directory("spectra/", "models/vega_2d_best.pt")
for r in results:
    print(f"{r['file']}: {r['detected']}")
```

### 12.3 Decay Chain Analysis Workflow

```python
from vega_portable_inference_2d import Vega2DInference
from synthetic_spectra.ground_truth.decay_chains import (
    infer_parent_from_daughters,
    get_chain_daughters
)

def analyze_with_chain_inference(spectrum, inference, threshold=0.5):
    """Full analysis including decay chain inference."""
    
    # Run basic inference
    result = inference.predict(spectrum, threshold)
    detected = {iso.name for iso in result.get_present_isotopes()}
    
    print("=== DIRECT DETECTIONS ===")
    for iso in result.get_present_isotopes():
        print(f"  {iso.name}: {iso.probability:.1%}")
    
    # Infer parents from daughters
    print("\n=== DECAY CHAIN ANALYSIS ===")
    parents = infer_parent_from_daughters(detected)
    
    if parents:
        for parent, signature, confidence in parents:
            print(f"\n  Inferred Parent: {parent}")
            print(f"    Confidence: {confidence:.1%}")
            print(f"    Signature: {signature.name}")
            print(f"    Required daughters found: {detected & signature.required_daughters}")
    else:
        print("  No decay chain signatures identified")
    
    return result, parents

# Usage
result, parents = analyze_with_chain_inference(spectrum, inference)
```

### 12.4 Real-Time Monitoring Workflow

```python
import time

def monitor_spectrum_stream(inference, spectrum_source, interval=1.0, threshold=0.5):
    """Monitor incoming spectra in real-time."""
    
    while True:
        # Get latest spectrum (implement your data source)
        spectrum = spectrum_source.get_latest()
        
        if spectrum is not None:
            result = inference.predict(spectrum, threshold)
            
            if result.num_present > 0:
                print(f"[{time.strftime('%H:%M:%S')}] DETECTION!")
                for iso in result.get_present_isotopes():
                    print(f"  {iso.name}: {iso.probability:.1%}, {iso.activity_bq:.1f} Bq")
            else:
                print(f"[{time.strftime('%H:%M:%S')}] Background only")
        
        time.sleep(interval)
```

### 12.5 Sample JSON Output

```json
{
  "isotopes": [
    {
      "name": "Cs-137",
      "probability": 0.9823,
      "activity_bq": 45.2,
      "present": true
    },
    {
      "name": "Cs-134",
      "probability": 0.8741,
      "activity_bq": 18.7,
      "present": true
    }
  ],
  "num_present": 2,
  "confidence": 0.9282,
  "threshold_used": 0.5
}
```

### 12.6 Sample Input Generation (for Testing)

```python
from vega_portable_inference_2d import create_sample_spectrum_2d

# Generate test spectrum
test_spectrum = create_sample_spectrum_2d(
    isotope="Cs-137",
    activity_bq=100.0,
    duration_seconds=60,
    add_background=True,
    add_noise=True,
    detector_fwhm_percent=8.5,
    seed=42
)

print(f"Shape: {test_spectrum.shape}")  # (60, 1023)
print(f"Range: [{test_spectrum.min():.1f}, {test_spectrum.max():.1f}]")

# Save for later
np.save("test_cs137.npy", test_spectrum)
```

---

## 13. Troubleshooting

### 13.1 Common Issues

#### Issue: "No isotopes detected" for known source
**Possible causes:**
1. Threshold too high → Lower to 0.3
2. Source very weak → Increase measurement time
3. Wrong normalization → Check if max > 0
4. Input shape wrong → Must be (T, 1023)

**Solution:**
```python
# Check probabilities before thresholding
result = inference.predict(spectrum, threshold=0.0, return_all=True)
top5 = sorted(result.isotopes, key=lambda x: -x.probability)[:5]
for iso in top5:
    print(f"{iso.name}: {iso.probability:.1%}")
```

#### Issue: "Too many false positives"
**Possible causes:**
1. Threshold too low → Raise to 0.6-0.7
2. Noisy data → Check for acquisition problems
3. Strong overlapping peaks → Check decay chains

**Solution:**
```python
# Use higher threshold for confirmation
result = inference.predict(spectrum, threshold=0.7)
```

#### Issue: "CUDA out of memory"
**Possible causes:**
1. Batch size too large
2. Other GPU processes

**Solution:**
```python
# Force CPU inference
inference = Vega2DInference(model_path, device=torch.device('cpu'))
```

#### Issue: "Model weights not matching"
**Possible causes:**
1. Model architecture changed
2. Wrong checkpoint version

**Solution:**
- Ensure checkpoint matches Vega2DConfig defaults
- Re-train if architecture was modified

### 13.2 Data Quality Checks

```python
def check_spectrum_quality(spectrum: np.ndarray) -> dict:
    """Check spectrum data quality."""
    issues = []
    
    # Shape check
    if spectrum.ndim != 2:
        issues.append(f"Wrong dimensions: {spectrum.ndim}, expected 2")
    
    if spectrum.shape[1] != 1023:
        issues.append(f"Wrong channels: {spectrum.shape[1]}, expected 1023")
    
    # Value checks
    if spectrum.min() < 0:
        issues.append("Contains negative values")
    
    if spectrum.max() == 0:
        issues.append("All zeros - no data")
    
    if np.isnan(spectrum).any():
        issues.append("Contains NaN values")
    
    if np.isinf(spectrum).any():
        issues.append("Contains infinite values")
    
    return {
        "shape": spectrum.shape,
        "min": float(spectrum.min()),
        "max": float(spectrum.max()),
        "mean": float(spectrum.mean()),
        "issues": issues,
        "valid": len(issues) == 0
    }
```

### 13.3 Performance Optimization

```python
# Batch predictions are faster than individual
spectra = [np.load(f) for f in spectrum_files]
results = inference.predict_batch(spectra, threshold=0.5)

# Pre-load model once, reuse for all predictions
inference = Vega2DInference(model_path)  # Do once
for spectrum in stream:
    result = inference.predict(spectrum)  # Fast
```

---

## Appendix A: Complete Configuration Reference

### A.1 Vega2DConfig Defaults

```python
Vega2DConfig(
    num_channels=1023,
    num_time_intervals=60,
    num_isotopes=82,
    conv_channels=[32, 64, 128],
    kernel_size=(3, 7),
    pool_size=(2, 2),
    fc_hidden_dims=[512, 256],
    dropout_rate=0.3,
    leaky_relu_slope=0.01,
    max_activity_bq=1000.0
)
```

### A.2 TrainingConfig2D Defaults

```python
TrainingConfig2D(
    data_dir="O:/master_data_collection/isotopev2",
    model_dir="models",
    target_time_intervals=60,
    epochs=50,
    batch_size=32,
    learning_rate=0.001,
    weight_decay=1e-05,
    classification_weight=1.0,
    regression_weight=0.1,
    use_amp=True,
    early_stopping_patience=10,
    lr_scheduler_patience=5,
    lr_scheduler_factor=0.5,
    num_workers=4
)
```

### A.3 Generation Scenario Fractions

```python
DEFAULT_SCENARIOS = [
    BackgroundOnlyScenario(0.15),
    SingleCalibrationScenario(0.20),
    SingleMedicalScenario(0.08),
    SingleIndustrialScenario(0.05),
    UraniumChainScenario(0.10),
    ThoriumChainScenario(0.10),
    NORMScenario(0.07),
    FalloutScenario(0.05),
    MixedSourcesScenario(0.10),
    ComplexMixScenario(0.05),
    WeakSourceScenario(0.05),
]
```

---

## Appendix B: Version History

| Version | Date | Changes |
|---------|------|---------|
| 2.0 | Jan 2025 | 2D model architecture, temporal features |
| 1.0 | Dec 2024 | Original 1D model (deprecated) |

---

**Document End**

*For questions or issues, consult the agents.md file in the repository root.*
