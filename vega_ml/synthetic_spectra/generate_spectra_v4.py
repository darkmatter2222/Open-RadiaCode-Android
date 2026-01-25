"""
Synthetic Spectra Generation Script v4

Optimized for statistical significance with:
- 300-second duration (300 time intervals) for 5x better counting statistics
- Compact binary storage format (~10x smaller than JSON)
- Memory-efficient design for large datasets

Storage Format:
- NPY files: float16 to save 50% space (plenty for normalized 0-1 data)
- Labels: Packed binary instead of JSON
  - 1 byte: isotope count
  - N bytes: isotope IDs (index into master list)
  - N*2 bytes: activity as uint16 (scaled 0-65535 for 0-1000 Bq)
  - 1 byte: flags (normalized, bg_k40, bg_radon, bg_thorium)

200k samples at 300s each:
- NPY: 300 * 1023 * 2 bytes = 614KB/sample -> 123GB total
- Labels: ~20 bytes/sample -> 4MB total (vs ~500MB with JSON!)

Usage:
    python -m synthetic_spectra.generate_spectra_v4 --num_samples 200000 --workers 16
"""

import argparse
import struct
import sys
from pathlib import Path
from datetime import datetime
import numpy as np
from multiprocessing import Pool, cpu_count
import time
from typing import List, Tuple, Optional
import os

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from synthetic_spectra.generator import (
    SpectrumGenerator,
    SpectrumConfig,
    IsotopeSource,
    GeneratedSpectrum,
)
from synthetic_spectra.config import RADIACODE_CONFIGS
from synthetic_spectra.ground_truth import get_isotope, get_all_isotopes


# =============================================================================
# MASTER ISOTOPE LIST - Fixed ordering for binary encoding
# =============================================================================

# Build master list of all isotopes with gamma lines
_all_isotopes = [iso for iso in get_all_isotopes() if len(iso.gamma_lines) > 0]
ISOTOPE_INDEX = {iso.name: i for i, iso in enumerate(_all_isotopes)}
INDEX_TO_ISOTOPE = {i: iso.name for i, iso in enumerate(_all_isotopes)}
NUM_ISOTOPES = len(ISOTOPE_INDEX)

print(f"Master isotope list: {NUM_ISOTOPES} isotopes")


# =============================================================================
# ISOTOPE POOLS - Same as v3 but using indices
# =============================================================================

CALIBRATION_ISOTOPES = [
    "Cs-137", "Co-60", "Am-241", "Ba-133", "Eu-152", "Na-22", "Co-57", "Mn-54"
]
MEDICAL_ISOTOPES = [
    "Tc-99m", "I-131", "I-123", "F-18", "Ga-67", "Ga-68", "In-111", "Lu-177", "Tl-201"
]
INDUSTRIAL_ISOTOPES = [
    "Ir-192", "Se-75", "Zn-65", "Co-58", "Cd-109"
]
URANIUM_238_CHAIN = ["U-238", "Ra-226", "Pb-214", "Bi-214"]
THORIUM_232_CHAIN = ["Th-232", "Ac-228", "Pb-212", "Bi-212", "Tl-208"]
CHERNOBYL_FUKUSHIMA = ["Cs-137", "Cs-134"]
FRESH_FALLOUT = ["I-131", "Cs-137", "Cs-134", "Zr-95", "Nb-95"]
NORM_MATERIALS = ["K-40", "Ra-226", "Th-232", "U-238"]


def get_valid_isotopes(isotope_list: List[str]) -> List[str]:
    """Filter to isotopes with gamma lines that are in our index."""
    return [name for name in isotope_list if name in ISOTOPE_INDEX]


