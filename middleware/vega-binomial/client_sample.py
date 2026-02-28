#!/usr/bin/env python3
"""
Sample client for Vega Bulk Binomial Detection API.

Usage:
    python client_sample.py
    python client_sample.py --url http://your-server:8021
    python client_sample.py --csv path/to/spectrum.csv
"""

import argparse
import json
import time
import numpy as np
import httpx

DEFAULT_URL = "http://localhost:8021"


def generate_synthetic_spectrum(
    num_time: int = 300,
    num_channels: int = 1023,
    add_peaks: bool = True
) -> np.ndarray:
    """Generate a synthetic spectrum for testing."""
    # Background noise (Poisson-distributed)
    spectrum = np.random.poisson(lam=10, size=(num_time, num_channels)).astype(float)
    
    if add_peaks:
        # Add Cs-137 peak around 662 keV (approximately channel 220 with typical calibration)
        cs137_channel = 220
        for t in range(num_time):
            spectrum[t, cs137_channel - 3 : cs137_channel + 4] += np.random.poisson(50)
        
        # Add K-40 peak around 1461 keV (approximately channel 500)
        k40_channel = 500
        for t in range(num_time):
            spectrum[t, k40_channel - 3 : k40_channel + 4] += np.random.poisson(30)
    
    return spectrum


def load_csv_spectrum(csv_path: str) -> np.ndarray:
    """Load spectrum from CSV file."""
    try:
        return np.loadtxt(csv_path, delimiter=",", skiprows=1, dtype=float)
    except Exception:
        return np.genfromtxt(csv_path, delimiter=",", skip_header=1, dtype=float)


def main():
    parser = argparse.ArgumentParser(description="Test Vega Bulk Binomial Detection API")
    parser.add_argument("--url", default=DEFAULT_URL, help="API base URL")
    parser.add_argument("--csv", type=str, help="Path to spectrum CSV file")
    parser.add_argument("--isotopes", type=str, nargs="*", help="Specific isotopes to check")
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    client = httpx.Client(timeout=60.0)

    print("=" * 60)
    print("Vega Bulk Binomial Detection API - Sample Client")
    print("=" * 60)
    print(f"URL: {base_url}")
    print()

    # Health check
    print("1. Health Check")
    print("-" * 40)
    try:
        resp = client.get(f"{base_url}/health")
        health = resp.json()
        print(f"   Status: {health['status']}")
        print(f"   Models loaded: {health['models_loaded']}")
        print(f"   Device: {health['device']}")
    except Exception as e:
        print(f"   ERROR: {e}")
        return 1
    print()

    # Get info
    print("2. Service Info")
    print("-" * 40)
    try:
        resp = client.get(f"{base_url}/info")
        info = resp.json()
        print(f"   Version: {info['version']}")
        print(f"   Model version: {info['model_version']}")
        print(f"   Loaded models: {info['loaded_count']}")
        print(f"   Failed models: {info['failed_count']}")
        if info.get('cuda_device_name'):
            print(f"   GPU: {info['cuda_device_name']}")
    except Exception as e:
        print(f"   ERROR: {e}")
    print()

    # List isotopes
    print("3. Available Isotopes")
    print("-" * 40)
    try:
        resp = client.get(f"{base_url}/isotopes")
        isotopes = resp.json()
        print(f"   Loaded: {isotopes['loaded_count']}")
        if isotopes['loaded_count'] > 0:
            sample = [i['isotope'] for i in isotopes['loaded'][:10]]
            print(f"   Sample: {', '.join(sample)}...")
    except Exception as e:
        print(f"   ERROR: {e}")
    print()

    # Prepare spectrum
    print("4. Detection Test")
    print("-" * 40)
    
    if args.csv:
        print(f"   Loading spectrum from: {args.csv}")
        spectrum = load_csv_spectrum(args.csv)
    else:
        print("   Generating synthetic spectrum (with Cs-137 and K-40 peaks)...")
        spectrum = generate_synthetic_spectrum()
    
    print(f"   Spectrum shape: {spectrum.shape}")
    print(f"   Total counts: {np.sum(spectrum):.0f}")
    print()

    # Detection request
    print("5. Running Detection")
    print("-" * 40)
    
    # Typical RadiaCode 110 calibration
    calibration = {
        "a0": 3.5093544,
        "a1": 2.3624456,
        "a2": 4.0645464e-4
    }
    
    request_data = {
        "spectrum": spectrum.tolist(),
        "calibration": calibration,
        "isotopes": args.isotopes  # None = all
    }
    
    start = time.perf_counter()
    try:
        resp = client.post(f"{base_url}/detect", json=request_data)
        elapsed = (time.perf_counter() - start) * 1000
        
        if resp.status_code == 200:
            result = resp.json()
            
            print(f"   Request ID: {result['request_id']}")
            print(f"   Total time: {result['total_elapsed_ms']:.1f}ms")
            print()
            
            print("   Preprocessing:")
            prep = result['preprocessing']
            print(f"      Translate: {prep['translate_ms']:.1f}ms")
            print(f"      Normalize: {prep['normalize_ms']:.1f}ms")
            print(f"      Counts before: {prep['total_counts_before']:.0f}")
            print(f"      Counts after: {prep['total_counts_after']:.0f}")
            print()
            
            print("   Inference:")
            inf = result['inference']
            print(f"      Models run: {inf['models_run']}")
            print(f"      Total time: {inf['total_inference_ms']:.1f}ms")
            print()
            
            print("   Detected Isotopes:")
            if result['detected_isotopes']:
                for iso in sorted(result['detected_isotopes']):
                    r = result['results'][iso]
                    print(f"      {iso}: prob={r['probability']:.4f}, threshold={r['threshold']:.4f}")
            else:
                print("      (none)")
            print()
            
            # Show top probabilities
            print("   Top 10 Probabilities:")
            sorted_results = sorted(
                [(k, v['probability']) for k, v in result['results'].items() if v.get('probability') is not None],
                key=lambda x: x[1],
                reverse=True
            )[:10]
            for iso, prob in sorted_results:
                r = result['results'][iso]
                detected = "DETECTED" if r.get('detected') else ""
                print(f"      {iso}: {prob:.4f} {detected}")
            
        else:
            print(f"   ERROR: HTTP {resp.status_code}")
            print(f"   {resp.text}")
            
    except Exception as e:
        print(f"   ERROR: {e}")

    print()
    print("=" * 60)
    print("Done!")
    return 0


if __name__ == "__main__":
    exit(main())
