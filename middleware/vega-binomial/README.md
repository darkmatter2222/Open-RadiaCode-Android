# Vega Bulk Binomial Isotope Detection API

Loads ALL trained per-isotope binary detection models onto GPU at startup and performs bulk inference on incoming spectra with automatic calibration translation.

## Overview

This service differs from the main isotope identification API (`vega-isotope-identification`) by:

1. **Per-isotope binary models**: Instead of a single multi-label model, this uses many tiny binary classifiers (one per isotope)
2. **Calibration translation**: Accepts raw device spectra with calibration coefficients and translates to training energy axis
3. **Comprehensive timing**: Returns millisecond timestamps for every processing step

## Quick Start

```powershell
# Copy .env.example to .env and configure
cp .env.example .env

# Deploy to server
.\deploy.ps1
```

## API Endpoints

### Health Check
```
GET /health
```
Returns service status and model counts.

### Service Info
```
GET /info
```
Returns detailed configuration including loaded models and GPU info.

### List Isotopes
```
GET /isotopes
```
Returns all isotopes with their model status (loaded/failed) and thresholds.

### Detect Isotopes
```
POST /detect
```
Main detection endpoint. Accepts:
- `spectrum`: 2D array (time x channels), typically 300x1023
- `calibration`: Device calibration coefficients (`a0`, `a1`, `a2`)
- `isotopes`: Optional list of specific isotopes to check

### Detect (1D)
```
POST /detect/1d
```
Accepts 1D spectrum (1023 channels), expands to 2D.

### Batch Detection
```
POST /detect/batch
```
Process multiple spectra in one request.

## Example Request

```json
{
  "spectrum": [[...1023 values...], [...], ...],  // 300 rows x 1023 columns
  "calibration": {
    "a0": 3.5093544,
    "a1": 2.3624456,
    "a2": 4.0645464e-4
  },
  "isotopes": null  // null = all isotopes
}
```

## Example Response

```json
{
  "request_id": "abc123",
  "timestamp_utc": "2026-01-26T15:30:00Z",
  "total_elapsed_ms": 45.3,
  "preprocessing": {
    "translate_ms": 12.5,
    "normalize_ms": 0.8,
    "total_counts_before": 150000,
    "total_counts_after": 148500,
    "calibration": {"a0": 3.509, "a1": 2.362, "a2": 0.000406}
  },
  "inference": {
    "models_run": 70,
    "total_inference_ms": 28.4,
    "inference_per_isotope_ms": {"Cs-137": 0.4, ...}
  },
  "results": {
    "Cs-137": {
      "probability": 0.9823,
      "threshold": 0.5,
      "tuned_threshold": 0.32,
      "detected": true,
      "inference_ms": 0.4
    },
    ...
  },
  "detected_isotopes": ["Cs-137", "K-40"],
  "summary": {
    "total_models": 70,
    "models_run": 70,
    "detected_count": 2
  }
}
```

## Calibration Translation

Device spectra come with energy calibration coefficients:
```
E(keV) = a0 + a1 * channel + a2 * channel^2
```

The training data uses a fixed linear calibration (20-3000 keV across 1023 channels). This service performs flux-conserving rebinning to translate device spectra to the training energy axis before inference.

## Docker Configuration

The service requires GPU access:

```yaml
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: 1
          capabilities: [gpu]
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VEGA_BINOMIAL_MODELS_DIR` | `/app/models` | Directory containing binary model files |
| `VEGA_DEVICE` | `cuda` | PyTorch device (`cuda` or `cpu`) |
| `VEGA_DEFAULT_THRESHOLD` | `0.5` | Default detection threshold |
| `VEGA_MODEL_VERSION` | `v5-micro-2000per` | Model version identifier |

## Model Files

Each isotope requires two files:
- `binary_<Isotope>.pt` - PyTorch checkpoint
- `binary_<Isotope>_meta.json` - Metadata with thresholds

Example:
```
models/
  binary_Cs_137.pt
  binary_Cs_137_meta.json
  binary_K_40.pt
  binary_K_40_meta.json
  ...
```

## Port

Default: **8021**

(Compared to 8020 for the main isotope identification service)