VALID_CALIBRATION = get_valid_isotopes(CALIBRATION_ISOTOPES)
VALID_MEDICAL = get_valid_isotopes(MEDICAL_ISOTOPES)
VALID_INDUSTRIAL = get_valid_isotopes(INDUSTRIAL_ISOTOPES)
VALID_U238_CHAIN = get_valid_isotopes(URANIUM_238_CHAIN)
VALID_TH232_CHAIN = get_valid_isotopes(THORIUM_232_CHAIN)
VALID_FALLOUT = get_valid_isotopes(CHERNOBYL_FUKUSHIMA + FRESH_FALLOUT)
VALID_NORM = get_valid_isotopes(NORM_MATERIALS)

ALL_VALID_ISOTOPES = list(set(
    VALID_CALIBRATION + VALID_MEDICAL + VALID_INDUSTRIAL +
    VALID_U238_CHAIN + VALID_TH232_CHAIN + VALID_FALLOUT + VALID_NORM
))


# =============================================================================
# COMPACT BINARY LABEL FORMAT
# =============================================================================

def pack_labels(
    isotopes: List[str],
    activities: dict,
    flags: dict
) -> bytes:
    """
    Pack labels into compact binary format.
    
    Format:
        1 byte:  num_source_isotopes
        N bytes: isotope indices (uint8)
        N*2 bytes: activities as uint16 (0-65535 maps to 0-1000 Bq)
        1 byte:  flags bitfield
            bit 0: normalized
            bit 1: include_k40
            bit 2: include_radon  
            bit 3: include_thorium
    
    Max size: 1 + 255 + 510 + 1 = 767 bytes (vs ~500 bytes JSON)
    Typical: 1 + 4 + 8 + 1 = 14 bytes (vs ~400 bytes JSON!)
    """
    data = bytearray()
    
    # Number of isotopes
    num_iso = len(isotopes)
    data.append(min(num_iso, 255))
    
    # Isotope indices
    for iso in isotopes[:255]:
        idx = ISOTOPE_INDEX.get(iso, 0)
        data.append(idx)
    
    # Activities (scaled to uint16)
    for iso in isotopes[:255]:
        activity = activities.get(iso, 0.0)
        # Scale: 0-1000 Bq -> 0-65535
        scaled = int(min(activity, 1000.0) * 65.535)
        scaled = max(0, min(65535, scaled))
        data.extend(struct.pack('<H', scaled))
    
    # Flags
    flag_byte = 0
    if flags.get('normalized', True):
        flag_byte |= 0x01
    if flags.get('include_k40', True):
        flag_byte |= 0x02
    if flags.get('include_radon', True):
        flag_byte |= 0x04
    if flags.get('include_thorium', True):
        flag_byte |= 0x08
    data.append(flag_byte)
    
    return bytes(data)


def unpack_labels(data: bytes) -> dict:
    """Unpack binary labels back to dict."""
    offset = 0
    
    # Number of isotopes
    num_iso = data[offset]
    offset += 1
    
    # Isotope indices
    isotopes = []
    for _ in range(num_iso):
        idx = data[offset]
        isotopes.append(INDEX_TO_ISOTOPE.get(idx, f"Unknown-{idx}"))
        offset += 1
    
    # Activities
    activities = {}
    for iso in isotopes:
        scaled = struct.unpack('<H', data[offset:offset+2])[0]
        activities[iso] = scaled / 65.535
        offset += 2
    
    # Flags
    flags = data[offset]
    
    return {
        'isotopes': isotopes,
        'activities': activities,
        'normalized': bool(flags & 0x01),
        'include_k40': bool(flags & 0x02),
        'include_radon': bool(flags & 0x04),
        'include_thorium': bool(flags & 0x08),
    }


# =============================================================================
# SCENARIO CLASSES (same logic as v3)
# =============================================================================

def generate_background_sources(rng) -> List[IsotopeSource]:
    return []

def generate_single_calibration(rng, activity_range) -> List[IsotopeSource]:
    isotope = rng.choice(VALID_CALIBRATION)
    activity = rng.uniform(*activity_range)
    return [IsotopeSource(isotope, activity, include_daughters=True)]

