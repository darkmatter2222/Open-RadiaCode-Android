"""
Vega 2D Isotope Identification Client Sample

Demonstrates how to use the Vega 2D Isotope Identification API for gamma spectrum analysis.

The API v2.0 supports 2D spectra (60 time intervals × 1023 energy channels) for improved
isotope identification accuracy. Legacy 1D spectra (1023 channels) are still supported
via the /identify/1d endpoint.

2D Spectrum Format:
    - Shape: (60, 1023) 
    - Axis 0: Time intervals (60 one-second intervals)
    - Axis 1: Energy channels (1023 channels, 20 keV to 3000 keV)

API Version: 2.0.0

NOTE: Activity estimates are approximate and should NOT be used for regulatory compliance, safety limits, or precise quantitative analysis. They are for relative comparison and general survey only.
"""

import requests
import base64
import io
import sys
import json
import math
import random
from typing import Optional, List, Dict, Any, Union

# Default API endpoint (via ingress)
API_URL = "http://192.168.86.48:8080"

# Direct endpoint (bypassing ingress)
DIRECT_API_URL = "http://192.168.86.48:8020"

# Spectrum dimensions (fixed by model architecture)
NUM_CHANNELS = 1023         # Energy channels
NUM_TIME_INTERVALS = 60     # Time dimension (1-second intervals)


def energy_to_channel(energy_kev: float, num_channels: int = NUM_CHANNELS) -> int:
    """Convert energy in keV to channel index."""
    e_min, e_max = 20.0, 3000.0
    channel = int((energy_kev - e_min) / (e_max - e_min) * num_channels)
    return max(0, min(num_channels - 1, channel))


def create_synthetic_spectrum(
    isotope: str = "Cs-137",
    activity_bq: float = 100.0,
    duration_seconds: float = 300.0,
    add_background: bool = True,
    add_noise: bool = True,
    seed: Optional[int] = None,
    return_2d: bool = True
) -> Union[List[float], List[List[float]]]:
    """
    Generate a synthetic gamma spectrum for testing.
    
    Args:
        isotope: Isotope name (e.g., "Cs-137", "Co-60", "Na-22")
        activity_bq: Source activity in Becquerels
        duration_seconds: Measurement duration
        add_background: Add environmental background
        add_noise: Apply Poisson counting statistics
        seed: Random seed for reproducibility
        return_2d: If True, return 2D spectrum (60, 1023). If False, return 1D (1023,)
        
    Returns:
        If return_2d=True: 2D spectrum as list of 60 time intervals, each 1023 channels
        If return_2d=False: 1D spectrum as list of 1023 float values
    """
    if seed is not None:
        random.seed(seed)
    
    # Gamma lines for common isotopes (energy_keV, branching_ratio)
    GAMMA_LINES = {
        "Cs-137": [(661.7, 0.851)],
        "Co-60": [(1173.2, 0.999), (1332.5, 0.999)],
        "Na-22": [(511.0, 1.798), (1274.5, 0.999)],
        "Am-241": [(59.54, 0.359)],
        "Ba-133": [(81.0, 0.329), (356.0, 0.620)],
        "K-40": [(1460.8, 0.107)],
        "Eu-152": [(121.8, 0.284), (344.3, 0.265), (1408.0, 0.210)],
    }
    
    spectrum = [0.0] * NUM_CHANNELS
    detector_fwhm_percent = 8.5
    
    # Get gamma lines for the isotope
    gamma_lines = GAMMA_LINES.get(isotope, GAMMA_LINES["Cs-137"])
    
    # Add peaks for each gamma line
    for energy_kev, branching_ratio in gamma_lines:
        fwhm_kev = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(energy_kev / 662.0)
        sigma_kev = fwhm_kev / 2.355
        
        efficiency = 0.1 * math.exp(-energy_kev / 500.0)
        expected_counts = activity_bq * duration_seconds * branching_ratio * efficiency
        
        center_channel = energy_to_channel(energy_kev)
        
        for ch in range(NUM_CHANNELS):
            energy = 20.0 + ch * (3000.0 - 20.0) / NUM_CHANNELS
            peak = expected_counts * math.exp(-0.5 * ((energy - energy_kev) / sigma_kev) ** 2)
            spectrum[ch] += peak
    
    # Add background continuum
    if add_background:
        for ch in range(NUM_CHANNELS):
            energy = 20.0 + ch * (3000.0 - 20.0) / NUM_CHANNELS
            bg = 50.0 * duration_seconds * math.exp(-energy / 300.0) / 300.0
            spectrum[ch] += bg
    
    # Apply Poisson noise
    if add_noise:
        spectrum = [max(0, random.gauss(c, math.sqrt(max(c, 1)))) for c in spectrum]
    
    # Return 2D or 1D spectrum
    if return_2d:
        return expand_to_2d(spectrum)
    return spectrum


