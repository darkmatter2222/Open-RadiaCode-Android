"""
Synthetic Spectra Generation Script v3

Optimized for 2D model training with:
- Fixed 60-second duration (60 time intervals)
- Better isotope combinations including decay chain scenarios
- Enhanced background-only samples
- More diverse mixing scenarios

Usage:
    python -m synthetic_spectra.generate_spectra_v3 --num_samples 200000 --workers 8
"""

import argparse
import sys
from pathlib import Path
import json
from datetime import datetime
import numpy as np
from multiprocessing import Pool, cpu_count
from functools import partial
import time
from typing import List, Tuple, Dict, Optional
import os

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from synthetic_spectra.generator import (
    SpectrumGenerator,
    SpectrumConfig,
    IsotopeSource,
    GeneratedSpectrum,
    save_spectrum,
)
from synthetic_spectra.config import RADIACODE_CONFIGS
from synthetic_spectra.ground_truth import get_isotope


# =============================================================================
# ISOTOPE POOLS - Organized for realistic scenarios
# =============================================================================

# Calibration/check sources (individual isotopes)
CALIBRATION_ISOTOPES = [
    "Cs-137", "Co-60", "Am-241", "Ba-133", "Eu-152", "Na-22", "Co-57", "Mn-54"
]

# Medical isotopes (often found individually)
MEDICAL_ISOTOPES = [
    "Tc-99m", "I-131", "I-123", "F-18", "Ga-67", "Ga-68", "In-111", "Lu-177", "Tl-201"
]

# Industrial sources
INDUSTRIAL_ISOTOPES = [
    "Ir-192", "Se-75", "Zn-65", "Co-58", "Cd-109"
]

# Natural decay chains - these ALWAYS appear together in nature
URANIUM_238_CHAIN = ["U-238", "Ra-226", "Pb-214", "Bi-214"]  # Secular equilibrium
THORIUM_232_CHAIN = ["Th-232", "Ac-228", "Pb-212", "Bi-212", "Tl-208"]
URANIUM_235_CHAIN = ["U-235"]  # Daughters have low gamma yield

# Fallout/contamination (often appear in specific combinations)
CHERNOBYL_FUKUSHIMA = ["Cs-137", "Cs-134"]  # Classic reactor fallout signature
FRESH_FALLOUT = ["I-131", "Cs-137", "Cs-134", "Zr-95", "Nb-95"]
OLDER_FALLOUT = ["Cs-137", "Sr-90"]  # Long-lived only

# Natural background (what you'd see with no source)
NATURAL_BACKGROUND = ["K-40"]  # Potassium in environment

# NORM - Naturally Occurring Radioactive Material
NORM_MATERIALS = ["K-40", "Ra-226", "Th-232", "U-238"]


def get_valid_isotopes(isotope_list: List[str]) -> List[str]:
    """Filter to isotopes with gamma lines."""
    valid = []
    for name in isotope_list:
        iso = get_isotope(name)
        if iso and len(iso.gamma_lines) > 0:
            valid.append(name)
    return valid


# Pre-validate all pools
VALID_CALIBRATION = get_valid_isotopes(CALIBRATION_ISOTOPES)
VALID_MEDICAL = get_valid_isotopes(MEDICAL_ISOTOPES)
VALID_INDUSTRIAL = get_valid_isotopes(INDUSTRIAL_ISOTOPES)
VALID_U238_CHAIN = get_valid_isotopes(URANIUM_238_CHAIN)
VALID_TH232_CHAIN = get_valid_isotopes(THORIUM_232_CHAIN)
VALID_FALLOUT = get_valid_isotopes(CHERNOBYL_FUKUSHIMA + FRESH_FALLOUT)
VALID_NORM = get_valid_isotopes(NORM_MATERIALS)

# All valid isotopes for random selection
ALL_VALID_ISOTOPES = list(set(
    VALID_CALIBRATION + VALID_MEDICAL + VALID_INDUSTRIAL +
    VALID_U238_CHAIN + VALID_TH232_CHAIN + VALID_FALLOUT + VALID_NORM
))


