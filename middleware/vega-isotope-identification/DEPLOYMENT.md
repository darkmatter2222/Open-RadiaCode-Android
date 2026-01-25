# Vega Isotope Identification API

## Overview

The Vega Isotope Identification API provides deep learning-based identification of radioactive isotopes from gamma ray spectra. The model can identify **82 different isotopes** with their detection probabilities and estimated activities.

## Quick Start

### Via Vega Ingress (Recommended)

The isotope identification service is available through the Vega Ingress API gateway:

```
Base URL: http://<your-server>:8080
```

### Direct Access

For direct access (bypassing ingress):

```
Base URL: http://<your-server>:8020
```

---

## API Endpoints

### Health Check

```http
GET /health
```

Returns service health status and model loading state.

**Response:**
```json
{
  "status": "healthy",
  "model_loaded": true,
  "device": "cuda"
}
```

### Service Info

```http
GET /info
```

Returns model and service information.

**Response:**
```json
{
  "service": "vega-isotope-identification",
  "version": "2.0.0",
  "model_path": "models/vega_2d_final.pt",
  "device": "cuda",
  "num_isotopes": 82,
  "num_channels": 1023,
  "default_threshold": 0.5,
  "model_loaded": true,
  "architecture": "CNN[64, 128, 256] → FC[512, 256]"
}
```

### List Supported Isotopes

```http
GET /isotopes
```

Returns all 82 supported isotopes with their gamma emission lines.

**Response:**
```json
{
  "isotopes": ["Ac-228", "Ag-110m", "Am-241", "Ba-133", ...],
  "total": 82,
  "gamma_lines": {
    "Cs-137": [[661.7, 0.851]],
    "Co-60": [[1173.2, 0.999], [1332.5, 0.999]],
    ...
  }
}
```

---

## Isotope Identification

### Basic Identification

```http
POST /identify
```

Identify isotopes from a gamma spectrum.

**Request Body:**
```json
{
  "spectrum": [0.0, 0.1, 0.2, ..., 0.5],  // 1023 float values
  "threshold": 0.5,                        // Detection threshold (0-1)
  "return_all": false                      // Return all 82 isotopes if true
}
```

**Parameters:**

| Parameter    | Type      | Default | Description |
|--------------|-----------|---------|-------------|
| `spectrum`   | float[]   | Required | Array of 1023 counts per channel |
| `threshold`  | float     | 0.5     | Detection threshold (0=sensitive, 1=specific) |
| `return_all` | boolean   | false   | If true, returns all 82 isotopes |

**Response:**
```json
{
  "isotopes": [
    {
      "name": "Cs-137",
      "probability": 0.9823,
      "activity_bq": 95.4,
      "present": true
    }
  ],
  "num_detected": 1,
  "confidence": 0.9823,
  "threshold_used": 0.5,
  "processing_time_ms": 12.5
}
```

### Base64-Encoded Spectrum

```http
POST /identify/b64
```

For numpy arrays saved as `.npy` files.

**Request Body:**
```json
{
  "spectrum_b64": "k05VTVBZAQ...",  // Base64-encoded .npy bytes
  "threshold": 0.5,
  "return_all": false
}
```

**Python Example:**
```python
import numpy as np
import base64
import io

# Your spectrum data
spectrum = np.array([...], dtype=np.float32)  # 1023 values

# Encode to base64
buffer = io.BytesIO()
np.save(buffer, spectrum)
spectrum_b64 = base64.b64encode(buffer.getvalue()).decode('ascii')

# Send request
payload = {
    "spectrum_b64": spectrum_b64,
    "threshold": 0.5
}
```

### Batch Identification

```http
POST /identify/batch
```

Process multiple spectra in a single request.

**Request Body:**
```json
{
  "spectra": [
    [0.0, 0.1, ...],  // First spectrum (1023 values)
    [0.2, 0.3, ...],  // Second spectrum
    [0.1, 0.4, ...]   // Third spectrum
  ],
  "threshold": 0.5,
  "return_all": false
}
```

**Response:**
```json
{
  "results": [
    { "isotopes": [...], "num_detected": 1, ... },
    { "isotopes": [...], "num_detected": 2, ... },
    { "isotopes": [...], "num_detected": 1, ... }
  ],
  "total_spectra": 3,
  "total_processing_time_ms": 35.2
}
```

---

## Input Format

### Spectrum Requirements

- **Array length:** Exactly 1023 values
- **Value type:** Float (counts per channel)
- **Energy range:** 20 keV to 3000 keV
- **Channel mapping:** Channel `i` corresponds to energy:
  ```
  E_i = 20 + i × (3000 - 20) / 1023 keV
  ```

### Time-Series Data

If you have 2D time-series data (N intervals × 1023 channels), the API automatically averages across time intervals.

---

## Python Client Example

### Installation

```bash
pip install requests numpy
```

### Complete Example