def generate_single_medical(rng, activity_range) -> List[IsotopeSource]:
    if not VALID_MEDICAL:
        return []
    isotope = rng.choice(VALID_MEDICAL)
    activity = rng.uniform(*activity_range)
    return [IsotopeSource(isotope, activity, include_daughters=True)]

def generate_single_industrial(rng, activity_range) -> List[IsotopeSource]:
    if not VALID_INDUSTRIAL:
        return []
    isotope = rng.choice(VALID_INDUSTRIAL)
    activity = rng.uniform(*activity_range)
    return [IsotopeSource(isotope, activity, include_daughters=True)]

def generate_uranium_chain(rng, activity_range) -> List[IsotopeSource]:
    base_activity = rng.uniform(*activity_range)
    sources = []
    for iso in VALID_U238_CHAIN:
        activity = base_activity * rng.uniform(0.8, 1.2)
        sources.append(IsotopeSource(iso, activity, include_daughters=False))
    return sources

def generate_thorium_chain(rng, activity_range) -> List[IsotopeSource]:
    base_activity = rng.uniform(*activity_range)
    sources = []
    for iso in VALID_TH232_CHAIN:
        activity = base_activity * rng.uniform(0.8, 1.2)
        sources.append(IsotopeSource(iso, activity, include_daughters=False))
    return sources

def generate_norm(rng, activity_range) -> List[IsotopeSource]:
    num_isotopes = rng.integers(2, 5)
    selected = rng.choice(VALID_NORM, size=min(num_isotopes, len(VALID_NORM)), replace=False)
    sources = []
    for iso in selected:
        activity = rng.uniform(*activity_range)
        sources.append(IsotopeSource(iso, activity, include_daughters=True))
    return sources

def generate_fallout(rng, activity_range) -> List[IsotopeSource]:
    sources = []
    cs137_activity = rng.uniform(*activity_range)
    age_factor = rng.uniform(0.1, 1.0)
    cs134_activity = cs137_activity * age_factor
    
    if "Cs-137" in VALID_FALLOUT:
        sources.append(IsotopeSource("Cs-137", cs137_activity, include_daughters=True))
    if "Cs-134" in VALID_FALLOUT and cs134_activity > 0.5:
        sources.append(IsotopeSource("Cs-134", cs134_activity, include_daughters=True))
    if rng.random() < 0.3 and "I-131" in VALID_FALLOUT:
        sources.append(IsotopeSource("I-131", rng.uniform(1, 50), include_daughters=True))
    return sources

def generate_mixed(rng, activity_range) -> List[IsotopeSource]:
    num_isotopes = rng.integers(2, 4)
    selected = rng.choice(ALL_VALID_ISOTOPES, size=num_isotopes, replace=False)
    sources = []
    for iso in selected:
        activity = rng.uniform(*activity_range)
        sources.append(IsotopeSource(iso, activity, include_daughters=True))
    return sources

def generate_complex(rng, activity_range) -> List[IsotopeSource]:
    num_isotopes = rng.integers(4, 7)
    selected = set()
    pools = [VALID_CALIBRATION, VALID_MEDICAL, VALID_INDUSTRIAL, VALID_U238_CHAIN, VALID_TH232_CHAIN]
    for pool in pools:
        if len(selected) >= num_isotopes:
            break
        if pool:
            iso = rng.choice(pool)
            selected.add(iso)
    while len(selected) < num_isotopes:
        iso = rng.choice(ALL_VALID_ISOTOPES)
        selected.add(iso)
    sources = []
    for iso in selected:
        activity = rng.uniform(*activity_range)
        sources.append(IsotopeSource(iso, activity, include_daughters=True))
    return sources

def generate_weak(rng, activity_range) -> List[IsotopeSource]:
    weak_activity_range = (0.1, 5.0)
    isotope = rng.choice(ALL_VALID_ISOTOPES)
    activity = rng.uniform(*weak_activity_range)
    return [IsotopeSource(isotope, activity, include_daughters=True)]


