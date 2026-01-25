#!/usr/bin/env python3
"""
Local Isotope Identification Benchmark for RTX 5090

Runs DeepSeek-R1-Distill-Qwen-32B locally using transformers with BF16.
Benchmarks isotope identification accuracy across multiple test cases.

Requirements:
    pip install torch transformers accelerate scipy numpy

Usage:
    python local_isotope_benchmark.py
"""

import json
import math
import random
import time
from dataclasses import dataclass
from typing import Optional

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# ==============================================================================
# Configuration
# ==============================================================================

MODEL_ID = "Qwen/Qwen2.5-3B-Instruct"
DEVICE = "cuda"
TORCH_DTYPE = torch.bfloat16
MAX_NEW_TOKENS = 512

# ==============================================================================
# Result Tracking
# ==============================================================================

@dataclass
class TestResult:
    name: str
    passed: bool
    duration_ms: float
    message: str
    response_data: Optional[dict] = None
    variant: str = ""


class LocalBenchmark:
    def __init__(self, model_id: str = MODEL_ID, verbose: bool = False):
        self.model_id = model_id
        self.verbose = verbose
        self.model = None
        self.tokenizer = None
        self.results: list[TestResult] = []
    
    def load_model(self):
        """Load the model and tokenizer."""
        print(f"Loading model: {self.model_id}")
        print(f"Device: {DEVICE}, Dtype: {TORCH_DTYPE}")
        
        start = time.time()
        
        self.tokenizer = AutoTokenizer.from_pretrained(
            self.model_id, 
            trust_remote_code=True
        )
        
        self.model = AutoModelForCausalLM.from_pretrained(
            self.model_id,
            torch_dtype=TORCH_DTYPE,
            device_map="auto",
            trust_remote_code=True
        )
        
        load_time = time.time() - start
        print(f"Model loaded in {load_time:.1f}s")
        
        # Print GPU memory usage
        if torch.cuda.is_available():
            allocated = torch.cuda.memory_allocated() / 1024**3
            reserved = torch.cuda.memory_reserved() / 1024**3
            print(f"GPU Memory: {allocated:.1f}GB allocated, {reserved:.1f}GB reserved")
        
        return self
    
    def generate(self, messages: list[dict], max_tokens: int = MAX_NEW_TOKENS, 
                 temperature: float = 0.1) -> tuple[str, float]:
        """Generate a response from the model."""
        # Apply chat template
        text = self.tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True
        )
        
        inputs = self.tokenizer(text, return_tensors="pt").to(DEVICE)
        
        start = time.time()
        
        with torch.no_grad():
            outputs = self.model.generate(
                **inputs,
                max_new_tokens=max_tokens,
                temperature=temperature,
                do_sample=temperature > 0,
                pad_token_id=self.tokenizer.eos_token_id
            )
        
        generation_time = time.time() - start
        
        # Decode only the new tokens
        response = self.tokenizer.decode(
            outputs[0][inputs.input_ids.shape[1]:], 
            skip_special_tokens=True
        )
        
        return response, generation_time
    
    def _generate_spectrum(self, isotopes: list, num_channels: int = 1024, 
                           kev_per_channel: float = 3.0, include_background: bool = True,
                           include_noise: bool = True) -> tuple[list, dict]:
        """Generate a synthetic gamma spectrum with specified isotopes."""
        random.seed(42)  # Reproducible results
        
        spectrum = [0] * num_channels
        
        if include_background:
            for ch in range(num_channels):
                background = 50 * math.exp(-ch / 300)
                spectrum[ch] = max(1, int(background))
        else:
            spectrum = [1] * num_channels
        
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
    
    def _find_peaks(self, spectrum: list, kev_per_channel: float) -> list[tuple[float, int]]:
        """Find peaks in spectrum using scipy."""
        try:
            import numpy as np
            from scipy.signal import find_peaks
            arr = np.array(spectrum)
            peaks, _ = find_peaks(arr, height=max(arr)*0.2, distance=10)
            return [(round(p * kev_per_channel, 1), int(arr[p])) for p in peaks]
        except ImportError:
            # Fallback: simple peak detection
            peaks = []
            for i in range(5, len(spectrum) - 5):
                if spectrum[i] > spectrum[i-1] and spectrum[i] > spectrum[i+1]:
                    if spectrum[i] > max(spectrum) * 0.2:
                        peaks.append((round(i * kev_per_channel, 1), spectrum[i]))
            return peaks
    
    def _run_isotope_identification(self, spectrum: list, metadata: dict, 
                                     test_name: str, variant: str = "peaks_only") -> TestResult:
        """Run isotope identification on a spectrum."""
        
        # Build prompt based on variant
        if variant == "peaks_only":
            peaks = self._find_peaks(spectrum, metadata['kev_per_channel'])
            peaks_table = "\n".join([f"| {i+1} | {e} | {c} |" for i, (e, c) in enumerate(peaks)])
            prompt = f"""GAMMA SPECTRUM PEAKS

PEAKS TABLE:
| # | Energy (keV) | Counts |
|---|--------------|--------|
{peaks_table}

ISOTOPE REFERENCE TABLE:
| Isotope | Gamma Energy (keV) |
|---------|-------------------|
| Am-241  | 59.5              |
| Cs-137  | 662               |
| Co-60   | 1173, 1332        |
| K-40    | 1461              |
| Na-22   | 511, 1275         |
| Ba-133  | 81, 356           |
| I-131   | 364               |
| Ra-226  | 186               |

TASK: Match each peak to the reference table. Only list isotopes with a matching peak (within 20 keV tolerance).

OUTPUT FORMAT (one line per identified isotope):
Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Only list isotopes with a matching peak."
        
        elif variant == "baseline":
            spectrum_str = ",".join(str(c) for c in spectrum)
            prompt = f"""GAMMA SPECTRUM ANALYSIS TASK

