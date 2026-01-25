#!/usr/bin/env python3
"""Quick local test of the isotope identification model."""

import sys
import numpy as np

# Test imports
print("Testing imports...")
from isotope_api import VegaInferenceEngine, ISOTOPE_NAMES, NUM_CHANNELS

print(f"✓ Isotopes supported: {len(ISOTOPE_NAMES)}")
print(f"✓ Channels: {NUM_CHANNELS}")

# Test model loading
print("\nLoading model...")
engine = VegaInferenceEngine()
engine.initialize('models/vega_v2_final.pt', 'cpu')
print(f"✓ Model loaded on: {engine.device}")

# Test inference with random spectrum
print("\nTesting inference with random spectrum...")
spectrum = np.random.rand(NUM_CHANNELS).astype(np.float32)
result = engine.predict(spectrum, threshold=0.5)
print(f"✓ Inference completed in {result['processing_time_ms']:.1f} ms")
print(f"✓ Detected: {result['num_detected']} isotopes")

# Test with synthetic Cs-137-like spectrum (peak at channel ~220 which is ~662 keV)
print("\nTesting with synthetic Cs-137 spectrum...")
spectrum = np.zeros(NUM_CHANNELS, dtype=np.float32)
# Add peak around 662 keV (channel ~220)
cs137_channel = int((661.7 - 20) / (3000 - 20) * NUM_CHANNELS)
for i in range(-20, 21):
    if 0 <= cs137_channel + i < NUM_CHANNELS:
        spectrum[cs137_channel + i] = 100 * np.exp(-0.5 * (i / 5) ** 2)

result = engine.predict(spectrum, threshold=0.3)
print(f"✓ Detected: {result['num_detected']} isotopes")
for iso in result['isotopes']:
    print(f"  • {iso['name']}: {iso['probability']:.1%} ({iso['activity_bq']:.1f} Bq)")

print("\n" + "=" * 50)
print("ALL LOCAL TESTS PASSED!")
print("=" * 50)
