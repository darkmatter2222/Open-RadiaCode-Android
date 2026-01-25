"""
Synthetic Spectra Generation Script

This script generates synthetic gamma spectra for training isotope identification models.

Usage:
    python generate_spectra.py --num_samples 10 --output_dir ./data/synthetic

Output:
    - data/synthetic/spectra/*.npy - Spectrum arrays (time x 1023 channels)
    - data/synthetic/spectra/*.png - Visual representations (optional)
    - data/synthetic/labels.json - Annotations for all samples
"""

import argparse
import sys
from pathlib import Path
import json
from datetime import datetime
import numpy as np

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from synthetic_spectra.generator import (
    SpectrumGenerator,
    SpectrumConfig,
    IsotopeSource,
    GeneratedSpectrum,
    save_spectrum,
    generate_labels_json,
)
from synthetic_spectra.config import RADIACODE_CONFIGS
from synthetic_spectra.ground_truth import (
    get_all_isotopes,
    get_isotopes_by_category,
    IsotopeCategory,
    DECAY_CHAINS,
)


def get_common_isotope_pool() -> list:
    """Get a pool of commonly encountered isotopes for realistic training data."""
    
    common_isotopes = [
        # Calibration sources (very common in spectra)
        "Cs-137", "Co-60", "Am-241", "Ba-133", "Eu-152", "Na-22", "Co-57",
        
        # Medical isotopes (occasionally encountered)
        "Tc-99m", "I-131", "I-123", "F-18", "Ga-67", "In-111", "Lu-177",
        
        # Natural background (always present to some degree)
        "K-40", "Pb-214", "Bi-214", "Pb-212", "Bi-212", "Tl-208", "Ac-228",
        
        # Industrial sources
        "Ir-192", "Se-75", "Mn-54", "Zn-65",
        
        # Uranium/Thorium (NORM)
        "U-235", "Ra-226", "Th-232",
        
        # Reactor/Fallout
        "Cs-134", "Sb-125", "Ce-144", "Co-58",
    ]
    
    # Filter to only isotopes in our database with gamma lines
    from synthetic_spectra.ground_truth import get_isotope
    valid_isotopes = []
    for name in common_isotopes:
        iso = get_isotope(name)
        if iso and len(iso.gamma_lines) > 0:
            valid_isotopes.append(name)
    
    return valid_isotopes


def generate_single_isotope_sample(
    generator: SpectrumGenerator,
    isotope_name: str,
    activity_bq: float,
    duration_seconds: float,
    **kwargs
) -> GeneratedSpectrum:
    """Generate a clean sample with a single isotope."""
    
    config = SpectrumConfig(
        duration_seconds=duration_seconds,
        sources=[
            IsotopeSource(
                isotope_name=isotope_name,
                activity_bq=activity_bq,
                include_daughters=True
            )
        ],
        **kwargs
    )
    
    return generator.generate_spectrum(config)


def generate_mixed_isotope_sample(
    generator: SpectrumGenerator,
    isotope_names: list,
    activities_bq: list,
    duration_seconds: float,
    **kwargs
) -> GeneratedSpectrum:
    """Generate a sample with multiple blended isotopes."""
    
    sources = [
        IsotopeSource(
            isotope_name=name,
            activity_bq=activity,
            include_daughters=True
        )
        for name, activity in zip(isotope_names, activities_bq)
    ]
    
    config = SpectrumConfig(
        duration_seconds=duration_seconds,
        sources=sources,
        **kwargs
    )
    
    return generator.generate_spectrum(config)