# Scenario distribution (name, fraction, generator_func)
SCENARIOS = [
    ("background", 0.15, generate_background_sources),
    ("calibration", 0.20, generate_single_calibration),
    ("medical", 0.08, generate_single_medical),
    ("industrial", 0.05, generate_single_industrial),
    ("uranium", 0.10, generate_uranium_chain),
    ("thorium", 0.10, generate_thorium_chain),
    ("norm", 0.07, generate_norm),
    ("fallout", 0.05, generate_fallout),
    ("mixed", 0.10, generate_mixed),
    ("complex", 0.05, generate_complex),
    ("weak", 0.05, generate_weak),
]


# =============================================================================
# SAMPLE GENERATION
# =============================================================================

def generate_single_sample_v4(args: Tuple[int, dict]) -> Optional[str]:
    """Generate a single sample with compact storage."""
    sample_idx, config = args
    
    try:
        rng = np.random.default_rng(config['base_seed'] + sample_idx)
        
        # Initialize generator
        detector_config = RADIACODE_CONFIGS.get(config['detector_name'])
        generator = SpectrumGenerator(detector_config=detector_config)
        
        # Select scenario
        scenario_names = [s[0] for s in SCENARIOS]
        scenario_probs = np.array([s[1] for s in SCENARIOS])
        scenario_probs /= scenario_probs.sum()
        scenario_idx = rng.choice(len(SCENARIOS), p=scenario_probs)
        scenario_name, _, generator_func = SCENARIOS[scenario_idx]
        
        # Generate sources
        if scenario_name == "background":
            sources = []
        else:
            sources = generator_func(rng, config['activity_range'])
        
        # Background config
        bg_cps = rng.uniform(config['bg_min'], config['bg_max']) * 5.0
        include_k40 = rng.random() < 0.95
        include_radon = rng.random() < 0.80
        include_thorium = rng.random() < 0.60
        
        # 300-second duration with 1-second intervals
        duration = 300.0
        
        spec_config = SpectrumConfig(
            duration_seconds=duration,
            time_interval_seconds=1.0,
            sources=sources,
            include_background=True,
            background_cps=bg_cps,
            include_k40=include_k40,
            include_radon=include_radon,
            include_thorium=include_thorium,
            detector_name=config['detector_name'],
        )
        
        spectrum = generator.generate_spectrum(spec_config)
        
        # Save spectrum data as float16 (50% smaller than float32)
        output_dir = Path(config['output_dir']) / "spectra"
        output_dir.mkdir(parents=True, exist_ok=True)
        
        sample_id = f"{sample_idx:08d}"
        
        # Save NPY as float16
        npy_path = output_dir / f"s{sample_id}.npy"
        np.save(npy_path, spectrum.data.astype(np.float16))
        
        # Save compact binary labels
        label_path = output_dir / f"s{sample_id}.lbl"
        
        isotopes = spectrum.isotopes_present
        activities = {s.isotope_name: s.activity_bq for s in sources}
        flags = {
            'normalized': True,
            'include_k40': include_k40,
            'include_radon': include_radon,
            'include_thorium': include_thorium,
        }
        
        label_data = pack_labels(isotopes, activities, flags)
        with open(label_path, 'wb') as f:
            f.write(label_data)
        
        return sample_id
        
    except Exception as e:
        print(f"\nError generating sample {sample_idx}: {e}")
        import traceback
        traceback.print_exc()
        return None