RAW SPECTRUM DATA:
- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})
- Calibration: {metadata['kev_per_channel']} keV per channel
- Counts per channel:
[{spectrum_str}]

ISOTOPE REFERENCE TABLE:
| Isotope | Gamma Energy (keV) |
|---------|-------------------|
| Am-241  | 59.5              |
| Cs-137  | 662               |
| Co-60   | 1173, 1332        |
| K-40    | 1461              |
| Na-22   | 511, 1275         |
| Ba-133  | 81, 356           |
| I-131   | 364               |
| Ra-226  | 186               |

TASK:
1. Find peaks in the spectrum (channels with elevated counts above background)
2. Convert peak channels to energy: Energy (keV) = Channel × {metadata['kev_per_channel']}
3. Match peaks to reference isotopes (within 20 keV tolerance)
4. List ONLY isotopes that have matching peaks in the data

OUTPUT FORMAT (one line per identified isotope):
Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Identify ONLY isotopes with peaks present in the spectrum data."
        
        elif variant == "step_by_step":
            spectrum_str = ",".join(str(c) for c in spectrum)
            prompt = f"""You are given a gamma spectrum.

RAW SPECTRUM DATA:
- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})
- Calibration: {metadata['kev_per_channel']} keV per channel
- Counts per channel:
[{spectrum_str}]

ISOTOPE REFERENCE TABLE:
| Isotope | Gamma Energy (keV) |
|---------|-------------------|
| Am-241  | 59.5              |
| Cs-137  | 662               |
| Co-60   | 1173, 1332        |
| K-40    | 1461              |
| Na-22   | 511, 1275         |
| Ba-133  | 81, 356           |
| I-131   | 364               |
| Ra-226  | 186               |

TASK: Step by step:
1. First find peaks in the data
2. Then convert to energy
3. Then match to isotopes
4. Output only those isotopes with a matching peak

Output as: Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert. Think step by step."
        
        elif variant == "few_shot":
            spectrum_str = ",".join(str(c) for c in spectrum)
            example = """Example:
RAW SPECTRUM DATA (11 channels, 10 keV/channel):
[1,2,1,1,10,20,10,1,1,2,1]

Peak at channel 5 (50 keV) → matches X-100 (50 keV)
Output: Isotope: X-100 | Energy: 50 keV | Confidence: 95%

---"""
            prompt = f"""{example}

Now analyze this spectrum:

RAW SPECTRUM DATA:
- Channels: {metadata['num_channels']} (0 to {metadata['num_channels']-1})
- Calibration: {metadata['kev_per_channel']} keV per channel
- Counts per channel:
[{spectrum_str}]

ISOTOPE REFERENCE TABLE:
| Isotope | Gamma Energy (keV) |
|---------|-------------------|
| Am-241  | 59.5              |
| Cs-137  | 662               |
| Co-60   | 1173, 1332        |
| K-40    | 1461              |
| Na-22   | 511, 1275         |
| Ba-133  | 81, 356           |
| I-131   | 364               |
| Ra-226  | 186               |

TASK: Find peaks, match to isotopes.

