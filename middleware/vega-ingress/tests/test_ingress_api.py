#!/usr/bin/env python3
"""
Vega Ingress API Test Suite

Tests all endpoints via the ingress API gateway.

Usage:
    python test_ingress_api.py [--host HOST] [--port PORT] [--save-audio] [--verbose]
"""

import argparse
import base64
import json
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Any

try:
    import requests
except ImportError:
    print("ERROR: requests library required. Install with: pip install requests")
    sys.exit(1)

# ==============================================================================
# Configuration
# ==============================================================================

DEFAULT_HOST = "192.168.86.48"
DEFAULT_PORT = 8080

# Test data
TEST_TEXT_SHORT = "Hello, I am Vega."
TEST_TEXT_MEDIUM = "The quick brown fox jumps over the lazy dog. This is a test of the voice synthesis system."
TEST_CHAT_MESSAGE = "What is the capital of France?"
TEST_GENERATE_PROMPT = "Once upon a time, in a land far away,"

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
    def __init__(self, base_url: str, save_audio: bool = False, verbose: bool = False):
        self.base_url = base_url
        self.save_audio = save_audio
        self.verbose = verbose
        self.results: list[TestResult] = []
        self.output_dir = Path(__file__).parent / "output"
        
        if save_audio:
            self.output_dir.mkdir(exist_ok=True)
    
    def _make_request(self, method: str, endpoint: str, **kwargs) -> requests.Response:
        """Make HTTP request to the API."""
        url = f"{self.base_url}{endpoint}"
        return requests.request(method, url, **kwargs)
    
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
    
    def _format_json(self, data: Any, max_length: int = 2000) -> str:
        """Format JSON data for display, truncating if needed."""
        if data is None:
            return "None"
        try:
            formatted = json.dumps(data, indent=2, ensure_ascii=False)
            if len(formatted) > max_length:
                return formatted[:max_length] + "\n... [truncated]"
            return formatted
        except:
            return str(data)[:max_length]
    
    def _truncate_audio_in_response(self, data: dict) -> dict:
        """Create a copy of response with audio data truncated for display."""
        if data is None:
            return None
        result = {}
        for key, value in data.items():
            if key in ('audio_base64', 'audio') and isinstance(value, str) and len(value) > 100:
                result[key] = f"[{len(value)} chars - audio data]"
            elif isinstance(value, dict):
                result[key] = self._truncate_audio_in_response(value)
            else:
                result[key] = value
        return result

    # ==========================================================================
    # Health & Info Tests
    # ==========================================================================
    
    def test_health(self) -> TestResult:
        """Test /health endpoint."""
        result = self._test("Health Check", "GET", "/health")
        if result.passed and result.response_data:
            backends = result.response_data.get("backends", {})
            tts_status = backends.get("tts", {}).get("status", "unknown")
            llm_status = backends.get("llm", {}).get("status", "unknown")
            result.message += f" [TTS: {tts_status}, LLM: {llm_status}]"
        return result
    
    def test_info(self) -> TestResult:
        """Test /info endpoint."""
        return self._test("Info", "GET", "/info")
    
    # ==========================================================================
    # TTS Health & Info (via proxy)
    # ==========================================================================
    
    def test_tts_health(self) -> TestResult:
        """Test /tts/health endpoint."""
        result = self._test("TTS Health (via ingress)", "GET", "/tts/health")
        if result.passed and result.response_data:
            model_loaded = result.response_data.get("model_loaded", False)
            device = result.response_data.get("device", "unknown")
            result.message += f" [model_loaded: {model_loaded}, device: {device}]"
        return result
    
    def test_tts_info(self) -> TestResult:
        """Test /tts/info endpoint."""
        return self._test("TTS Info (via ingress)", "GET", "/tts/info")
    
    # ==========================================================================
    # LLM Health & Info (via proxy)
    # ==========================================================================
    
    def test_llm_health(self) -> TestResult:
        """Test /llm/health endpoint."""
        result = self._test("LLM Health (via ingress)", "GET", "/llm/health")
        if result.passed and result.response_data:
            model_id = result.response_data.get("model_id", "unknown")
            result.message += f" [model: {model_id}]"
        return result
    
    def test_llm_info(self) -> TestResult:
        """Test /llm/info endpoint."""
        return self._test("LLM Info (via ingress)", "GET", "/llm/info")
    
    # ==========================================================================
    # TTS Synthesis Tests
    # ==========================================================================
    
    def test_synthesize_root(self) -> TestResult:
        """Test /synthesize endpoint (root shortcut)."""
        result = self._test(
            "Synthesize (root)",
            "POST",
            "/synthesize",
            json={"text": TEST_TEXT_SHORT},
            timeout=120
        )
        # Note: May return 500 if HuggingFace token not configured
        if not result.passed and result.response_data:
            detail = result.response_data.get("detail", "")
            if "Token is required" in str(detail) or "token" in str(detail).lower():
                result.message += " [HF token required - config issue]"
        elif result.passed and result.response_data:
            result.message += " [WAV audio returned]"
        return result
    
    def test_synthesize_via_tts(self) -> TestResult:
        """Test /tts/synthesize endpoint."""
        result = self._test(
            "Synthesize (via /tts)",
            "POST",
            "/tts/synthesize",
            json={"text": TEST_TEXT_SHORT},
            timeout=120
        )
        return result
    
    def test_synthesize_b64_root(self) -> TestResult:
        """Test /synthesize/b64 endpoint (base64 response)."""
        result = self._test(
            "Synthesize Base64 (root)",
            "POST",
            "/synthesize/b64",
            json={"text": TEST_TEXT_SHORT},
            timeout=120
        )
        if result.passed and result.response_data:
            audio_b64 = result.response_data.get("audio_base64", "")
            duration = result.response_data.get("duration_seconds", 0)
            gen_time = result.response_data.get("generation_time_seconds", 0)
            result.message += f" [duration: {duration:.2f}s, gen_time: {gen_time:.2f}s]"
            
            # Save audio if requested
            if self.save_audio and audio_b64:
                audio_path = self.output_dir / "test_synthesize_b64.wav"
                audio_path.write_bytes(base64.b64decode(audio_b64))
                result.message += f" [saved: {audio_path.name}]"
        return result
    
    def test_synthesize_b64_via_tts(self) -> TestResult:
        """Test /tts/synthesize/b64 endpoint."""
        result = self._test(
            "Synthesize Base64 (via /tts)",
            "POST",
            "/tts/synthesize/b64",
            json={"text": TEST_TEXT_MEDIUM},
            timeout=120
        )
        if result.passed and result.response_data:
            duration = result.response_data.get("duration_seconds", 0)
            result.message += f" [duration: {duration:.2f}s]"
            
            if self.save_audio:
                audio_b64 = result.response_data.get("audio_base64", "")
                if audio_b64:
                    audio_path = self.output_dir / "test_synthesize_medium.wav"
                    audio_path.write_bytes(base64.b64decode(audio_b64))
        return result
    
    # ==========================================================================
    # LLM Tests
    # ==========================================================================
    
    def test_chat_root(self) -> TestResult:
        """Test /chat endpoint (root shortcut)."""
        result = self._test(
            "Chat (root)",
            "POST",
            "/chat",
            json={
                "messages": [
                    {"role": "user", "content": TEST_CHAT_MESSAGE}
                ]
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response_text = result.response_data.get("response", "")[:100]
            tokens = result.response_data.get("tokens_generated", 0)
            result.message += f" [tokens: {tokens}, response: '{response_text}...']"
        return result
    
    def test_chat_via_llm(self) -> TestResult:
        """Test /llm/chat endpoint."""
        result = self._test(
            "Chat (via /llm)",
            "POST",
            "/llm/chat",
            json={
                "messages": [
                    {"role": "system", "content": "You are a helpful assistant. Be brief."},
                    {"role": "user", "content": "What is 2 + 2?"}
                ],
                "max_tokens": 50,
                "temperature": 0.3
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response_text = result.response_data.get("response", "")[:80]
            result.message += f" [response: '{response_text}']"
        return result
    
    def test_generate_root(self) -> TestResult:
        """Test /generate endpoint (root shortcut)."""
        result = self._test(
            "Generate (root)",
            "POST",
            "/generate",
            json={
                "prompt": TEST_GENERATE_PROMPT,
                "max_tokens": 100
            },
            timeout=60
        )
        if result.passed and result.response_data:
            text = result.response_data.get("text", "")[:100]
            tokens = result.response_data.get("tokens_generated", 0)
            result.message += f" [tokens: {tokens}]"
        return result
    
    def test_generate_via_llm(self) -> TestResult:
        """Test /llm/generate endpoint."""
        result = self._test(
            "Generate (via /llm)",
            "POST",
            "/llm/generate",
            json={
                "prompt": "The meaning of life is",
                "max_tokens": 50,
                "temperature": 0.5
            },
            timeout=60
        )
        return result
    
    # ==========================================================================
    # Spectrogram Analysis Tests (VEGA Radiological Assistant)
    # ==========================================================================
    
    def test_spectrogram_text(self) -> TestResult:
        """Test /spectrogram endpoint with text format."""
        result = self._test(
            "Spectrogram (text format)",
            "POST",
            "/spectrogram",
            json={
                "query": "What is the current radiation status?",
                "response_format": "text",
                "dose_rate": 0.142,
                "cps": 12.5
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "")[:80]
            result.message += f" [VEGA: '{response}...']"
        return result
    
    def test_spectrogram_speech(self) -> TestResult:
        """Test /spectrogram endpoint with speech format."""
        result = self._test(
            "Spectrogram (speech format)",
            "POST",
            "/spectrogram",
            json={
                "query": "Describe the current dose rate",
                "response_format": "speech",
                "dose_rate": 0.25
            },
            timeout=60
        )
        if result.passed and result.response_data:
            fmt = result.response_data.get("response_format", "")
            result.message += f" [format: {fmt}]"
        return result
    
    def test_spectrogram_json(self) -> TestResult:
        """Test /spectrogram endpoint with JSON structured output."""
        json_schema = json.dumps({
            "type": "object",
            "properties": {
                "status": {"type": "string"},
                "dose_rate_usv_hr": {"type": "number"},
                "safety_level": {"type": "string"},
                "recommendation": {"type": "string"}
            }
        })
        result = self._test(
            "Spectrogram (JSON format)",
            "POST",
            "/spectrogram",
            json={
                "query": "Provide a structured analysis of the radiation data",
                "response_format": "json",
                "dose_rate": 0.35,
                "cps": 28.7,
                "total_counts": 1720,
                "measurement_duration": 60.0,
                "json_schema": json_schema
            },
            timeout=60
        )
        if result.passed and result.response_data:
            fmt = result.response_data.get("response_format", "")
            result.message += f" [format: {fmt}]"
        return result
    
    def test_spectrogram_full_data(self) -> TestResult:
        """Test /spectrogram with all available data fields including spectrum."""
        # Simulated spectrum data (256 channels with a Cs-137 peak at channel 180)
        spectrum_data = [0] * 256
        for i in range(256):
            # Background noise
            spectrum_data[i] = max(0, int(5 + (50 - abs(i - 180)) * 2) if abs(i - 180) < 30 else 2)
        
        result = self._test(
            "Spectrogram (full spectrum data)",
            "POST",
            "/spectrogram",
            json={
                "query": "Analyze this gamma spectrum and identify any isotopes present. Is this a Cs-137 source?",
                "response_format": "text",
                "dose_rate": 1.85,
                "cps": 145.2,
                "total_counts": 8712,
                "measurement_duration": 60.0,
                "spectrum_data": spectrum_data
            },
            timeout=90
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "")[:100]
            result.message += f" [VEGA: '{response}...']"
        return result
    
    def test_spectrogram_high_radiation(self) -> TestResult:
        """Test /spectrogram with high radiation scenario for safety advisory."""
        result = self._test(
            "Spectrogram (high radiation alert)",
            "POST",
            "/spectrogram",
            json={
                "query": "Is this radiation level dangerous? What should I do?",
                "response_format": "text",
                "dose_rate": 125.0,  # High dose rate - above 100 µSv/h threshold
                "cps": 8500.0
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "")
            # Check if VEGA provides safety advisory for high radiation
            if any(word in response.lower() for word in ["caution", "warning", "evacuate", "distance", "safe", "danger"]):
                result.message += " [Safety advisory detected]"
            else:
                result.message += f" [Response: '{response[:60]}...']"
        return result
    
    def test_spectrogram_low_radiation(self) -> TestResult:
        """Test /spectrogram with background radiation levels."""
        result = self._test(
            "Spectrogram (background levels)",
            "POST",
            "/spectrogram",
            json={
                "query": "Is this normal background radiation?",
                "response_format": "text",
                "dose_rate": 0.08,  # Normal background ~0.05-0.2 µSv/h
                "cps": 3.2
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "")[:80]
            result.message += f" [VEGA: '{response}...']"
        return result
    
    def test_spectrogram_via_llm(self) -> TestResult:
        """Test /llm/spectrogram endpoint (proxy path)."""
        result = self._test(
            "Spectrogram (via /llm proxy)",
            "POST",
            "/llm/spectrogram",
            json={
                "query": "Is this radiation level safe?",
                "response_format": "text",
                "dose_rate": 0.08,
                "cps": 5.2
            },
            timeout=60
        )
        return result
    
    def test_spectrogram_minimal_query(self) -> TestResult:
        """Test /spectrogram with minimal input (just query, no radiation data)."""
        result = self._test(
            "Spectrogram (minimal - query only)",
            "POST",
            "/spectrogram",
            json={
                "query": "What are typical background radiation levels?"
            },
            timeout=60
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "")[:80]
            result.message += f" [VEGA: '{response}...']"
        return result
    
    def test_spectrogram_response_fields(self) -> TestResult:
        """Test that /spectrogram returns all expected response fields."""
        result = self._test(
            "Spectrogram (response validation)",
            "POST",
            "/spectrogram",
            json={
                "query": "Analyze this reading",
                "response_format": "text",
                "dose_rate": 0.5,
                "cps": 42.0
            },
            timeout=60
        )
        if result.passed and result.response_data:
            data = result.response_data
            has_response = "response" in data
            has_format = "response_format" in data
            has_model = "model" in data
            
            if has_response and has_format and has_model:
                result.message += f" [All fields present: model={data.get('model', 'N/A')[:20]}]"
            else:
                missing = []
                if not has_response: missing.append("response")
                if not has_format: missing.append("response_format")
                if not has_model: missing.append("model")
                result.message += f" [Missing fields: {', '.join(missing)}]"
        return result
    
    def test_spectrogram_isotope_identification(self) -> TestResult:
        """Test isotope identification from raw 1024-channel spectrum data."""
        import math
        import random
        
        # Generate synthetic 1024-channel gamma spectrum
        # Calibration: 3 keV per channel (channel 0 = 0 keV, channel 1023 = 3069 keV)
        num_channels = 1024
        kev_per_channel = 3.0
        accumulation_time_seconds = 300.0  # 5 minute acquisition
        
        # Initialize with exponential background (Compton continuum)
        spectrum = []
        for ch in range(num_channels):
            background = 50 * math.exp(-ch / 300)  # Exponential falloff
            spectrum.append(max(1, int(background)))
        
        # Add Gaussian peaks for isotopes
        def add_peak(spectrum, energy_kev, amplitude, fwhm_kev=15):
            """Add a Gaussian peak at the given energy."""
            center_channel = int(energy_kev / kev_per_channel)
            sigma = fwhm_kev / (2.355 * kev_per_channel)  # FWHM to sigma
            for ch in range(max(0, center_channel - 50), min(num_channels, center_channel + 50)):
                gaussian = amplitude * math.exp(-0.5 * ((ch - center_channel) / sigma) ** 2)
                spectrum[ch] += int(gaussian)
        
        # Add isotope peaks:
        # Am-241: 59.5 keV (channel ~20)
        add_peak(spectrum, 59.5, amplitude=350, fwhm_kev=12)
        # Cs-137: 662 keV (channel ~221)
        add_peak(spectrum, 662, amplitude=800, fwhm_kev=18)
        # Co-60: 1173 keV and 1332 keV (channels ~391 and ~444)
        add_peak(spectrum, 1173, amplitude=400, fwhm_kev=20)
        add_peak(spectrum, 1332, amplitude=350, fwhm_kev=20)
        # K-40: 1461 keV (channel ~487)
        add_peak(spectrum, 1461, amplitude=150, fwhm_kev=22)
        
        # Add Poisson noise
        random.seed(42)  # Reproducible
        spectrum = [max(0, int(c + random.gauss(0, math.sqrt(max(1, c))))) for c in spectrum]
        
        # Build the prompt with raw spectrum data
        # Format as compact representation to fit in context
        spectrum_str = ",".join(str(c) for c in spectrum)
        
        prompt = f"""GAMMA SPECTRUM ANALYSIS TASK

RAW SPECTRUM DATA:
- Channels: 1024 (0 to 1023)
- Calibration: {kev_per_channel} keV per channel
- Accumulation time: {accumulation_time_seconds} seconds
- Counts per channel (channel 0 to 1023):
[{spectrum_str}]

ISOTOPE REFERENCE TABLE:
| Isotope | Gamma Energy (keV) |
|---------|--------------------|
| Am-241  | 59.5               |
| Cs-137  | 662                |
| Co-60   | 1173, 1332         |
| K-40    | 1461               |
| Na-22   | 511, 1275          |
| Ba-133  | 356, 81            |

TASK:
1. Find peaks in the spectrum (channels with significantly elevated counts above background)
2. Convert peak channel numbers to energy using: Energy (keV) = Channel × {kev_per_channel}
3. Match peaks to the reference table isotopes
4. Report each identified isotope with confidence percentage:
   - Peak within 10 keV of reference = 95% confidence
   - Peak within 20 keV of reference = 85% confidence
   - Peak within 30 keV of reference = 70% confidence

OUTPUT FORMAT (one line per isotope):
Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""

        result = self._test(
            "Spectrogram (raw 1024-ch isotope ID)",
            "POST",
            "/llm/chat",
            json={
                "messages": [
                    {"role": "system", "content": "You are a gamma spectroscopy expert. Analyze raw spectrum data to identify isotopes."},
                    {"role": "user", "content": prompt}
                ],
                "max_tokens": 500,
                "temperature": 0.1
            },
            timeout=90
        )
        if result.passed and result.response_data:
            response = result.response_data.get("response", "").lower()
            # Check for expected isotopes
            found_isotopes = []
            if "cs-137" in response or "cesium" in response:
                found_isotopes.append("Cs-137")
            if "co-60" in response or "cobalt" in response:
                found_isotopes.append("Co-60")
            if "k-40" in response or "potassium" in response:
                found_isotopes.append("K-40")
            if "am-241" in response or "americium" in response:
                found_isotopes.append("Am-241")
            
            # Check for confidence percentages
            has_confidence = "%" in response or "confidence" in response
            
            if len(found_isotopes) >= 3 and has_confidence:
                result.message += f" [Identified: {', '.join(found_isotopes)} with confidence %]"
            elif len(found_isotopes) >= 2:
                result.message += f" [Partial: {', '.join(found_isotopes)}]"
            else:
                result.message += f" [Found {len(found_isotopes)} isotopes]"
        return result
    
    # ==========================================================================
    # API Alias Tests
    # ==========================================================================
    
    def _generate_spectrum(self, isotopes: list, num_channels: int = 1024, 
                           kev_per_channel: float = 3.0, include_background: bool = True,
                           include_noise: bool = True) -> tuple[list, dict]:
        """
        Generate a synthetic gamma spectrum with specified isotopes.
        
        Args:
            isotopes: List of dicts with 'name', 'energies' (list of keV), 'amplitude'
            num_channels: Number of spectrum channels
            kev_per_channel: Energy calibration
            include_background: Whether to add exponential Compton background
            include_noise: Whether to add Poisson statistical noise
            
        Returns:
            (spectrum_counts, metadata_dict)
        """
        import math
        import random
        random.seed(42)  # Reproducible results
        
        # Initialize spectrum
        spectrum = [0] * num_channels
        
        # Add exponential background (Compton continuum)
        if include_background:
            for ch in range(num_channels):
                background = 50 * math.exp(-ch / 300)
                spectrum[ch] = max(1, int(background))
        else:
            spectrum = [1] * num_channels  # Minimal baseline
        
        # Add Gaussian peaks for each isotope
        def add_peak(energy_kev, amplitude, fwhm_kev=15):
            center_channel = int(energy_kev / kev_per_channel)
            sigma = fwhm_kev / (2.355 * kev_per_channel)
            for ch in range(max(0, center_channel - 50), min(num_channels, center_channel + 50)):
                gaussian = amplitude * math.exp(-0.5 * ((ch - center_channel) / sigma) ** 2)
                spectrum[ch] += int(gaussian)
        
        expected_isotopes = []
        for isotope in isotopes:
            for energy in isotope['energies']:
                add_peak(energy, isotope['amplitude'], isotope.get('fwhm', 15))
            expected_isotopes.append(isotope['name'])
        
        # Add Poisson noise
        if include_noise:
            spectrum = [max(0, int(c + random.gauss(0, math.sqrt(max(1, c))))) for c in spectrum]
        
        metadata = {
            'num_channels': num_channels,
            'kev_per_channel': kev_per_channel,
            'expected_isotopes': expected_isotopes,
            'include_background': include_background,
            'include_noise': include_noise
        }
        
        return spectrum, metadata
    
    def _run_isotope_identification(self, spectrum: list, metadata: dict, 
                                     test_name: str, accumulation_time: float = 300.0, variant: str = "baseline") -> TestResult:
        """Run isotope identification on a spectrum and return test result. Variant controls prompt/strategy."""
        spectrum_str = ",".join(str(c) for c in spectrum)

        # Variant prompt engineering
        if variant == "baseline":
            prompt = f"""GAMMA SPECTRUM ANALYSIS TASK\n\nRAW SPECTRUM DATA:\n- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})\n- Calibration: {metadata['kev_per_channel']} keV per channel\n- Accumulation time: {accumulation_time} seconds\n- Counts per channel:\n[{spectrum_str}]\n\nISOTOPE REFERENCE TABLE:\n| Isotope | Gamma Energy (keV) |\n|---------|--------------------|\n| Am-241  | 59.5               |\n| Cs-137  | 662                |\n| Co-60   | 1173, 1332         |\n| K-40    | 1461               |\n| Na-22   | 511, 1275          |\n| Ba-133  | 81, 356            |\n| I-131   | 364                |\n| Ra-226  | 186                |\n\nTASK:\n1. Find peaks in the spectrum (channels with elevated counts above background)\n2. Convert peak channels to energy: Energy (keV) = Channel × {metadata['kev_per_channel']}\n3. Match peaks to reference isotopes (within 20 keV tolerance)\n4. List ONLY isotopes that have matching peaks in the data\n\nOUTPUT FORMAT (one line per identified isotope):\nIsotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Identify ONLY isotopes with peaks present in the spectrum data. Do not list isotopes without matching peaks."
        elif variant == "step_by_step":
            prompt = f"""You are given a gamma spectrum.\n\nRAW SPECTRUM DATA:\n- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})\n- Calibration: {metadata['kev_per_channel']} keV per channel\n- Accumulation time: {accumulation_time} seconds\n- Counts per channel:\n[{spectrum_str}]\n\nISOTOPE REFERENCE TABLE:\n| Isotope | Gamma Energy (keV) |\n|---------|--------------------|\n| Am-241  | 59.5               |\n| Cs-137  | 662                |\n| Co-60   | 1173, 1332         |\n| K-40    | 1461               |\n| Na-22   | 511, 1275          |\n| Ba-133  | 81, 356            |\n| I-131   | 364                |\n| Ra-226  | 186                |\n\nTASK: Step by step, first find peaks, then convert to energy, then match to isotopes, then output only those isotopes with a matching peak.\n\nOutput as: Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Think step by step."
        elif variant == "few_shot":
            # Add a few-shot example (short spectrum, correct answer)
            example_spectrum = "[1,2,1,1,10,20,10,1,1,2,1]"
            example_prompt = f"RAW SPECTRUM DATA:\n- Channels: 11 (0 to 10)\n- Calibration: 10 keV per channel\n- Counts per channel:\n{example_spectrum}\n\nISOTOPE REFERENCE TABLE:\n| Isotope | Gamma Energy (keV) |\n|---------|--------------------|\n| X-100   | 50                 |\n| Y-200   | 100                |\n\nTASK: Find peaks, match to isotopes.\n\nOUTPUT FORMAT:\nIsotope: X-100 | Energy: 50 keV | Confidence: 95%"
            prompt = f"{example_prompt}\n\n---\n\nNow analyze this spectrum:\n\nRAW SPECTRUM DATA:\n- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})\n- Calibration: {metadata['kev_per_channel']} keV per channel\n- Accumulation time: {accumulation_time} seconds\n- Counts per channel:\n[{spectrum_str}]\n\nISOTOPE REFERENCE TABLE:\n| Isotope | Gamma Energy (keV) |\n|---------|--------------------|\n| Am-241  | 59.5               |\n| Cs-137  | 662                |\n| Co-60   | 1173, 1332         |\n| K-40    | 1461               |\n| Na-22   | 511, 1275          |\n| Ba-133  | 81, 356            |\n| I-131   | 364                |\n| Ra-226  | 186                |\n\nTASK: Find peaks, match to isotopes.\n\nOUTPUT FORMAT (one line per identified isotope):\nIsotope: [name] | Energy: [X] keV | Confidence: [Y]%"
            system = "You are a gamma spectroscopy expert."
        elif variant == "peaks_only":
            # Preprocess: find peaks, send only peak energies/counts
            import numpy as np
            from scipy.signal import find_peaks
            arr = np.array(spectrum)
            peaks, _ = find_peaks(arr, height=max(arr)*0.2, distance=10)
            peak_energies = [round(p * metadata['kev_per_channel'], 1) for p in peaks]
            peak_counts = [int(arr[p]) for p in peaks]
            peaks_table = "\n".join([f"| {i+1} | {e} | {c} |" for i, (e, c) in enumerate(zip(peak_energies, peak_counts))])
            prompt = f"""GAMMA SPECTRUM PEAKS\n\nPEAKS TABLE:\n| # | Energy (keV) | Counts |\n|---|--------------|--------|\n{peaks_table}\n\nISOTOPE REFERENCE TABLE:\n| Isotope | Gamma Energy (keV) |\n|---------|--------------------|\n| Am-241  | 59.5               |\n| Cs-137  | 662                |\n| Co-60   | 1173, 1332         |\n| K-40    | 1461               |\n| Na-22   | 511, 1275          |\n| Ba-133  | 81, 356            |\n| I-131   | 364                |\n| Ra-226  | 186                |\n\nTASK: Match each peak to the reference table. Only list isotopes with a matching peak.\n\nOUTPUT FORMAT (one line per identified isotope):\nIsotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Only list isotopes with a matching peak."
        else:
            # fallback to baseline
            prompt = f"RAW SPECTRUM DATA:\n[{spectrum_str}]"
            system = "You are a gamma spectroscopy expert."

        result = self._test(
            f"{test_name} [{variant}]",
            "POST",
            "/llm/chat",
            json={
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": prompt}
                ],
                "max_tokens": 400,
                "temperature": 0.1
            },
            timeout=90
        )
        
        # Analyze results for benchmark metrics
        if result.passed and result.response_data:
            response = result.response_data.get("response", "").lower()
            expected = set(metadata['expected_isotopes'])
            
            # Detect isotopes in response
            found = set()
            isotope_patterns = {
                'Am-241': ['am-241', 'americium', 'am241'],
                'Cs-137': ['cs-137', 'cesium', 'cs137'],
                'Co-60': ['co-60', 'cobalt', 'co60'],
                'K-40': ['k-40', 'potassium', 'k40'],
                'Na-22': ['na-22', 'sodium', 'na22'],
                'Ba-133': ['ba-133', 'barium', 'ba133'],
                'I-131': ['i-131', 'iodine', 'i131'],
                'Ra-226': ['ra-226', 'radium', 'ra226']
            }
            
            for isotope, patterns in isotope_patterns.items():
                if any(p in response for p in patterns):
                    found.add(isotope)
            
            # Calculate metrics
            true_positives = expected & found
            false_positives = found - expected
            false_negatives = expected - found
            
            result.response_data['_benchmark'] = {
                'expected': list(expected),
                'found': list(found),
                'true_positives': list(true_positives),
                'false_positives': list(false_positives),
                'false_negatives': list(false_negatives)
            }
            
            if not false_positives and not false_negatives:
                result.message += f" [PERFECT: {','.join(sorted(found))}]"
            else:
                parts = []
                if true_positives:
                    parts.append(f"✓{','.join(sorted(true_positives))}")
                if false_positives:
                    parts.append(f"+{','.join(sorted(false_positives))}")
                if false_negatives:
                    parts.append(f"-{','.join(sorted(false_negatives))}")
                result.message += f" [{' '.join(parts)}]"
        
        return result
    
    def run_isotope_benchmark(self) -> dict:
        """
        Run a benchmark suite of 10 isotope identification tests for each prompt/preprocessing variant.
        Returns benchmark statistics for all variants.
        """
        print("=" * 70)
        print("ISOTOPE IDENTIFICATION BENCHMARK SUITE (Prompt/Preprocessing Variants)")
        print("=" * 70)
        print()

        variants = [
            "baseline",
            "step_by_step",
            "few_shot",
            "peaks_only"
        ]

        test_cases = [
            ("Cs-137 Only (bg)", [{'name': 'Cs-137', 'energies': [662], 'amplitude': 800}], True, True),
            ("Co-60 Only (bg)", [{'name': 'Co-60', 'energies': [1173, 1332], 'amplitude': 400}], True, True),
            ("Am-241 Only (bg)", [{'name': 'Am-241', 'energies': [59.5], 'amplitude': 500}], True, True),
            ("Cs-137 Only (clean)", [{'name': 'Cs-137', 'energies': [662], 'amplitude': 800}], False, False),
            ("Cs-137 + Co-60 (bg)", [
                {'name': 'Cs-137', 'energies': [662], 'amplitude': 700},
                {'name': 'Co-60', 'energies': [1173, 1332], 'amplitude': 350}
            ], True, True),
            ("Am-241 + Cs-137 + K-40 (bg)", [
                {'name': 'Am-241', 'energies': [59.5], 'amplitude': 400},
                {'name': 'Cs-137', 'energies': [662], 'amplitude': 600},
                {'name': 'K-40', 'energies': [1461], 'amplitude': 200}
            ], True, True),
            ("4 Isotopes (bg)", [
                {'name': 'Am-241', 'energies': [59.5], 'amplitude': 350},
                {'name': 'Cs-137', 'energies': [662], 'amplitude': 800},
                {'name': 'Co-60', 'energies': [1173, 1332], 'amplitude': 400},
                {'name': 'K-40', 'energies': [1461], 'amplitude': 150}
            ], True, True),
            ("Na-22 Only (bg)", [{'name': 'Na-22', 'energies': [511, 1275], 'amplitude': 450}], True, True),
            ("Ba-133 + I-131 (bg)", [
                {'name': 'Ba-133', 'energies': [81, 356], 'amplitude': 500},
                {'name': 'I-131', 'energies': [364], 'amplitude': 600}
            ], True, True),
            ("5 Isotopes (no bg)", [
                {'name': 'Am-241', 'energies': [59.5], 'amplitude': 400},
                {'name': 'Cs-137', 'energies': [662], 'amplitude': 700},
                {'name': 'Co-60', 'energies': [1173, 1332], 'amplitude': 350},
                {'name': 'Na-22', 'energies': [511, 1275], 'amplitude': 300},
                {'name': 'K-40', 'energies': [1461], 'amplitude': 200}
            ], False, True)
        ]

        all_results = {v: [] for v in variants}

        for variant in variants:
            print(f"\n{'='*30}\nVARIANT: {variant}\n{'='*30}")
            for name, iso, bg, noise in test_cases:
                print("─" * 50)
                print(f"TEST: {name}")
                print("─" * 50)
                spectrum, meta = self._generate_spectrum(iso, include_background=bg, include_noise=noise)
                result = self._run_isotope_identification(spectrum, meta, name, variant=variant)
                self._print_benchmark_result(result)
                all_results[variant].append(result)
                print()
        # Print summary for each variant
        for variant in variants:
            print(f"\n{'='*30}\nSUMMARY FOR VARIANT: {variant}\n{'='*30}")
            self._print_benchmark_summary(all_results[variant])
        return all_results
    
    def _print_benchmark_result(self, result: TestResult):
        """Print a single benchmark test result."""
        status = "PASS" if result.passed else "FAIL"
        print(f"  [{status}] {result.name}")
        print(f"         {result.message}")
        
        if self.verbose and result.response_data:
            response = result.response_data.get("response", "")[:500]
            print(f"         Response: {response}")
        print()
    
    def _print_benchmark_summary(self, results: list) -> dict:
        """Print benchmark summary statistics."""
        print("=" * 70)
        print("BENCHMARK SUMMARY")
        print("=" * 70)
        
        total_tests = len(results)
        passed_tests = sum(1 for r in results if r.passed)
        
        # Aggregate benchmark metrics
        total_expected = 0
        total_tp = 0
        total_fp = 0
        total_fn = 0
        perfect_scores = 0
        
        print("\nDetailed Results:")
        print("-" * 70)
        print(f"{'Test':<40} {'Expected':<15} {'Result':<15}")
        print("-" * 70)
        
        for r in results:
            if r.response_data and '_benchmark' in r.response_data:
                b = r.response_data['_benchmark']
                expected_str = ','.join(sorted(b['expected']))
                
                if b['false_positives'] or b['false_negatives']:
                    result_parts = []
                    if b['true_positives']:
                        result_parts.append(f"✓{','.join(sorted(b['true_positives']))}")
                    if b['false_positives']:
                        result_parts.append(f"+{','.join(sorted(b['false_positives']))}")
                    if b['false_negatives']:
                        result_parts.append(f"-{','.join(sorted(b['false_negatives']))}")
                    result_str = ' '.join(result_parts)
                else:
                    result_str = "✓ PERFECT"
                    perfect_scores += 1
                
                total_expected += len(b['expected'])
                total_tp += len(b['true_positives'])
                total_fp += len(b['false_positives'])
                total_fn += len(b['false_negatives'])
                
                print(f"{r.name:<40} {expected_str:<15} {result_str:<15}")
        
        print("-" * 70)
        print()
        
        # Calculate metrics
        accuracy = total_tp / total_expected if total_expected > 0 else 0
        precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0
        recall = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 0
        f1 = 2 * (precision * recall) / (precision + recall) if (precision + recall) > 0 else 0
        
        total_time = sum(r.duration_ms for r in results)
        
        print("AGGREGATE METRICS:")
        print(f"  Tests Passed:     {passed_tests}/{total_tests}")
        print(f"  Perfect Scores:   {perfect_scores}/{total_tests}")
        print(f"  True Positives:   {total_tp}")
        print(f"  False Positives:  {total_fp}")
        print(f"  False Negatives:  {total_fn}")
        print()
        print(f"  Accuracy:         {accuracy:.1%} (correct IDs / expected)")
        print(f"  Precision:        {precision:.1%} (correct IDs / all IDs)")
        print(f"  Recall:           {recall:.1%} (correct IDs / expected)")
        print(f"  F1 Score:         {f1:.1%}")
        print()
        print(f"  Total Time:       {total_time:.0f}ms ({total_time/1000:.2f}s)")
        print(f"  Avg per Test:     {total_time/total_tests:.0f}ms")
        print("=" * 70)
        
        return {
            'total_tests': total_tests,
            'passed_tests': passed_tests,
            'perfect_scores': perfect_scores,
            'true_positives': total_tp,
            'false_positives': total_fp,
            'false_negatives': total_fn,
            'accuracy': accuracy,
            'precision': precision,
            'recall': recall,
            'f1_score': f1,
            'total_time_ms': total_time
        }

    def test_api_tts_alias(self) -> TestResult:
        """Test /api/tts/* alias route."""
        return self._test(
            "API TTS Alias (/api/tts/health)",
            "GET",
            "/api/tts/health"
        )
    
    def test_api_llm_alias(self) -> TestResult:
        """Test /api/llm/* alias route."""
        return self._test(
            "API LLM Alias (/api/llm/health)",
            "GET",
            "/api/llm/health"
        )
    
    # ==========================================================================
    # Error Handling Tests
    # ==========================================================================
    
    def test_404_endpoint(self) -> TestResult:
        """Test 404 for non-existent endpoint."""
        return self._test(
            "404 Not Found",
            "GET",
            "/nonexistent",
            expected_status=404
        )
    
    def test_synthesize_empty_text(self) -> TestResult:
        """Test synthesize with empty text (should fail)."""
        return self._test(
            "Synthesize Empty Text (should fail)",
            "POST",
            "/synthesize",
            json={"text": ""},
            expected_status=422  # Validation error
        )
    
    def test_chat_empty_messages(self) -> TestResult:
        """Test chat with empty messages (behavior varies by implementation)."""
        result = self._test(
            "Chat Empty Messages",
            "POST",
            "/chat",
            json={"messages": []},
            expected_status=200  # LLM may accept empty messages with default system prompt
        )
        return result
    
    # ==========================================================================
    # Run All Tests
    # ==========================================================================
    
    def run_all(self) -> bool:
        """Run all tests and return True if all passed."""
        print("=" * 70)
        print(f"VEGA INGRESS API TEST SUITE")
        print(f"Target: {self.base_url}")
        print("=" * 70)
        print()
        
        # Group 1: Health & Info
        print("─" * 50)
        print("HEALTH & INFO ENDPOINTS")
        print("─" * 50)
        self._run_and_print(self.test_health)
        self._run_and_print(self.test_info)
        print()
        
        # Group 2: TTS Backend
        print("─" * 50)
        print("TTS BACKEND (via proxy)")
        print("─" * 50)
        self._run_and_print(self.test_tts_health)
        self._run_and_print(self.test_tts_info)
        print()
        
        # Group 3: LLM Backend
        print("─" * 50)
        print("LLM BACKEND (via proxy)")
        print("─" * 50)
        self._run_and_print(self.test_llm_health)
        self._run_and_print(self.test_llm_info)
        print()
        
        # Group 4: TTS Synthesis
        print("─" * 50)
        print("TTS SYNTHESIS")
        print("─" * 50)
        self._run_and_print(self.test_synthesize_root)
        self._run_and_print(self.test_synthesize_via_tts)
        self._run_and_print(self.test_synthesize_b64_root)
        self._run_and_print(self.test_synthesize_b64_via_tts)
        print()
        
        # Group 5: LLM Generation
        print("─" * 50)
        print("LLM GENERATION")
        print("─" * 50)
        self._run_and_print(self.test_chat_root)
        self._run_and_print(self.test_chat_via_llm)
        self._run_and_print(self.test_generate_root)
        self._run_and_print(self.test_generate_via_llm)
        print()
        
        # Group 6: Spectrogram Analysis (VEGA Radiological Assistant)
        print("─" * 50)
        print("SPECTROGRAM ANALYSIS (VEGA Radiological)")
        print("─" * 50)
        self._run_and_print(self.test_spectrogram_text)
        self._run_and_print(self.test_spectrogram_speech)
        self._run_and_print(self.test_spectrogram_json)
        self._run_and_print(self.test_spectrogram_full_data)
        self._run_and_print(self.test_spectrogram_high_radiation)
        self._run_and_print(self.test_spectrogram_low_radiation)
        self._run_and_print(self.test_spectrogram_via_llm)
        self._run_and_print(self.test_spectrogram_minimal_query)
        self._run_and_print(self.test_spectrogram_response_fields)
        self._run_and_print(self.test_spectrogram_isotope_identification)
        print()
        
        # Group 7: API Aliases
        print("─" * 50)
        print("API ALIASES")
        print("─" * 50)
        self._run_and_print(self.test_api_tts_alias)
        self._run_and_print(self.test_api_llm_alias)
        print()
        
        # Group 8: Error Handling
        print("─" * 50)
        print("ERROR HANDLING")
        print("─" * 50)
        self._run_and_print(self.test_404_endpoint)
        self._run_and_print(self.test_synthesize_empty_text)
        self._run_and_print(self.test_chat_empty_messages)
        print()
        
        # Summary
        return self._print_summary()
    
    def _run_and_print(self, test_func):
        """Run a test and print result."""
        result = test_func()
        status = "PASS" if result.passed else "FAIL"
        print(f"  [{status}] {result.name}")
        print(f"         {result.message}")
        
        # Verbose mode - show full request/response
        if self.verbose:
            print()
            print(f"         ┌─ REQUEST ─────────────────────────────────────")
            print(f"         │ {result.method} {self.base_url}{result.endpoint}")
            if result.request_data:
                req_json = self._format_json(result.request_data)
                for line in req_json.split('\n'):
                    print(f"         │ {line}")
            else:
                print(f"         │ (no body)")
            print(f"         └──────────────────────────────────────────────")
            print()
            print(f"         ┌─ RESPONSE ────────────────────────────────────")
            if result.response_data:
                # Truncate audio data for display
                display_data = self._truncate_audio_in_response(result.response_data)
                resp_json = self._format_json(display_data)
                for line in resp_json.split('\n'):
                    print(f"         │ {line}")
            else:
                print(f"         │ (no response data)")
            print(f"         └──────────────────────────────────────────────")
            print()
    
    def _print_summary(self) -> bool:
        """Print test summary and return True if all passed."""
        print("=" * 70)
        print("SUMMARY")
        print("=" * 70)
        
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)
        
        total_time = sum(r.duration_ms for r in self.results)
        
        print(f"  Total:  {total} tests")
        print(f"  Passed: {passed} ✓")
        print(f"  Failed: {failed} ✗")
        print(f"  Time:   {total_time:.0f}ms ({total_time/1000:.2f}s)")
        print()
        
        if failed > 0:
            print("FAILED TESTS:")
            for r in self.results:
                if not r.passed:
                    print(f"  - {r.name}: {r.message}")
            print()
        
        all_passed = failed == 0
        status = "ALL TESTS PASSED ✓" if all_passed else f"SOME TESTS FAILED ({failed}/{total}) ✗"
        print(f"Result: {status}")
        print("=" * 70)
        
        return all_passed


# ==============================================================================
# Main
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Test Vega Ingress API endpoints",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.add_argument(
        "--host", 
        default=DEFAULT_HOST,
        help="Ingress host"
    )
    parser.add_argument(
        "--port", 
        type=int, 
        default=DEFAULT_PORT,
        help="Ingress port"
    )
    parser.add_argument(
        "--save-audio",
        action="store_true",
        help="Save generated audio files to output directory"
    )
    parser.add_argument(
        "--quick",
        action="store_true",
        help="Run only health check tests (fast)"
    )
    parser.add_argument(
        "--benchmark",
        action="store_true",
        help="Run isotope identification benchmark suite (10 tests)"
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show detailed request/response for each test"
    )
    
    args = parser.parse_args()
    
    base_url = f"http://{args.host}:{args.port}"
    runner = TestRunner(base_url, save_audio=args.save_audio, verbose=args.verbose)
    
    if args.quick:
        # Quick mode - just health checks
        print(f"Quick test mode - checking {base_url}")
        print("-" * 40)
        runner.test_health()
        runner.test_tts_health()
        runner.test_llm_health()
        runner._print_summary()
    elif args.benchmark:
        # Isotope identification benchmark
        print(f"Isotope Benchmark mode - testing {base_url}")
        all_results = runner.run_isotope_benchmark()
        # Get best variant accuracy
        best_accuracy = 0
        for variant, results in all_results.items():
            total_expected = 0
            total_tp = 0
            for r in results:
                if r.response_data and '_benchmark' in r.response_data:
                    b = r.response_data['_benchmark']
                    total_expected += len(b['expected'])
                    total_tp += len(b['true_positives'])
            accuracy = total_tp / total_expected if total_expected > 0 else 0
            if accuracy > best_accuracy:
                best_accuracy = accuracy
        sys.exit(0 if best_accuracy >= 0.7 else 1)
    else:
        # Full test suite
        all_passed = runner.run_all()
        sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