def generate_training_batch(
    num_samples: int,
    output_dir: Path,
    detector_name: str = "radiacode_103",
    duration_range: tuple = (60, 300),
    activity_range: tuple = (1.0, 100.0),
    single_isotope_fraction: float = 0.4,
    dual_isotope_fraction: float = 0.3,
    multi_isotope_fraction: float = 0.2,
    background_only_fraction: float = 0.1,
    save_png: bool = False,
    random_seed: int = None,
) -> list:
    """
    Generate a batch of training samples with various configurations.
    
    Args:
        num_samples: Total number of samples to generate
        output_dir: Output directory for spectra and labels
        detector_name: Radiacode device to simulate
        duration_range: (min, max) duration in seconds
        activity_range: (min, max) source activity in Bq
        single_isotope_fraction: Fraction of single-isotope samples
        dual_isotope_fraction: Fraction of two-isotope samples
        multi_isotope_fraction: Fraction of 3+ isotope samples
        background_only_fraction: Fraction of background-only samples
        save_png: Whether to also save PNG images
        random_seed: Random seed for reproducibility
    
    Returns:
        List of generated spectra
    """
    
    if random_seed is not None:
        np.random.seed(random_seed)
    
    # Create output directories
    output_dir = Path(output_dir)
    spectra_dir = output_dir / "spectra"
    spectra_dir.mkdir(parents=True, exist_ok=True)
    
    # Initialize generator
    generator = SpectrumGenerator(
        detector_config=RADIACODE_CONFIGS.get(detector_name),
        random_seed=random_seed
    )
    
    # Get isotope pool
    isotope_pool = get_common_isotope_pool()
    print(f"Using isotope pool with {len(isotope_pool)} isotopes")
    
    # Calculate sample counts for each category
    n_single = int(num_samples * single_isotope_fraction)
    n_dual = int(num_samples * dual_isotope_fraction)
    n_multi = int(num_samples * multi_isotope_fraction)
    n_background = int(num_samples * background_only_fraction)
    
    # Adjust to ensure we hit exactly num_samples
    remaining = num_samples - (n_single + n_dual + n_multi + n_background)
    n_single += remaining
    
    generated_spectra = []
    
    print(f"\nGenerating {num_samples} synthetic spectra:")
    print(f"  - Single isotope: {n_single}")
    print(f"  - Dual isotope: {n_dual}")
    print(f"  - Multi isotope (3+): {n_multi}")
    print(f"  - Background only: {n_background}")
    print()
    
    sample_num = 0
    
    # Generate single isotope samples
    print("Generating single-isotope samples...")
    for i in range(n_single):
        isotope = np.random.choice(isotope_pool)
        activity = np.random.uniform(*activity_range)
        duration = np.random.uniform(*duration_range)
        
        spectrum = generate_single_isotope_sample(
            generator,
            isotope,
            activity,
            duration,
            detector_name=detector_name,
            include_background=True,
        )
        
        # Save spectrum
        save_spectrum(
            spectrum,
            spectra_dir,
            save_image=True,
            image_format='both' if save_png else 'npy'
        )
        
        generated_spectra.append(spectrum)
        sample_num += 1
        
        if sample_num % 10 == 0:
            print(f"  Generated {sample_num}/{num_samples} samples...")
    
    # Generate dual isotope samples
    print("Generating dual-isotope samples...")
    for i in range(n_dual):
        isotopes = np.random.choice(isotope_pool, size=2, replace=False)
        activities = [np.random.uniform(*activity_range) for _ in range(2)]
        duration = np.random.uniform(*duration_range)
        
        spectrum = generate_mixed_isotope_sample(
            generator,
            list(isotopes),
            activities,
            duration,
            detector_name=detector_name,
            include_background=True,
        )
        
        save_spectrum(
            spectrum,
            spectra_dir,
            save_image=True,
            image_format='both' if save_png else 'npy'
        )
        
        generated_spectra.append(spectrum)
        sample_num += 1
        
        if sample_num % 10 == 0:
            print(f"  Generated {sample_num}/{num_samples} samples...")
    
    # Generate multi-isotope samples
    print("Generating multi-isotope samples...")
    for i in range(n_multi):
        num_isotopes = np.random.randint(3, min(6, len(isotope_pool)))
        isotopes = np.random.choice(isotope_pool, size=num_isotopes, replace=False)
        activities = [np.random.uniform(*activity_range) for _ in range(num_isotopes)]
        duration = np.random.uniform(*duration_range)
        
        spectrum = generate_mixed_isotope_sample(
            generator,
            list(isotopes),
            activities,
            duration,
            detector_name=detector_name,
            include_background=True,
        )
        
        save_spectrum(
            spectrum,
            spectra_dir,
            save_image=True,
            image_format='both' if save_png else 'npy'
        )
        
        generated_spectra.append(spectrum)
        sample_num += 1
        
        if sample_num % 10 == 0:
            print(f"  Generated {sample_num}/{num_samples} samples...")
    
    # Generate background-only samples
    print("Generating background-only samples...")
    for i in range(n_background):
        duration = np.random.uniform(*duration_range)
        
        config = SpectrumConfig(
            duration_seconds=duration,
            sources=[],  # No additional sources
            include_background=True,
            detector_name=detector_name,
        )
        
        spectrum = generator.generate_spectrum(config)
        
        save_spectrum(
            spectrum,
            spectra_dir,
            save_image=True,
            image_format='both' if save_png else 'npy'
        )
        
        generated_spectra.append(spectrum)
        sample_num += 1
    
    print(f"\nGenerated {len(generated_spectra)} samples total")
    
    # Individual JSON labels are saved per-sample by save_spectrum()
    # No combined labels.json needed for efficient large-scale training
    
    return generated_spectra