OUTPUT FORMAT (one line per identified isotope):
Isotope: [name] | Energy: [X] keV | Confidence: [Y]%"""
            system = "You are a gamma spectroscopy expert."
        
        else:
            raise ValueError(f"Unknown variant: {variant}")
        
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt}
        ]
        
        start_time = time.time()
        
        try:
            response_text, gen_time = self.generate(messages, max_tokens=400, temperature=0.1)
            duration_ms = (time.time() - start_time) * 1000
            passed = True
            msg = f"✓ OK ({duration_ms:.0f}ms, gen: {gen_time:.1f}s)"
        except Exception as e:
            duration_ms = (time.time() - start_time) * 1000
            response_text = ""
            passed = False
            msg = f"✗ Error: {e}"
        
        # Analyze results
        response_lower = response_text.lower()
        expected = set(metadata['expected_isotopes'])
        
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
        
        found = set()
        for isotope, patterns in isotope_patterns.items():
            if any(p in response_lower for p in patterns):
                found.add(isotope)
        
        true_positives = expected & found
        false_positives = found - expected
        false_negatives = expected - found
        
        benchmark_data = {
            'expected': list(expected),
            'found': list(found),
            'true_positives': list(true_positives),
            'false_positives': list(false_positives),
            'false_negatives': list(false_negatives),
            'response': response_text
        }
        
        if not false_positives and not false_negatives:
            msg += f" [PERFECT: {','.join(sorted(found))}]"
        else:
            parts = []
            if true_positives:
                parts.append(f"✓{','.join(sorted(true_positives))}")
            if false_positives:
                parts.append(f"+{','.join(sorted(false_positives))}")
            if false_negatives:
                parts.append(f"-{','.join(sorted(false_negatives))}")
            msg += f" [{' '.join(parts)}]"
        
        result = TestResult(
            name=f"{test_name} [{variant}]",
            passed=passed,
            duration_ms=duration_ms,
            message=msg,
            response_data=benchmark_data,
            variant=variant
        )
        
        self.results.append(result)
        return result
    
    def run_isotope_benchmark(self) -> dict:
        """Run the full isotope identification benchmark suite."""
        print("=" * 70)
        print(f"ISOTOPE IDENTIFICATION BENCHMARK - LOCAL RTX 5090")
        print(f"Model: {self.model_id}")
        print("=" * 70)
        print()
        
        variants = ["baseline", "step_by_step", "few_shot", "peaks_only"]
        
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
                
                print(f"  [{('PASS' if result.passed else 'FAIL')}] {result.name}")
                print(f"         {result.message}")
                
                if self.verbose and result.response_data:
                    response = result.response_data.get('response', '')[:500]
                    print(f"         Response: {response}")
                
                print()
                all_results[variant].append(result)
        
        # Print summary for each variant
        for variant in variants:
            self._print_benchmark_summary(variant, all_results[variant])
        
        # Print overall comparison
        self._print_variant_comparison(all_results)
        
        return all_results
    
    def _print_benchmark_summary(self, variant: str, results: list):
        """Print benchmark summary for a variant."""
        print(f"\n{'='*70}")
        print(f"SUMMARY FOR VARIANT: {variant}")
        print("=" * 70)
        
        total_tests = len(results)
        passed_tests = sum(1 for r in results if r.passed)
        
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
            if r.response_data:
                b = r.response_data
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
        
        accuracy = total_tp / total_expected if total_expected > 0 else 0
        precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0
        recall = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 0
        f1 = 2 * (precision * recall) / (precision + recall) if (precision + recall) > 0 else 0
        
        total_time = sum(r.duration_ms for r in results)
        
        print("\nAGGREGATE METRICS:")
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
    
    def _print_variant_comparison(self, all_results: dict):
        """Print comparison across all variants."""
        print("\n" + "=" * 70)
        print("VARIANT COMPARISON")
        print("=" * 70)
        print(f"{'Variant':<15} {'Accuracy':<10} {'Precision':<10} {'F1':<10} {'Perfect':<10} {'Avg Time':<10}")
        print("-" * 70)
        
        for variant, results in all_results.items():
            total_expected = 0
            total_tp = 0
            total_fp = 0
            total_fn = 0
            perfect = 0
            
            for r in results:
                if r.response_data:
                    b = r.response_data
                    total_expected += len(b['expected'])
                    total_tp += len(b['true_positives'])
                    total_fp += len(b['false_positives'])
                    total_fn += len(b['false_negatives'])
                    if not b['false_positives'] and not b['false_negatives']:
                        perfect += 1
            
            accuracy = total_tp / total_expected if total_expected > 0 else 0
            precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0
            f1 = 2 * (precision * accuracy) / (precision + accuracy) if (precision + accuracy) > 0 else 0
            avg_time = sum(r.duration_ms for r in results) / len(results)
            
            print(f"{variant:<15} {accuracy:>8.1%}  {precision:>8.1%}  {f1:>8.1%}  {perfect:>6}/10  {avg_time:>8.0f}ms")
        
        print("=" * 70)


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description="Local Isotope Identification Benchmark")
    parser.add_argument("--model", default=MODEL_ID, help="HuggingFace model ID")
    parser.add_argument("--verbose", "-v", action="store_true", help="Show full responses")
    args = parser.parse_args()
    
    benchmark = LocalBenchmark(model_id=args.model, verbose=args.verbose)
    benchmark.load_model()
    
    results = benchmark.run_isotope_benchmark()
    
    # Save results to JSON
    output = {
        "model": args.model,
        "device": DEVICE,
        "dtype": str(TORCH_DTYPE),
        "results": {}
    }
    
    for variant, variant_results in results.items():
        output["results"][variant] = []
        for r in variant_results:
            output["results"][variant].append({
                "name": r.name,
                "passed": r.passed,
                "duration_ms": r.duration_ms,
                "expected": r.response_data.get('expected', []) if r.response_data else [],
                "found": r.response_data.get('found', []) if r.response_data else [],
                "true_positives": r.response_data.get('true_positives', []) if r.response_data else [],
                "false_positives": r.response_data.get('false_positives', []) if r.response_data else [],
                "false_negatives": r.response_data.get('false_negatives', []) if r.response_data else []
            })
    
    output_file = f"benchmark_results_{args.model.replace('/', '_')}.json"
    with open(output_file, 'w') as f:
        json.dump(output, f, indent=2)
    print(f"\nResults saved to: {output_file}")


if __name__ == "__main__":
    main()