def expand_to_2d(
    spectrum_1d: List[float],
    num_intervals: int = NUM_TIME_INTERVALS,
    add_variation: bool = True,
    seed: Optional[int] = None
) -> List[List[float]]:
    """
    Expand a 1D spectrum to 2D by simulating time-series data.
    
    The 2D model expects 60 time intervals (1 second each) of spectral data.
    This function simulates realistic time variation by adding counting 
    statistics noise to each interval.

    NOTE: The API currently expands 1D spectra by simple repetition (np.tile),
    but the client simulates more realistic time variation. For best results,
    use real time-series data if available.

    Args:
        spectrum_1d: 1D spectrum (1023 channels) - total counts
        num_intervals: Number of time intervals (default 60)
        add_variation: Add realistic counting variation per interval
        seed: Random seed for reproducibility
        
    Returns:
        2D spectrum as list of lists, shape (60, 1023)
    """
    if seed is not None:
        random.seed(seed)
    
    # Divide total counts by number of intervals
    interval_spectrum = [c / num_intervals for c in spectrum_1d]
    
    spectrum_2d = []
    for _ in range(num_intervals):
        if add_variation:
            # Add Poisson-like counting statistics per interval
            interval = [max(0, random.gauss(c, math.sqrt(max(c, 0.1)))) for c in interval_spectrum]
        else:
            interval = interval_spectrum.copy()
        spectrum_2d.append(interval)
    
    return spectrum_2d


def identify_isotopes(
    spectrum: Union[List[float], List[List[float]]],
    threshold: float = 0.5,
    return_all: bool = False,
    use_ingress: bool = True
) -> Dict[str, Any]:
    """
    Identify isotopes from a gamma spectrum.
    
    Automatically detects if input is 1D or 2D and routes to appropriate endpoint.
    
    Args:
        spectrum: 2D spectrum (60×1023) or 1D spectrum (1023 channels)
        threshold: Detection threshold (0-1). Lower = more sensitive
        return_all: If True, return all 82 isotopes. If False, only detected ones.
        use_ingress: If True, use ingress gateway. If False, direct API.
    
    Returns:
        API response with detected isotopes, probabilities, and activities
    """
    base_url = API_URL if use_ingress else DIRECT_API_URL
    
    # Detect if 1D or 2D spectrum
    is_2d = isinstance(spectrum[0], list)
    
    if is_2d:
        endpoint = "/identify"
    else:
        endpoint = "/identify/1d"
    
    payload = {
        "spectrum": spectrum,
        "threshold": threshold,
        "return_all": return_all
    }
    
    response = requests.post(f"{base_url}{endpoint}", json=payload)
    response.raise_for_status()
    return response.json()


def identify_isotopes_b64(
    npy_bytes: bytes,
    threshold: float = 0.5,
    return_all: bool = False,
    use_ingress: bool = True
) -> Dict[str, Any]:
    """
    Identify isotopes from a base64-encoded numpy array.
    
    Args:
        npy_bytes: Bytes of a .npy file (shape: (60, 1023) or (1023,))
        threshold: Detection threshold (0-1)
        return_all: If True, return all 82 isotopes
        use_ingress: If True, use ingress gateway
    
    Returns:
        API response with detected isotopes
    """
    base_url = API_URL if use_ingress else DIRECT_API_URL
    
    payload = {
        "spectrum_b64": base64.b64encode(npy_bytes).decode('ascii'),
        "threshold": threshold,
        "return_all": return_all
    }
    
    response = requests.post(f"{base_url}/identify/b64", json=payload)
    response.raise_for_status()
    return response.json()


def identify_batch(
    spectra: List[List[List[float]]],
    threshold: float = 0.5,
    use_ingress: bool = True
) -> Dict[str, Any]:
    """
    Batch identification for multiple 2D spectra.
    
    Args:
        spectra: List of 2D spectra (each shape: 60×1023)
        threshold: Detection threshold (0-1)
        use_ingress: If True, use ingress gateway
    
    Returns:
        API response with results for each spectrum
    """
    base_url = API_URL if use_ingress else DIRECT_API_URL
    
    payload = {
        "spectra": spectra,
        "threshold": threshold,
        "return_all": False
    }
    
    response = requests.post(f"{base_url}/identify/batch", json=payload)
    response.raise_for_status()
    return response.json()


def get_supported_isotopes(use_ingress: bool = True) -> Dict[str, Any]:
    """Get list of all supported isotopes with gamma lines."""
    base_url = API_URL if use_ingress else DIRECT_API_URL
    response = requests.get(f"{base_url}/isotopes")
    response.raise_for_status()
    return response.json()


