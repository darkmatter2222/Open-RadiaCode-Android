"""
Synthetic Spectra Generation Script v2

Improvements over v1:
- Parallel generation using multiprocessing for 10x+ speedup
- Class-balanced isotope sampling to ensure all isotopes are represented
- More variable background noise (intensity, composition)
- Memory efficient - doesn't accumulate spectra in memory
- Progress bar with ETA

Usage:
    python -m synthetic_spectra.generate_spectra_v2 --num_samples 100000 --workers 8
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
# ISOTOPE POOL WITH CATEGORIES FOR BALANCED SAMPLING
# =============================================================================

ISOTOPE_CATEGORIES = {
    "calibration": [
        "Cs-137", "Co-60", "Am-241", "Ba-133", "Eu-152", "Na-22", "Co-57", "Mn-54"
    ],
    "medical": [
        "Tc-99m", "I-131", "I-123", "F-18", "Ga-67", "Ga-68", "In-111", "Lu-177", "Tl-201"
    ],
    "industrial": [
        "Ir-192", "Se-75", "Zn-65", "Co-58", "Cd-109"
    ],
    "natural_background": [
        "K-40", "Ra-226", "U-235", "U-238", "Th-232"
    ],
    "decay_chain_u238": [
        "Pb-214", "Bi-214", "Pb-210"
    ],
    "decay_chain_th232": [
        "Pb-212", "Bi-212", "Tl-208", "Ac-228", "Ra-224"
    ],
    "reactor_fallout": [
        "Cs-134", "I-131", "Sr-90", "Zr-95", "Nb-95", "Ru-103", "Ce-141", "Ce-144", "Sb-125"
    ],
}


def get_valid_isotope_pool() -> Tuple[List[str], Dict[str, List[str]]]:
    """
    Get all valid isotopes (with gamma lines) organized by category.
    
    Returns:
        Tuple of (flat_list, category_dict)
    """
    valid_categories = {}
    all_isotopes = []
    
    for category, isotopes in ISOTOPE_CATEGORIES.items():
        valid = []
        for name in isotopes:
            iso = get_isotope(name)
            if iso and len(iso.gamma_lines) > 0:
                valid.append(name)
                if name not in all_isotopes:
                    all_isotopes.append(name)
        valid_categories[category] = valid
    
    return all_isotopes, valid_categories


# =============================================================================
# BACKGROUND VARIATION
# =============================================================================

class BackgroundConfig:
    """Configuration for varied background generation."""
    
    def __init__(
        self,
        intensity_min: float = 0.3,
        intensity_max: float = 3.0,
        k40_prob: float = 0.95,  # Almost always present
        radon_prob: float = 0.8,  # Usually present indoors
        thorium_prob: float = 0.6,  # Sometimes present
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
# SINGLE SAMPLE GENERATION (for parallel workers)
# =============================================================================

def generate_single_sample(
    args: Tuple[int, dict]
) -> Optional[str]:
    """
    Generate a single sample. Designed to be called by worker processes.
    
    Args:
        args: Tuple of (sample_index, config_dict)
    
    Returns:
        Sample ID if successful, None if failed
    """
    sample_idx, config = args
    
    try:
        # Create RNG with unique seed per sample
        rng = np.random.default_rng(config['base_seed'] + sample_idx)
        
        # Initialize generator (each worker creates its own)
        detector_config = RADIACODE_CONFIGS.get(config['detector_name'])
        generator = SpectrumGenerator(detector_config=detector_config)
        
        # Determine sample type based on distribution
        sample_type = config['sample_types'][sample_idx % len(config['sample_types'])]
        
        # Get isotopes for this sample
        isotope_pool = config['isotope_pool']
        category_pools = config['category_pools']
        
        # Sample background configuration
        bg_config = BackgroundConfig(
            intensity_min=config.get('bg_intensity_min', 0.3),
            intensity_max=config.get('bg_intensity_max', 3.0),
        )
        bg_params = bg_config.sample(rng)
        
        # Random duration
        duration = rng.uniform(*config['duration_range'])
        
        # Build sources based on sample type
        sources = []
        
        if sample_type == 'single':
            # For class balance, cycle through isotopes
            isotope_idx = sample_idx % len(isotope_pool)
            isotope = isotope_pool[isotope_idx]
            activity = rng.uniform(*config['activity_range'])
            sources.append(IsotopeSource(
                isotope_name=isotope,
                activity_bq=activity,
                include_daughters=True
            ))
            
        elif sample_type == 'dual':
            # Pick from different categories for variety
            categories = list(category_pools.keys())
            cat1, cat2 = rng.choice(categories, size=2, replace=True)
            iso1 = rng.choice(category_pools[cat1]) if category_pools[cat1] else rng.choice(isotope_pool)
            iso2 = rng.choice(category_pools[cat2]) if category_pools[cat2] else rng.choice(isotope_pool)
            
            # Ensure different isotopes
            while iso2 == iso1:
                iso2 = rng.choice(isotope_pool)
            
            for iso in [iso1, iso2]:
                activity = rng.uniform(*config['activity_range'])
                sources.append(IsotopeSource(
                    isotope_name=iso,
                    activity_bq=activity,
                    include_daughters=True
                ))
                
        elif sample_type == 'multi':
            # 3-5 isotopes from various categories
            num_isotopes = rng.integers(3, 6)
            selected = set()
            
            for _ in range(num_isotopes):
                cat = rng.choice(list(category_pools.keys()))
                pool = category_pools[cat] if category_pools[cat] else isotope_pool
                iso = rng.choice(pool)
                
                # Avoid duplicates
                attempts = 0
                while iso in selected and attempts < 10:
                    iso = rng.choice(isotope_pool)
                    attempts += 1
                
                if iso not in selected:
                    selected.add(iso)
                    activity = rng.uniform(*config['activity_range'])
                    sources.append(IsotopeSource(
                        isotope_name=iso,
                        activity_bq=activity,
                        include_daughters=True
                    ))
        
        # elif sample_type == 'background': sources stays empty
        
        # Create spectrum config
        spec_config = SpectrumConfig(
            duration_seconds=duration,
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
            save_image=True,
            image_format='npy'  # Skip PNG for speed
        )
        
        return spectrum.sample_id
        
    except Exception as e:
        print(f"Error generating sample {sample_idx}: {e}")
        return None


# =============================================================================
# MAIN BATCH GENERATION
# =============================================================================

def generate_training_batch_parallel(
    num_samples: int,
    output_dir: Path,
    detector_name: str = "radiacode_103",
    duration_range: Tuple[float, float] = (60, 300),
    activity_range: Tuple[float, float] = (1.0, 100.0),
    single_isotope_fraction: float = 0.40,
    dual_isotope_fraction: float = 0.30,
    multi_isotope_fraction: float = 0.20,
    background_only_fraction: float = 0.10,
    bg_intensity_range: Tuple[float, float] = (0.3, 3.0),
    num_workers: int = None,
    random_seed: int = None,
    chunk_size: int = 100,
) -> int:
    """
    Generate training samples in parallel.
    
    Args:
        num_samples: Total number of samples to generate
        output_dir: Output directory
        detector_name: Detector to simulate
        duration_range: (min, max) duration in seconds
        activity_range: (min, max) activity in Bq
        single_isotope_fraction: Fraction of single-isotope samples
        dual_isotope_fraction: Fraction of dual-isotope samples
        multi_isotope_fraction: Fraction of multi-isotope samples
        background_only_fraction: Fraction of background-only samples
        bg_intensity_range: (min, max) background intensity multiplier
        num_workers: Number of parallel workers (default: CPU count - 1)
        random_seed: Base random seed
        chunk_size: Number of samples per worker batch
    
    Returns:
        Number of successfully generated samples
    """
    if num_workers is None:
        num_workers = max(1, cpu_count() - 1)
    
    if random_seed is None:
        random_seed = int(time.time())
    
    # Create output directory
    output_dir = Path(output_dir)
    spectra_dir = output_dir / "spectra"
    spectra_dir.mkdir(parents=True, exist_ok=True)
    
    # Get isotope pools
    isotope_pool, category_pools = get_valid_isotope_pool()
    
    print(f"Isotope pool: {len(isotope_pool)} isotopes across {len(category_pools)} categories")
    
    # Calculate sample counts
    n_single = int(num_samples * single_isotope_fraction)
    n_dual = int(num_samples * dual_isotope_fraction)
    n_multi = int(num_samples * multi_isotope_fraction)
    n_background = int(num_samples * background_only_fraction)
    
    # Adjust to hit exact count
    remaining = num_samples - (n_single + n_dual + n_multi + n_background)
    n_single += remaining
    
    # Create sample type list (shuffled for variety in batches)
    sample_types = (
        ['single'] * n_single +
        ['dual'] * n_dual +
        ['multi'] * n_multi +
        ['background'] * n_background
    )
    np.random.seed(random_seed)
    np.random.shuffle(sample_types)
    
    print(f"\nGenerating {num_samples} samples with {num_workers} workers:")
    print(f"  - Single isotope: {n_single} ({single_isotope_fraction*100:.0f}%)")
    print(f"  - Dual isotope: {n_dual} ({dual_isotope_fraction*100:.0f}%)")
    print(f"  - Multi isotope: {n_multi} ({multi_isotope_fraction*100:.0f}%)")
    print(f"  - Background only: {n_background} ({background_only_fraction*100:.0f}%)")
    print(f"  - Background intensity: {bg_intensity_range[0]:.1f}x - {bg_intensity_range[1]:.1f}x")
    print()
    
    # Shared config for all workers
    shared_config = {
        'detector_name': detector_name,
        'output_dir': str(output_dir),
        'duration_range': duration_range,
        'activity_range': activity_range,
        'bg_intensity_min': bg_intensity_range[0],
        'bg_intensity_max': bg_intensity_range[1],
        'base_seed': random_seed,
        'isotope_pool': isotope_pool,
        'category_pools': category_pools,
        'sample_types': sample_types,
    }
    
    # Generate samples in parallel
    start_time = time.time()
    successful = 0
    
    # Create argument list
    args_list = [(i, shared_config) for i in range(num_samples)]
    
    # Use multiprocessing pool
    with Pool(processes=num_workers) as pool:
        # Process in chunks and report progress
        for i in range(0, num_samples, chunk_size):
            chunk_end = min(i + chunk_size, num_samples)
            chunk_args = args_list[i:chunk_end]
            
            results = pool.map(generate_single_sample, chunk_args)
            
            chunk_success = sum(1 for r in results if r is not None)
            successful += chunk_success
            
            # Progress report
            elapsed = time.time() - start_time
            rate = successful / elapsed if elapsed > 0 else 0
            eta = (num_samples - successful) / rate if rate > 0 else 0
            
            print(f"  Progress: {successful}/{num_samples} ({100*successful/num_samples:.1f}%) | "
                  f"Rate: {rate:.1f} samples/s | ETA: {eta/60:.1f} min")
    
    total_time = time.time() - start_time
    
    print(f"\n{'='*60}")
    print(f"Generation complete!")
    print(f"  Total samples: {successful}/{num_samples}")
    print(f"  Total time: {total_time/60:.1f} minutes")
    print(f"  Average rate: {successful/total_time:.1f} samples/second")
    print(f"{'='*60}")
    
    return successful


def main():
    parser = argparse.ArgumentParser(
        description="Generate synthetic gamma spectra (v2 - parallel, balanced)"
    )
    
    parser.add_argument(
        "--num_samples", "-n",
        type=int,
        default=100000,
        help="Number of samples to generate (default: 100000)"
    )
    
    parser.add_argument(
        "--output_dir", "-o",
        type=str,
        default="O:/master_data_collection/isotopev2",
        help="Output directory (default: O:/master_data_collection/isotopev2)"
    )
    
    parser.add_argument(
        "--detector",
        type=str,
        default="radiacode_103",
        choices=list(RADIACODE_CONFIGS.keys()),
        help="Detector to simulate (default: radiacode_103)"
    )
    
    parser.add_argument(
        "--workers", "-w",
        type=int,
        default=None,
        help="Number of parallel workers (default: CPU count - 1)"
    )
    
    parser.add_argument(
        "--min_duration",
        type=float,
        default=60,
        help="Minimum duration in seconds (default: 60)"
    )
    
    parser.add_argument(
        "--max_duration",
        type=float,
        default=300,
        help="Maximum duration in seconds (default: 300)"
    )
    
    parser.add_argument(
        "--min_activity",
        type=float,
        default=1.0,
        help="Minimum activity in Bq (default: 1.0)"
    )
    
    parser.add_argument(
        "--max_activity",
        type=float,
        default=100.0,
        help="Maximum activity in Bq (default: 100.0)"
    )
    
    parser.add_argument(
        "--bg_min",
        type=float,
        default=0.3,
        help="Minimum background intensity multiplier (default: 0.3)"
    )
    
    parser.add_argument(
        "--bg_max",
        type=float,
        default=3.0,
        help="Maximum background intensity multiplier (default: 3.0)"
    )
    
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Random seed for reproducibility"
    )
    
    parser.add_argument(
        "--chunk_size",
        type=int,
        default=100,
        help="Samples per progress update (default: 100)"
    )
    
    # Sample type fractions
    parser.add_argument("--single_frac", type=float, default=0.40)
    parser.add_argument("--dual_frac", type=float, default=0.30)
    parser.add_argument("--multi_frac", type=float, default=0.20)
    parser.add_argument("--bg_frac", type=float, default=0.10)
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("Synthetic Gamma Spectra Generator v2")
    print("  - Parallel processing")
    print("  - Class-balanced sampling")
    print("  - Variable background")
    print("=" * 60)
    print(f"Samples: {args.num_samples:,}")
    print(f"Workers: {args.workers or (cpu_count() - 1)}")
    print(f"Output: {args.output_dir}")
    print(f"Detector: {args.detector}")
    print(f"Duration: {args.min_duration}-{args.max_duration}s")
    print(f"Activity: {args.min_activity}-{args.max_activity} Bq")
    print(f"Background: {args.bg_min}x-{args.bg_max}x")
    print("=" * 60)
    
    generate_training_batch_parallel(
        num_samples=args.num_samples,
        output_dir=Path(args.output_dir),
        detector_name=args.detector,
        duration_range=(args.min_duration, args.max_duration),
        activity_range=(args.min_activity, args.max_activity),
        single_isotope_fraction=args.single_frac,
        dual_isotope_fraction=args.dual_frac,
        multi_isotope_fraction=args.multi_frac,
        background_only_fraction=args.bg_frac,
        bg_intensity_range=(args.bg_min, args.bg_max),
        num_workers=args.workers,
        random_seed=args.seed,
        chunk_size=args.chunk_size,
    )


if __name__ == "__main__":
    main()
