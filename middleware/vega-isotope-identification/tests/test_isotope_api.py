#!/usr/bin/env python3
"""
Vega Isotope Identification API Test Suite

Tests all endpoints of the isotope identification service via the ingress gateway.

Usage:
    python test_isotope_api.py [--host HOST] [--port PORT] [--verbose]
"""

import argparse
import base64
import io
import json
import math
import random
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Any, List, Dict

try:
    import requests
except ImportError:
    print("ERROR: requests library required. Install with: pip install requests")
    sys.exit(1)

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False
    print("WARNING: numpy not found. Base64 tests will be skipped.")

# ==============================================================================
# Configuration
# ==============================================================================

DEFAULT_HOST = "192.168.86.48"
DEFAULT_PORT = 8080  # Ingress port

NUM_CHANNELS = 1023

# Known gamma line energies (keV) for validation
KNOWN_GAMMA_LINES = {
    "Cs-137": 661.7,
    "Co-60": [1173.2, 1332.5],
    "Na-22": [511.0, 1274.5],
    "K-40": 1460.8,
    "Am-241": 59.54,
}

# ==============================================================================
# Test Data Generation
# ==============================================================================

def energy_to_channel(energy_kev: float, num_channels: int = NUM_CHANNELS) -> int:
    """Convert energy in keV to channel index."""
    e_min, e_max = 20.0, 3000.0
    channel = int((energy_kev - e_min) / (e_max - e_min) * num_channels)
    return max(0, min(num_channels - 1, channel))


def create_test_spectrum(
    isotope: str = "Cs-137",
    activity_bq: float = 100.0,
    duration_seconds: float = 300.0,
    add_background: bool = True,
    add_noise: bool = True,
    seed: Optional[int] = None
) -> List[float]:
    """Generate a synthetic gamma spectrum for testing."""
    if seed is not None:
        random.seed(seed)
    
    GAMMA_LINES = {
        "Cs-137": [(661.7, 0.851)],
        "Co-60": [(1173.2, 0.999), (1332.5, 0.999)],
        "Na-22": [(511.0, 1.798), (1274.5, 0.999)],
        "Am-241": [(59.54, 0.359)],
        "Ba-133": [(81.0, 0.329), (356.0, 0.620)],
        "K-40": [(1460.8, 0.107)],
        "Eu-152": [(121.8, 0.284), (344.3, 0.265), (1408.0, 0.210)],
        "I-131": [(364.5, 0.817)],
        "Tc-99m": [(140.5, 0.890)],
    }
    
    spectrum = [0.0] * NUM_CHANNELS
    detector_fwhm_percent = 8.5
    
    gamma_lines = GAMMA_LINES.get(isotope, GAMMA_LINES["Cs-137"])
    
    for energy_kev, branching_ratio in gamma_lines:
        fwhm_kev = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(energy_kev / 662.0)
        sigma_kev = fwhm_kev / 2.355
        efficiency = 0.1 * math.exp(-energy_kev / 500.0)
        expected_counts = activity_bq * duration_seconds * branching_ratio * efficiency
        
        for ch in range(NUM_CHANNELS):
            energy = 20.0 + ch * (3000.0 - 20.0) / NUM_CHANNELS
            peak = expected_counts * math.exp(-0.5 * ((energy - energy_kev) / sigma_kev) ** 2)
            spectrum[ch] += peak
    
    if add_background:
        for ch in range(NUM_CHANNELS):
            energy = 20.0 + ch * (3000.0 - 20.0) / NUM_CHANNELS
            bg = 50.0 * duration_seconds * math.exp(-energy / 300.0) / 300.0
            spectrum[ch] += bg
    
    if add_noise:
        spectrum = [max(0, random.gauss(c, math.sqrt(max(c, 1)))) for c in spectrum]
    
    return spectrum


# ==============================================================================
# Test Results
# ==============================================================================

@dataclass
class TestResult:
    name: str
    passed: bool
    duration_ms: float
    message: str
    response_data: Optional[dict] = None
    request_data: Optional[dict] = field(default=None)
    endpoint: str = ""
    method: str = ""