# =============================================================================
# SAMPLE SCENARIOS
# =============================================================================

class SampleScenario:
    """Defines a type of sample to generate."""
    
    def __init__(self, name: str, fraction: float):
        self.name = name
        self.fraction = fraction
    
    def generate_sources(self, rng: np.random.Generator, activity_range: Tuple[float, float]) -> List[IsotopeSource]:
        """Generate isotope sources for this scenario."""
        raise NotImplementedError


class BackgroundOnlyScenario(SampleScenario):
    """Pure background - no identifiable sources."""
    
    def __init__(self, fraction: float = 0.15):
        super().__init__("background_only", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        return []  # No sources - just background


class SingleCalibrationScenario(SampleScenario):
    """Single calibration source."""
    
    def __init__(self, fraction: float = 0.20):
        super().__init__("single_calibration", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        isotope = rng.choice(VALID_CALIBRATION)
        activity = rng.uniform(*activity_range)
        return [IsotopeSource(isotope, activity, include_daughters=True)]


class SingleMedicalScenario(SampleScenario):
    """Single medical isotope."""
    
    def __init__(self, fraction: float = 0.10):
        super().__init__("single_medical", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        if not VALID_MEDICAL:
            return []
        isotope = rng.choice(VALID_MEDICAL)
        activity = rng.uniform(*activity_range)
        return [IsotopeSource(isotope, activity, include_daughters=True)]


class SingleIndustrialScenario(SampleScenario):
    """Single industrial source."""
    
    def __init__(self, fraction: float = 0.05):
        super().__init__("single_industrial", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        if not VALID_INDUSTRIAL:
            return []
        isotope = rng.choice(VALID_INDUSTRIAL)
        activity = rng.uniform(*activity_range)
        return [IsotopeSource(isotope, activity, include_daughters=True)]


class UraniumChainScenario(SampleScenario):
    """Natural uranium with decay chain in equilibrium."""
    
    def __init__(self, fraction: float = 0.08):
        super().__init__("uranium_chain", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        # All daughters at ~same activity (secular equilibrium)
        base_activity = rng.uniform(*activity_range)
        sources = []
        for iso in VALID_U238_CHAIN:
            # Slight variation to simulate real-world
            activity = base_activity * rng.uniform(0.8, 1.2)
            sources.append(IsotopeSource(iso, activity, include_daughters=False))
        return sources


class ThoriumChainScenario(SampleScenario):
    """Natural thorium with decay chain."""
    
    def __init__(self, fraction: float = 0.08):
        super().__init__("thorium_chain", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        base_activity = rng.uniform(*activity_range)
        sources = []
        for iso in VALID_TH232_CHAIN:
            activity = base_activity * rng.uniform(0.8, 1.2)
            sources.append(IsotopeSource(iso, activity, include_daughters=False))
        return sources


class NORMScenario(SampleScenario):
    """NORM - naturally occurring radioactive material (multiple natural isotopes)."""
    
    def __init__(self, fraction: float = 0.08):
        super().__init__("norm", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        # Pick 2-4 NORM isotopes
        num_isotopes = rng.integers(2, 5)
        selected = rng.choice(VALID_NORM, size=min(num_isotopes, len(VALID_NORM)), replace=False)
        
        sources = []
        for iso in selected:
            activity = rng.uniform(*activity_range)
            sources.append(IsotopeSource(iso, activity, include_daughters=True))
        return sources


class FalloutScenario(SampleScenario):
    """Reactor fallout signature (Cs-137 + Cs-134 fingerprint)."""
    
    def __init__(self, fraction: float = 0.06):
        super().__init__("fallout", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        sources = []
        
        # Cs-137/Cs-134 ratio varies with age of fallout
        cs137_activity = rng.uniform(*activity_range)
        # Fresh fallout: ~1:1 ratio, aged: Cs-134 decays faster
        age_factor = rng.uniform(0.1, 1.0)  # How "fresh" the fallout is
        cs134_activity = cs137_activity * age_factor
        
        if "Cs-137" in VALID_FALLOUT:
            sources.append(IsotopeSource("Cs-137", cs137_activity, include_daughters=True))
        if "Cs-134" in VALID_FALLOUT and cs134_activity > 0.5:
            sources.append(IsotopeSource("Cs-134", cs134_activity, include_daughters=True))
        
        # Sometimes include I-131 (very fresh fallout only)
        if rng.random() < 0.3 and "I-131" in VALID_FALLOUT:
            sources.append(IsotopeSource("I-131", rng.uniform(1, 50), include_daughters=True))
        
        return sources


class MixedSourcesScenario(SampleScenario):
    """Random mix of 2-3 different source types."""
    
    def __init__(self, fraction: float = 0.10):
        super().__init__("mixed", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        num_isotopes = rng.integers(2, 4)
        selected = rng.choice(ALL_VALID_ISOTOPES, size=num_isotopes, replace=False)
        
        sources = []
        for iso in selected:
            activity = rng.uniform(*activity_range)
            sources.append(IsotopeSource(iso, activity, include_daughters=True))
        return sources


class ComplexMixScenario(SampleScenario):
    """Complex scenario: 4-6 isotopes from various categories."""
    
    def __init__(self, fraction: float = 0.05):
        super().__init__("complex_mix", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        num_isotopes = rng.integers(4, 7)
        selected = set()
        
        # Try to get variety from different pools
        pools = [VALID_CALIBRATION, VALID_MEDICAL, VALID_INDUSTRIAL, VALID_U238_CHAIN, VALID_TH232_CHAIN]
        for pool in pools:
            if len(selected) >= num_isotopes:
                break
            if pool:
                iso = rng.choice(pool)
                selected.add(iso)
        
        # Fill remaining with random
        while len(selected) < num_isotopes:
            iso = rng.choice(ALL_VALID_ISOTOPES)
            selected.add(iso)
        
        sources = []
        for iso in selected:
            activity = rng.uniform(*activity_range)
            sources.append(IsotopeSource(iso, activity, include_daughters=True))
        return sources


class WeakSourceScenario(SampleScenario):
    """Very weak sources - near detection limit."""
    
    def __init__(self, fraction: float = 0.05):
        super().__init__("weak_source", fraction)
    
    def generate_sources(self, rng, activity_range) -> List[IsotopeSource]:
        # Very low activity - near background
        weak_activity_range = (0.1, 5.0)  # Much weaker than normal
        
        isotope = rng.choice(ALL_VALID_ISOTOPES)
        activity = rng.uniform(*weak_activity_range)
        return [IsotopeSource(isotope, activity, include_daughters=True)]


# All scenarios with their fractions (should sum to 1.0)
DEFAULT_SCENARIOS = [
    BackgroundOnlyScenario(0.15),      # 15% - important for "no detection" cases
    SingleCalibrationScenario(0.20),   # 20% - common check sources
    SingleMedicalScenario(0.08),       # 8%  - medical isotopes
    SingleIndustrialScenario(0.05),    # 5%  - industrial sources
    UraniumChainScenario(0.10),        # 10% - natural uranium + daughters
    ThoriumChainScenario(0.10),        # 10% - natural thorium + daughters
    NORMScenario(0.07),                # 7%  - NORM materials
    FalloutScenario(0.05),             # 5%  - reactor fallout signature
    MixedSourcesScenario(0.10),        # 10% - random 2-3 isotope mixes
    ComplexMixScenario(0.05),          # 5%  - complex 4-6 isotope scenarios
    WeakSourceScenario(0.05),          # 5%  - near-detection-limit sources
]


# =============================================================================
# BACKGROUND VARIATION
# =============================================================================

class BackgroundConfig:
    """Configuration for varied background generation."""
    
    def __init__(
        self,
        intensity_min: float = 0.3,
        intensity_max: float = 3.0,
        k40_prob: float = 0.95,
        radon_prob: float = 0.8,
        thorium_prob: float = 0.6,
    ):
        self.intensity_min = intensity_min
        self.intensity_max = intensity_max
        self.k40_prob = k40_prob
        self.radon_prob = radon_prob
        self.thorium_prob = thorium_prob
    
    def sample(self, rng: np.random.Generator) -> dict:
        """Sample a random background configuration."""
        return {
            'background_cps': rng.uniform(self.intensity_min, self.intensity_max) * 5.0,
            'include_k40': rng.random() < self.k40_prob,
            'include_radon': rng.random() < self.radon_prob,
            'include_thorium': rng.random() < self.thorium_prob,
        }


# =============================================================================
# SAMPLE GENERATION
# =============================================================================

def generate_single_sample(args: Tuple[int, dict]) -> Optional[str]:
    """
    Generate a single sample for parallel processing.
    
    Args:
        args: Tuple of (sample_index, config_dict)
    
    Returns:
        Sample ID if successful, None if failed
    """
    sample_idx, config = args
    
    try:
        # Create RNG with unique seed per sample
        rng = np.random.default_rng(config['base_seed'] + sample_idx)
        
        # Initialize generator
        detector_config = RADIACODE_CONFIGS.get(config['detector_name'])
        generator = SpectrumGenerator(detector_config=detector_config)
        
        # Select scenario based on cumulative probabilities
        scenarios = config['scenarios']
        scenario_probs = [s.fraction for s in scenarios]
        scenario = rng.choice(scenarios, p=scenario_probs)
        
        # Generate sources for this scenario
        sources = scenario.generate_sources(rng, config['activity_range'])
        
        # Background configuration
        bg_config = BackgroundConfig(
            intensity_min=config.get('bg_intensity_min', 0.3),
            intensity_max=config.get('bg_intensity_max', 3.0),
        )
        bg_params = bg_config.sample(rng)
        
        # FIXED 60-second duration for 2D model
        duration = 60.0
        
        # Create spectrum config
        spec_config = SpectrumConfig(
            duration_seconds=duration,
            time_interval_seconds=1.0,  # 1 second per interval = 60 intervals
            sources=sources,
            include_background=True,
            background_cps=bg_params['background_cps'],
            include_k40=bg_params['include_k40'],
            include_radon=bg_params['include_radon'],
            include_thorium=bg_params['include_thorium'],
            detector_name=config['detector_name'],
        )
        
        # Generate spectrum
        spectrum = generator.generate_spectrum(spec_config)
        
        # Save spectrum
        output_dir = Path(config['output_dir']) / "spectra"
        save_spectrum(
            spectrum,
            output_dir,
            save_image=True,   # Save NPY file
            image_format='npy'  # Skip PNG for speed
        )
        
        return spectrum.sample_id
        
    except Exception as e:
        print(f"Error generating sample {sample_idx}: {e}")
        import traceback
        traceback.print_exc()
        return None


def generate_training_data_v3(
    num_samples: int,
    output_dir: Path,
    detector_name: str = "radiacode_103",
    activity_range: Tuple[float, float] = (1.0, 100.0),
    bg_intensity_range: Tuple[float, float] = (0.3, 3.0),
    scenarios: Optional[List[SampleScenario]] = None,
    num_workers: int = None,
    random_seed: int = None,
) -> int:
    """
    Generate training samples in parallel.
    
    Args:
        num_samples: Total number of samples to generate
        output_dir: Output directory
        detector_name: Detector to simulate
        activity_range: (min, max) activity in Bq
        bg_intensity_range: Background intensity multiplier range
        scenarios: List of SampleScenario objects (default: DEFAULT_SCENARIOS)
        num_workers: Number of parallel workers
        random_seed: Base random seed
    
    Returns:
        Number of successfully generated samples
    """
    if num_workers is None:
        num_workers = max(1, cpu_count() - 1)
    
    if random_seed is None:
        random_seed = int(time.time())
    
    if scenarios is None:
        scenarios = DEFAULT_SCENARIOS
    
    # Normalize scenario fractions
    total_fraction = sum(s.fraction for s in scenarios)
    for s in scenarios:
        s.fraction /= total_fraction
    
    # Create output directory
    output_dir = Path(output_dir)
    spectra_dir = output_dir / "spectra"
    spectra_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"=" * 70)
    print(f"SYNTHETIC SPECTRA GENERATION v3 - Optimized for 2D Model")
    print(f"=" * 70)
    print(f"\nConfiguration:")
    print(f"  Samples: {num_samples:,}")
    print(f"  Output: {output_dir}")
    print(f"  Detector: {detector_name}")
    print(f"  Duration: 60 seconds (fixed)")
    print(f"  Activity range: {activity_range[0]:.1f} - {activity_range[1]:.1f} Bq")
    print(f"  Workers: {num_workers}")
    print(f"\nScenario distribution:")
    for s in scenarios:
        count = int(num_samples * s.fraction)
        print(f"  {s.name}: {s.fraction*100:.1f}% (~{count:,} samples)")
    print()
    
    # Shared config for all workers
    shared_config = {
        'detector_name': detector_name,
        'output_dir': str(output_dir),
        'activity_range': activity_range,
        'bg_intensity_min': bg_intensity_range[0],
        'bg_intensity_max': bg_intensity_range[1],
        'base_seed': random_seed,
        'scenarios': scenarios,
    }
    
    # Create work items
    work_items = [(i, shared_config) for i in range(num_samples)]
    
    # Progress tracking
    start_time = time.time()
    completed = 0
    failed = 0
    last_report = 0
    
    print(f"Starting generation...")
    
    # Generate in parallel
    with Pool(num_workers) as pool:
        for result in pool.imap_unordered(generate_single_sample, work_items, chunksize=100):
            if result is not None:
                completed += 1
            else:
                failed += 1
            
            total = completed + failed
            
            # Progress report every 1%
            if total - last_report >= num_samples // 100 or total == num_samples:
                elapsed = time.time() - start_time
                rate = completed / elapsed if elapsed > 0 else 0
                eta = (num_samples - total) / rate if rate > 0 else 0
                
                print(f"\r  Progress: {total:,}/{num_samples:,} ({100*total/num_samples:.1f}%) | "
                      f"Rate: {rate:.1f}/s | "
                      f"ETA: {eta/60:.1f}m | "
                      f"Failed: {failed}", end="", flush=True)
                last_report = total
    
    total_time = time.time() - start_time
    
    print(f"\n\nGeneration complete!")
    print(f"  Total time: {total_time/60:.1f} minutes")
    print(f"  Successful: {completed:,}")
    print(f"  Failed: {failed}")
    print(f"  Rate: {completed/total_time:.1f} samples/second")
    
    return completed


def main():
    parser = argparse.ArgumentParser(description='Generate synthetic gamma spectra v3')
    parser.add_argument('--num_samples', '-n', type=int, default=200000,
                        help='Number of samples to generate')
    parser.add_argument('--output_dir', '-o', type=str, default='data/synthetic',
                        help='Output directory')
    parser.add_argument('--detector', '-d', type=str, default='radiacode_103',
                        help='Detector type')
    parser.add_argument('--workers', '-w', type=int, default=None,
                        help='Number of parallel workers')
    parser.add_argument('--seed', '-s', type=int, default=None,
                        help='Random seed')
    parser.add_argument('--activity_min', type=float, default=1.0,
                        help='Minimum activity in Bq')
    parser.add_argument('--activity_max', type=float, default=100.0,
                        help='Maximum activity in Bq')
    
    args = parser.parse_args()
    
    generate_training_data_v3(
        num_samples=args.num_samples,
        output_dir=Path(args.output_dir),
        detector_name=args.detector,
        activity_range=(args.activity_min, args.activity_max),
        num_workers=args.workers,
        random_seed=args.seed,
    )


if __name__ == '__main__':
    main()