def check_health(use_ingress: bool = True) -> Dict[str, Any]:
    """Check API health status."""
    base_url = API_URL if use_ingress else DIRECT_API_URL
    response = requests.get(f"{base_url}/health")
    response.raise_for_status()
    return response.json()


def get_info(use_ingress: bool = True) -> Dict[str, Any]:
    """Get API information."""
    base_url = API_URL if use_ingress else DIRECT_API_URL
    endpoint = "/isotope/info" if use_ingress else "/info"
    response = requests.get(f"{base_url}{endpoint}")
    response.raise_for_status()
    return response.json()


def demo():
    """Run a demonstration of the isotope identification API."""
    print("=" * 70)
    print("VEGA 2D ISOTOPE IDENTIFICATION - CLIENT DEMO (API v2.0)")
    print("=" * 70)
    print(f"Input format: {NUM_TIME_INTERVALS} time intervals × {NUM_CHANNELS} energy channels")
    
    # Check health
    print("\n[1] Checking API health...")
    try:
        health = check_health()
        print(f"    Status: {health.get('status', 'unknown')}")
        if 'backends' in health:
            for name, status in health['backends'].items():
                print(f"    - {name}: {status.get('status', 'unknown')}")
    except Exception as e:
        print(f"    Error: {e}")
        return
    
    # Get supported isotopes
    print("\n[2] Getting supported isotopes...")
    try:
        isotopes = get_supported_isotopes()
        print(f"    Total: {isotopes['total']} isotopes")
        print(f"    Sample: {', '.join(isotopes['isotopes'][:10])}...")
    except Exception as e:
        print(f"    Error: {e}")
    
    # Test with synthetic Cs-137 spectrum (2D)
    print("\n[3] Creating synthetic Cs-137 2D spectrum...")
    spectrum_cs137 = create_synthetic_spectrum("Cs-137", activity_bq=100, seed=42, return_2d=True)
    print(f"    Spectrum shape: ({len(spectrum_cs137)}, {len(spectrum_cs137[0])})")
    total_max = max(max(row) for row in spectrum_cs137)
    print(f"    Max counts per interval: {total_max:.1f}")
    
    # Identify isotopes
    print("\n[4] Identifying isotopes from 2D spectrum...")
    try:
        result = identify_isotopes(spectrum_cs137, threshold=0.5)
        print(f"    Detected: {result['num_detected']} isotope(s)")
        print(f"    Confidence: {result['confidence']:.2%}")
        print(f"    Processing time: {result['processing_time_ms']:.1f} ms")
        print("    Isotopes:")
        for iso in result['isotopes']:
            print(f"      • {iso['name']}: {iso['probability']:.1%} "
                  f"({iso['activity_bq']:.1f} Bq estimated)")
    except Exception as e:
        print(f"    Error: {e}")
    
    # Test with Co-60 (dual peaks) - 1D legacy mode
    print("\n[5] Testing Co-60 with 1D legacy endpoint...")
    spectrum_co60_1d = create_synthetic_spectrum("Co-60", activity_bq=50, seed=123, return_2d=False)
    print(f"    Spectrum length: {len(spectrum_co60_1d)}")
    try:
        result = identify_isotopes(spectrum_co60_1d, threshold=0.3)  # Auto-detects 1D
        print(f"    Detected: {result['num_detected']} isotope(s)")
        for iso in result['isotopes']:
            print(f"      • {iso['name']}: {iso['probability']:.1%}")
    except Exception as e:
        print(f"    Error: {e}")
    
    # Batch test with 2D spectra
    print("\n[6] Testing batch identification with 2D spectra...")
    spectra = [
        create_synthetic_spectrum("Cs-137", seed=1, return_2d=True),
        create_synthetic_spectrum("Na-22", seed=2, return_2d=True),
        create_synthetic_spectrum("K-40", seed=3, return_2d=True),
    ]
    print(f"    Batch size: {len(spectra)} spectra")
    print(f"    Each spectrum: ({len(spectra[0])}, {len(spectra[0][0])})")
    try:
        result = identify_batch(spectra, threshold=0.3)
        print(f"    Processed: {result['total_spectra']} spectra")
        print(f"    Total time: {result['total_processing_time_ms']:.1f} ms")
        for i, res in enumerate(result['results']):
            isotopes = [iso['name'] for iso in res['isotopes']]
            print(f"    Spectrum {i+1}: {', '.join(isotopes) or 'None detected'}")
    except Exception as e:
        print(f"    Error: {e}")
    
    print("\n" + "=" * 70)
    print("DEMO COMPLETE")
    print("=" * 70)


if __name__ == "__main__":
    demo()