```python
import requests
import numpy as np
import base64
import io

# API endpoint (via ingress)
API_URL = "http://192.168.86.48:8080"

# 1. Check health
health = requests.get(f"{API_URL}/health").json()
print(f"Status: {health['status']}")

# 2. List available isotopes
isotopes = requests.get(f"{API_URL}/isotopes").json()
print(f"Supported isotopes: {isotopes['total']}")

# 3. Identify isotopes from spectrum array
spectrum = [...]  # Your 1023-channel spectrum data

response = requests.post(
    f"{API_URL}/identify",
    json={
        "spectrum": spectrum,
        "threshold": 0.5,
        "return_all": False
    }
)
result = response.json()

print(f"Detected {result['num_detected']} isotope(s):")
for iso in result['isotopes']:
    print(f"  • {iso['name']}: {iso['probability']:.1%} ({iso['activity_bq']:.1f} Bq)")

# 4. Identify from numpy file
spectrum_np = np.load("my_spectrum.npy")

# Encode for API
buffer = io.BytesIO()
np.save(buffer, spectrum_np.astype(np.float32))
spectrum_b64 = base64.b64encode(buffer.getvalue()).decode('ascii')

response = requests.post(
    f"{API_URL}/identify/b64",
    json={
        "spectrum_b64": spectrum_b64,
        "threshold": 0.3  # Lower threshold for higher sensitivity
    }
)
result = response.json()
print(f"Processing time: {result['processing_time_ms']:.1f} ms")
```

### Using the Client Sample

```bash
cd vega-isotope-identification
python client_sample.py
```

---

## Detection Threshold

The `threshold` parameter controls the sensitivity/specificity trade-off:

| Threshold | Behavior |
|-----------|----------|
| 0.1 - 0.3 | High sensitivity, may have false positives |
| 0.4 - 0.6 | Balanced (default: 0.5) |
| 0.7 - 0.9 | High specificity, may miss weak signals |

**Recommendation:** Start with 0.5, adjust based on your application needs.

---

## Supported Isotopes (82 total)

### Calibration Sources
Am-241, Ba-133, Cs-137, Co-57, Co-60, Eu-152, Na-22, Mn-54

### Medical Isotopes
Tc-99m, F-18, Ga-67, Ga-68, In-111, I-123, I-125, Tl-201, Lu-177

### Industrial Sources
Ir-192, Se-75, Cd-109, I-131, Y-90

### Natural Background
K-40, Ra-226, Th-232, U-238, Rn-222

### Fission Products
Cs-134, Sr-90, Zr-95, Nb-95, Ru-103, Ru-106, Ce-141, Ce-144

### Decay Chain Members
Full U-238, Th-232, and U-235 decay series

---

## Integration with Vega Ingress

### Accessing via Ingress Gateway

The Vega Ingress routes all isotope identification requests:

| Ingress Route | Direct Equivalent |
|---------------|-------------------|
| `GET /isotope/health` | `GET /health` |
| `GET /isotope/info` | `GET /info` |
| `POST /identify` | `POST /identify` |
| `POST /identify/b64` | `POST /identify/b64` |
| `POST /identify/batch` | `POST /identify/batch` |
| `GET /isotopes` | `GET /isotopes` |

### Combined Health Check

The ingress `/health` endpoint includes isotope service status:

```json
{
  "status": "healthy",
  "backends": {
    "tts": {"status": "healthy"},
    "llm": {"status": "healthy"},
    "isotope": {"status": "healthy", "model_loaded": true}
  }
}
```

---

## Error Handling

### Common Errors

| Status Code | Error | Description |
|-------------|-------|-------------|
| 400 | Bad Request | Invalid spectrum shape or parameters |
| 422 | Validation Error | Missing required fields or wrong types |
| 500 | Server Error | Internal processing error |
| 502 | Bad Gateway | Backend service unavailable |
| 503 | Service Unavailable | Model not loaded |
| 504 | Gateway Timeout | Request timeout |

### Example Error Response

```json
{
  "detail": "Expected 1023 channels, got 500"
}
```

---

## Performance

- **Typical inference time:** 10-30 ms on GPU (CUDA)
- **CPU inference:** 50-100 ms
- **Batch processing:** More efficient than individual requests
- **Model loading:** ~5-10 seconds at startup

---

## Deployment

### Docker Deployment

```bash
cd vega-isotope-identification

# Copy model file
mkdir -p models
cp ../sample/vega_v2_final.pt models/

# Build and run
docker compose up -d --build

# Check logs
docker logs vega-isotope -f
```

### Remote Server Deployment

1. Copy `.env.example` to `.env` and configure:
   ```
   SSH_USER=your_username
   SSH_HOST=192.168.86.48
   REMOTE_PATH=/home/your_username/vega-isotope-identification
   API_PORT=8020
   ```

2. Run deployment script:
   ```powershell
   .\deploy.ps1
   ```

---

## Testing

Run the test suite:

```bash
cd vega-isotope-identification/tests
pip install -r requirements.txt
python test_isotope_api.py --host 192.168.86.48 --port 8080
```

---

## Architecture

The model uses a CNN-FCNN (Convolutional + Fully Connected Neural Network):

```
Input (1023 channels)
       ↓
ConvBlock 1: Conv1d(1→64) → BN → LeakyReLU → MaxPool → Dropout
       ↓
ConvBlock 2: Conv1d(64→128) → BN → LeakyReLU → MaxPool → Dropout
       ↓
ConvBlock 3: Conv1d(128→256) → BN → LeakyReLU → MaxPool → Dropout
       ↓
Flatten
       ↓
FC Classifier → 82 isotope probabilities (sigmoid)
       ↓
FC Regressor → 82 activity estimates (Bq)
```

---

## Changelog

### v1.0.0
- Initial release
- 82 isotope classification
- Activity estimation
- Batch processing support
- Base64 numpy input support