class TestRunner:
    def __init__(self, base_url: str, verbose: bool = False):
        self.base_url = base_url
        self.verbose = verbose
        self.results: List[TestResult] = []
    
    def _make_request(self, method: str, endpoint: str, **kwargs) -> requests.Response:
        """Make HTTP request to the API."""
        url = f"{self.base_url}{endpoint}"
        return requests.request(method, url, timeout=60, **kwargs)
    
    def _test(self, name: str, method: str, endpoint: str,
              expected_status: int = 200, **kwargs) -> TestResult:
        """Run a single test."""
        request_payload = kwargs.get('json', kwargs.get('data', None))
        
        start = time.perf_counter()
        try:
            response = self._make_request(method, endpoint, **kwargs)
            duration = (time.perf_counter() - start) * 1000
            
            passed = response.status_code == expected_status
            try:
                data = response.json()
            except:
                data = {"raw": response.text[:500] if response.text else None}
            
            if passed:
                msg = f"✓ {response.status_code} OK ({duration:.0f}ms)"
            else:
                msg = f"✗ Expected {expected_status}, got {response.status_code}"
            
            result = TestResult(name, passed, duration, msg, data, request_payload, endpoint, method)
            
        except requests.exceptions.ConnectionError as e:
            duration = (time.perf_counter() - start) * 1000
            result = TestResult(name, False, duration, f"✗ Connection error: {e}", None, request_payload, endpoint, method)
        except Exception as e:
            duration = (time.perf_counter() - start) * 1000
            result = TestResult(name, False, duration, f"✗ Error: {e}", None, request_payload, endpoint, method)
        
        self.results.append(result)
        return result

    # ==========================================================================
    # Health & Info Tests
    # ==========================================================================
    
    def test_health(self) -> TestResult:
        """Test /health endpoint."""
        result = self._test("Health Check", "GET", "/health")
        if result.passed and result.response_data:
            backends = result.response_data.get("backends", {})
            isotope_status = backends.get("isotope", {}).get("status", "unknown")
            result.message += f" [Isotope: {isotope_status}]"
        return result
    
    def test_info(self) -> TestResult:
        """Test /info endpoint."""
        return self._test("Info", "GET", "/info")
    
    def test_isotope_health(self) -> TestResult:
        """Test isotope service health via proxy."""
        return self._test("Isotope Health (via proxy)", "GET", "/isotope/health")
    
    def test_isotope_info(self) -> TestResult:
        """Test isotope service info via proxy."""
        return self._test("Isotope Info (via proxy)", "GET", "/isotope/info")
    
    # ==========================================================================
    # Isotope List Tests
    # ==========================================================================
    
    def test_isotopes_list(self) -> TestResult:
        """Test /isotopes endpoint."""
        result = self._test("List Isotopes", "GET", "/isotopes")
        if result.passed and result.response_data:
            total = result.response_data.get("total", 0)
            result.message += f" [{total} isotopes]"
            if total != 82:
                result.passed = False
                result.message += " (Expected 82!)"
        return result
    
    # ==========================================================================
    # Basic Identification Tests
    # ==========================================================================
    
    def test_identify_cs137(self) -> TestResult:
        """Test identification of Cs-137."""
        spectrum = create_test_spectrum("Cs-137", activity_bq=100, seed=42)
        
        result = self._test(
            "Identify Cs-137",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.5, "return_all": False}
        )
        
        if result.passed and result.response_data:
            detected = result.response_data.get("isotopes", [])
            cs137_found = any(iso["name"] == "Cs-137" and iso["present"] for iso in detected)
            num_detected = result.response_data.get("num_detected", 0)
            processing_time = result.response_data.get("processing_time_ms", 0)
            
            if cs137_found:
                result.message += f" [Cs-137 detected, {num_detected} total, {processing_time:.0f}ms]"
            else:
                result.passed = False
                result.message += " [Cs-137 NOT detected!]"
        
        return result
    
    def test_identify_co60(self) -> TestResult:
        """Test identification of Co-60 (dual peak isotope)."""
        spectrum = create_test_spectrum("Co-60", activity_bq=80, seed=123)
        
        result = self._test(
            "Identify Co-60",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.3, "return_all": False}
        )
        
        if result.passed and result.response_data:
            detected = result.response_data.get("isotopes", [])
            co60_found = any(iso["name"] == "Co-60" for iso in detected)
            if co60_found:
                iso = next(i for i in detected if i["name"] == "Co-60")
                result.message += f" [Co-60: {iso['probability']:.1%}]"
            else:
                result.message += " [Co-60 not in top results]"
        
        return result
    
    def test_identify_na22(self) -> TestResult:
        """Test identification of Na-22 (positron emitter with annihilation peak)."""
        spectrum = create_test_spectrum("Na-22", activity_bq=60, seed=456)
        
        result = self._test(
            "Identify Na-22",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.3, "return_all": False}
        )
        
        if result.passed and result.response_data:
            detected = result.response_data.get("isotopes", [])
            na22_found = any(iso["name"] == "Na-22" for iso in detected)
            result.message += f" [Na-22: {'detected' if na22_found else 'not detected'}]"
        
        return result
    
    def test_identify_k40(self) -> TestResult:
        """Test identification of K-40 (natural background isotope)."""
        spectrum = create_test_spectrum("K-40", activity_bq=200, seed=789)
        
        result = self._test(
            "Identify K-40",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.3, "return_all": False}
        )
        
        if result.passed and result.response_data:
            detected = result.response_data.get("isotopes", [])
            k40_found = any(iso["name"] == "K-40" for iso in detected)
            result.message += f" [K-40: {'detected' if k40_found else 'not detected'}]"
        
        return result
    
    # ==========================================================================
    # Threshold Tests
    # ==========================================================================
    
    def test_identify_low_threshold(self) -> TestResult:
        """Test with low threshold (more sensitive)."""
        spectrum = create_test_spectrum("Cs-137", activity_bq=20, seed=111)  # Low activity
        
        result = self._test(
            "Identify (low threshold=0.2)",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.2, "return_all": False}
        )
        
        if result.passed and result.response_data:
            num_detected = result.response_data.get("num_detected", 0)
            result.message += f" [{num_detected} isotopes detected]"
        
        return result
    
    def test_identify_high_threshold(self) -> TestResult:
        """Test with high threshold (more specific)."""
        spectrum = create_test_spectrum("Cs-137", activity_bq=200, seed=222)  # High activity
        
        result = self._test(
            "Identify (high threshold=0.8)",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.8, "return_all": False}
        )
        
        if result.passed and result.response_data:
            num_detected = result.response_data.get("num_detected", 0)
            result.message += f" [{num_detected} isotopes detected]"
        
        return result
    
    def test_identify_return_all(self) -> TestResult:
        """Test return_all=True to get all 82 isotopes."""
        spectrum = create_test_spectrum("Cs-137", activity_bq=100, seed=333)
        
        result = self._test(
            "Identify (return_all=True)",
            "POST",
            "/identify",
            json={"spectrum": spectrum, "threshold": 0.5, "return_all": True}
        )
        
        if result.passed and result.response_data:
            num_returned = len(result.response_data.get("isotopes", []))
            if num_returned == 82:
                result.message += f" [All 82 isotopes returned]"
            else:
                result.passed = False
                result.message += f" [Expected 82, got {num_returned}]"
        
        return result
    
    # ==========================================================================
    # Base64 Encoding Tests
    # ==========================================================================
    
    def test_identify_b64(self) -> TestResult:
        """Test identification with base64-encoded numpy array."""
        if not HAS_NUMPY:
            return TestResult("Identify B64", False, 0, "⊘ Skipped (numpy not installed)")
        
        spectrum = create_test_spectrum("Cs-137", activity_bq=100, seed=42)
        spectrum_np = np.array(spectrum, dtype=np.float32)
        
        # Encode as .npy bytes
        buffer = io.BytesIO()
        np.save(buffer, spectrum_np)
        npy_bytes = buffer.getvalue()
        spectrum_b64 = base64.b64encode(npy_bytes).decode('ascii')
        
        result = self._test(
            "Identify B64 (numpy)",
            "POST",
            "/identify/b64",
            json={"spectrum_b64": spectrum_b64, "threshold": 0.5, "return_all": False}
        )
        
        if result.passed and result.response_data:
            detected = result.response_data.get("isotopes", [])
            cs137_found = any(iso["name"] == "Cs-137" for iso in detected)
            result.message += f" [Cs-137: {'detected' if cs137_found else 'not detected'}]"
        
        return result
    
    # ==========================================================================
    # Batch Tests
    # ==========================================================================
    
    def test_identify_batch(self) -> TestResult:
        """Test batch identification."""
        spectra = [
            create_test_spectrum("Cs-137", seed=1),
            create_test_spectrum("Co-60", seed=2),
            create_test_spectrum("Na-22", seed=3),
        ]
        
        result = self._test(
            "Batch Identification",
            "POST",
            "/identify/batch",
            json={"spectra": spectra, "threshold": 0.3, "return_all": False}
        )
        
        if result.passed and result.response_data:
            total = result.response_data.get("total_spectra", 0)
            total_time = result.response_data.get("total_processing_time_ms", 0)
            result.message += f" [{total} spectra, {total_time:.0f}ms total]"
        
        return result
    
    # ==========================================================================
    # Error Handling Tests
    # ==========================================================================
    
    def test_identify_wrong_length(self) -> TestResult:
        """Test with wrong spectrum length (should fail)."""
        spectrum = [0.0] * 500  # Wrong length
        
        result = self._test(
            "Identify (wrong length)",
            "POST",
            "/identify",
            expected_status=422,  # Validation error
            json={"spectrum": spectrum, "threshold": 0.5}
        )
        
        return result
    
    def test_identify_invalid_threshold(self) -> TestResult:
        """Test with invalid threshold (should fail)."""
        spectrum = create_test_spectrum("Cs-137", seed=999)
        
        result = self._test(
            "Identify (invalid threshold=2.0)",
            "POST",
            "/identify",
            expected_status=422,
            json={"spectrum": spectrum, "threshold": 2.0}
        )
        
        return result
    
    def test_identify_empty_spectrum(self) -> TestResult:
        """Test with empty spectrum (should fail)."""
        result = self._test(
            "Identify (empty spectrum)",
            "POST",
            "/identify",
            expected_status=422,
            json={"spectrum": [], "threshold": 0.5}
        )
        
        return result
    
    # ==========================================================================
    # Performance Tests
    # ==========================================================================
    
    def test_identify_performance(self) -> TestResult:
        """Test inference performance."""
        spectrum = create_test_spectrum("Cs-137", seed=42)
        
        # Run 5 times and measure
        times = []
        for i in range(5):
            start = time.perf_counter()
            try:
                response = self._make_request("POST", "/identify", json={
                    "spectrum": spectrum,
                    "threshold": 0.5,
                    "return_all": False
                })
                if response.status_code == 200:
                    data = response.json()
                    times.append(data.get("processing_time_ms", 0))
            except:
                pass
        
        if times:
            avg_time = sum(times) / len(times)
            result = TestResult(
                "Performance (5 runs)",
                True,
                avg_time,
                f"✓ Avg: {avg_time:.1f}ms, Min: {min(times):.1f}ms, Max: {max(times):.1f}ms"
            )
        else:
            result = TestResult("Performance (5 runs)", False, 0, "✗ All requests failed")
        
        self.results.append(result)
        return result
    
    # ==========================================================================
    # Run All Tests
    # ==========================================================================
    
    def run_all(self):
        """Run all tests."""
        print("\n" + "=" * 70)
        print("VEGA ISOTOPE IDENTIFICATION API TEST SUITE")
        print(f"Target: {self.base_url}")
        print("=" * 70)
        
        sections = [
            ("Health & Info", [
                self.test_health,
                self.test_info,
                self.test_isotope_health,
                self.test_isotope_info,
            ]),
            ("Isotope List", [
                self.test_isotopes_list,
            ]),
            ("Basic Identification", [
                self.test_identify_cs137,
                self.test_identify_co60,
                self.test_identify_na22,
                self.test_identify_k40,
            ]),
            ("Threshold Tests", [
                self.test_identify_low_threshold,
                self.test_identify_high_threshold,
                self.test_identify_return_all,
            ]),
            ("Base64 Encoding", [
                self.test_identify_b64,
            ]),
            ("Batch Processing", [
                self.test_identify_batch,
            ]),
            ("Error Handling", [
                self.test_identify_wrong_length,
                self.test_identify_invalid_threshold,
                self.test_identify_empty_spectrum,
            ]),
            ("Performance", [
                self.test_identify_performance,
            ]),
        ]
        
        for section_name, tests in sections:
            print(f"\n[{section_name}]")
            print("-" * 50)
            
            for test_fn in tests:
                result = test_fn()
                status = "✓" if result.passed else "✗"
                print(f"  {status} {result.name}: {result.message}")
                
                if self.verbose and result.response_data:
                    print(f"      Response: {json.dumps(result.response_data, indent=2)[:500]}")
        
        # Summary
        print("\n" + "=" * 70)
        print("SUMMARY")
        print("=" * 70)
        
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)
        
        print(f"  Total:  {total}")
        print(f"  Passed: {passed} ({passed/total*100:.1f}%)")
        print(f"  Failed: {failed}")
        
        if failed > 0:
            print("\n  Failed tests:")
            for r in self.results:
                if not r.passed:
                    print(f"    • {r.name}: {r.message}")
        
        print("=" * 70)
        
        return failed == 0


def main():
    parser = argparse.ArgumentParser(description="Vega Isotope Identification API Test Suite")
    parser.add_argument("--host", default=DEFAULT_HOST, help="API host")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="API port")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose output")
    args = parser.parse_args()
    
    base_url = f"http://{args.host}:{args.port}"
    runner = TestRunner(base_url, verbose=args.verbose)
    
    success = runner.run_all()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