def generate_training_data_v4(
    num_samples: int,
    output_dir: Path,
    detector_name: str = "radiacode_103",
    activity_range: Tuple[float, float] = (1.0, 100.0),
    bg_range: Tuple[float, float] = (0.3, 3.0),
    num_workers: int = None,
    random_seed: int = None,
) -> int:
    """Generate training samples with compact storage format."""
    if num_workers is None:
        num_workers = max(1, cpu_count() - 1)
    
    if random_seed is None:
        random_seed = int(time.time())
    
    output_dir = Path(output_dir)
    spectra_dir = output_dir / "spectra"
    spectra_dir.mkdir(parents=True, exist_ok=True)
    
    # Calculate storage estimates
    npy_size_bytes = 300 * 1023 * 2  # float16
    label_size_bytes = 20  # average
    total_npy_gb = (npy_size_bytes * num_samples) / (1024**3)
    total_label_mb = (label_size_bytes * num_samples) / (1024**2)
    
    print("=" * 70)
    print("SYNTHETIC SPECTRA GENERATION v4 - 300s Integration, Compact Storage")
    print("=" * 70)
    print(f"\nConfiguration:")
    print(f"  Samples: {num_samples:,}")
    print(f"  Output: {output_dir}")
    print(f"  Detector: {detector_name}")
    print(f"  Duration: 300 seconds (5x better counting statistics)")
    print(f"  Activity range: {activity_range[0]:.1f} - {activity_range[1]:.1f} Bq")
    print(f"  Workers: {num_workers}")
    print(f"\nStorage estimates:")
    print(f"  NPY (float16): {npy_size_bytes/1024:.1f} KB/sample -> {total_npy_gb:.1f} GB total")
    print(f"  Labels (binary): ~{label_size_bytes} bytes/sample -> {total_label_mb:.1f} MB total")
    print(f"  Total: ~{total_npy_gb:.1f} GB")
    print(f"\nScenario distribution:")
    for name, frac, _ in SCENARIOS:
        count = int(num_samples * frac)
        print(f"  {name}: {frac*100:.1f}% (~{count:,} samples)")
    
    # Save metadata
    metadata = {
        'version': 4,
        'num_samples': num_samples,
        'duration_seconds': 300,
        'time_intervals': 300,
        'channels': 1023,
        'energy_range_kev': [20, 3000],
        'detector': detector_name,
        'activity_range': activity_range,
        'dtype': 'float16',
        'label_format': 'binary',
        'isotope_index': ISOTOPE_INDEX,
        'generated_at': datetime.now().isoformat(),
    }
    
    import json
    with open(output_dir / "metadata.json", 'w') as f:
        json.dump(metadata, f, indent=2)
    
    # Shared config
    shared_config = {
        'detector_name': detector_name,
        'output_dir': str(output_dir),
        'activity_range': activity_range,
        'bg_min': bg_range[0],
        'bg_max': bg_range[1],
        'base_seed': random_seed,
    }
    
    work_items = [(i, shared_config) for i in range(num_samples)]
    
    start_time = time.time()
    completed = 0
    failed = 0
    last_report = 0
    
    print(f"\nStarting generation...")
    
    with Pool(num_workers) as pool:
        for result in pool.imap_unordered(generate_single_sample_v4, work_items, chunksize=100):
            if result is not None:
                completed += 1
            else:
                failed += 1
            
            total = completed + failed
            
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
    parser = argparse.ArgumentParser(description='Generate synthetic gamma spectra v4')
    parser.add_argument('--num_samples', '-n', type=int, default=200000,
                        help='Number of samples to generate')
    parser.add_argument('--output_dir', '-o', type=str, default='O:/master_data_collection/isotopev4',
                        help='Output directory')
    parser.add_argument('--detector', '-d', type=str, default='radiacode_103',
                        help='Detector type')
    parser.add_argument('--workers', '-w', type=int, default=None,
                        help='Number of parallel workers')
    parser.add_argument('--seed', '-s', type=int, default=42,
                        help='Random seed')
    parser.add_argument('--activity_min', type=float, default=1.0,
                        help='Minimum activity in Bq')
    parser.add_argument('--activity_max', type=float, default=100.0,
                        help='Maximum activity in Bq')
    
    args = parser.parse_args()
    
    generate_training_data_v4(
        num_samples=args.num_samples,
        output_dir=Path(args.output_dir),
        detector_name=args.detector,
        activity_range=(args.activity_min, args.activity_max),
        num_workers=args.workers,
        random_seed=args.seed,
    )


if __name__ == '__main__':
    main()