def main():
    parser = argparse.ArgumentParser(
        description="Generate synthetic gamma spectra for ML training"
    )
    
    parser.add_argument(
        "--num_samples",
        type=int,
        default=10,
        help="Number of samples to generate (default: 10)"
    )
    
    parser.add_argument(
        "--output_dir",
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
        "--min_duration",
        type=float,
        default=60,
        help="Minimum spectrum duration in seconds (default: 60)"
    )
    
    parser.add_argument(
        "--max_duration",
        type=float,
        default=300,
        help="Maximum spectrum duration in seconds (default: 300)"
    )
    
    parser.add_argument(
        "--min_activity",
        type=float,
        default=1.0,
        help="Minimum source activity in Bq (default: 1.0)"
    )
    
    parser.add_argument(
        "--max_activity",
        type=float,
        default=100.0,
        help="Maximum source activity in Bq (default: 100.0)"
    )
    
    parser.add_argument(
        "--save_png",
        action="store_true",
        help="Also save PNG images of spectra"
    )
    
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Random seed for reproducibility"
    )
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("Synthetic Gamma Spectra Generator")
    print("=" * 60)
    print(f"Samples to generate: {args.num_samples}")
    print(f"Output directory: {args.output_dir}")
    print(f"Detector: {args.detector}")
    print(f"Duration range: {args.min_duration}-{args.max_duration} seconds")
    print(f"Activity range: {args.min_activity}-{args.max_activity} Bq")
    print(f"Random seed: {args.seed}")
    print("=" * 60)
    
    spectra = generate_training_batch(
        num_samples=args.num_samples,
        output_dir=Path(args.output_dir),
        detector_name=args.detector,
        duration_range=(args.min_duration, args.max_duration),
        activity_range=(args.min_activity, args.max_activity),
        save_png=args.save_png,
        random_seed=args.seed,
    )
    
    print("\n" + "=" * 60)
    print("Generation complete!")
    print("=" * 60)
    
    # Print summary
    print("\nSample summary:")
    for i, spectrum in enumerate(spectra[:5]):
        isotopes = spectrum.isotopes_present if spectrum.isotopes_present else ["(background only)"]
        print(f"  {i+1}. {spectrum.sample_id}: {', '.join(isotopes)}")
    if len(spectra) > 5:
        print(f"  ... and {len(spectra) - 5} more samples")


if __name__ == "__main__":
    main()
