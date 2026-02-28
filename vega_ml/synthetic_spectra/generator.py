"""
Synthetic Spectrum Generator

Main class for generating synthetic gamma spectra images
with various isotope combinations and configurations.
"""

import numpy as np
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple, Any
import json
from pathlib import Path
from datetime import datetime
import hashlib

from .config import DetectorConfig, get_default_config, RADIACODE_CONFIGS
from .ground_truth import (
    ISOTOPE_DATABASE,
    Isotope,
    get_isotope,
    get_all_isotopes,
    DECAY_CHAINS,
    get_chain_daughters,
    get_full_descendants,
    infer_parent_from_daughters,
)
from .physics import (
    PeakParameters,
    generate_peak_spectrum,
    generate_peak_with_compton,
    generate_environmental_background,
    apply_poisson_noise,
    apply_electronic_noise,
    normalize_spectrum,
)


@dataclass
class IsotopeSource:
    """Definition of an isotope source for spectrum generation."""
    isotope_name: str
    activity_bq: float
    
    # Optional: if part of a decay chain, include daughters
    include_daughters: bool = True
    
    # Activity can vary by this factor for augmentation
    activity_variation: float = 0.0


@dataclass
class SpectrumConfig:
    """Configuration for a single spectrum generation."""
    
    # Time parameters
    duration_seconds: float = 60.0
    time_interval_seconds: float = 1.0  # Each row in the spectrogram
    
    # Sources to include
    sources: List[IsotopeSource] = field(default_factory=list)
    
    # Background options
    include_background: bool = True
    background_cps: float = 5.0
    include_k40: bool = True
    include_radon: bool = True
    include_thorium: bool = True
    
    # Detector configuration
    detector_name: str = "radiacode_103"
    
    # Noise options
    apply_poisson: bool = True
    apply_electronic: bool = False
    electronic_noise_sigma: float = 0.5
    
    # Normalization
    normalize: bool = True
    normalization_method: str = "max"  # max, sum, log, sqrt
    
    # === Domain randomization for better generalization ===
    # Include Compton continuum (critical for realism)
    include_compton: bool = True
    compton_ratio: float = 0.6  # Compton to peak ratio (0.3-1.0 typical)
    
    # FWHM variation (detector-to-detector variance)
    fwhm_variation: float = 0.1  # +/- 10% FWHM variation
    
    # Efficiency curve variation
    efficiency_variation: float = 0.15  # +/- 15% efficiency variation
    
    # Energy calibration jitter (keV)
    calibration_offset_kev: float = 0.0  # Will be randomized if cal_jitter > 0
    calibration_jitter_kev: float = 5.0  # Random offset up to +/- this value
    
    # Gain variation (multiplicative energy scale)
    gain_variation: float = 0.02  # +/- 2% gain variation


@dataclass
class GeneratedSpectrum:
    """Result of spectrum generation."""
    
    # The spectrum data (2D array: time x channels)
    data: np.ndarray
    
    # Metadata
    config: SpectrumConfig
    isotopes_present: List[str]
    background_isotopes: List[str]
    
    # For labels/annotations
    labels: Dict[str, Any] = field(default_factory=dict)
    
    # Unique identifier
    sample_id: str = ""
    
    # Generation timestamp
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())


class SpectrumGenerator:
    """
    Main class for generating synthetic gamma spectra.
    
    Creates 2D spectrogram images where:
    - X-axis: Energy channels (1023 channels, 20-3000 keV)
    - Y-axis: Time intervals (variable duration)
    - Pixel intensity: Normalized count rate
    """
    
    def __init__(
        self,
        detector_config: Optional[DetectorConfig] = None,
        random_seed: Optional[int] = None
    ):
        """
        Initialize the spectrum generator.
        
        Args:
            detector_config: Detector configuration (default: Radiacode 103)
            random_seed: Random seed for reproducibility
        """
        if detector_config is None:
            detector_config = get_default_config()
        
        self.detector_config = detector_config
        self.energy_bins = detector_config.get_energy_bins()
        self.num_channels = len(self.energy_bins)
        
        if random_seed is not None:
            np.random.seed(random_seed)
    
    def generate_single_interval(
        self,
        sources: List[IsotopeSource],
        interval_duration: float,
        include_background: bool = True,
        background_config: Optional[Dict] = None
    ) -> Tuple[np.ndarray, List[str], List[str]]:
        """
        Generate a single time interval spectrum.
        
        Args:
            sources: List of isotope sources
            interval_duration: Duration in seconds
            include_background: Whether to include environmental background
            background_config: Background configuration options
        
        Returns:
            Tuple of (spectrum, source_isotopes, background_isotopes)
        """
        spectrum = np.zeros(self.num_channels)
        source_isotopes = []
        background_isotopes = []
        
        # Get domain randomization settings from background_config
        include_compton = background_config.get('include_compton', True)
        compton_ratio = background_config.get('compton_ratio', 0.6)
        fwhm_variation = background_config.get('fwhm_variation', 0.0)
        efficiency_variation = background_config.get('efficiency_variation', 0.0)
        calibration_offset = background_config.get('calibration_offset_kev', 0.0)
        gain_factor = background_config.get('gain_factor', 1.0)
        
        # Create a modified detector config if we have variations
        effective_detector = self.detector_config
        if fwhm_variation != 0 or efficiency_variation != 0:
            # Copy and modify
            from copy import deepcopy
            effective_detector = deepcopy(self.detector_config)
            if fwhm_variation != 0:
                fwhm_mult = 1.0 + np.random.uniform(-fwhm_variation, fwhm_variation)
                effective_detector.fwhm_at_662 *= fwhm_mult
        
        # Apply calibration offset to energy bins for this interval
        effective_energy_bins = self.energy_bins * gain_factor + calibration_offset
        
        # Add background
        if include_background:
            if background_config is None:
                background_config = {}
            
            bg_spectrum, bg_isotopes = generate_environmental_background(
                effective_energy_bins,
                interval_duration,
                background_cps=background_config.get('background_cps', 5.0),
                include_k40=background_config.get('include_k40', True),
                include_radon=background_config.get('include_radon', True),
                include_thorium=background_config.get('include_thorium', True),
                detector_config=effective_detector
            )
            spectrum += bg_spectrum
            background_isotopes = bg_isotopes
        
        # Add source isotopes
        for source in sources:
            isotope = get_isotope(source.isotope_name)
            if isotope is None:
                print(f"Warning: Unknown isotope {source.isotope_name}")
                continue
            
            # Apply activity variation if specified
            activity = source.activity_bq
            if source.activity_variation > 0:
                variation = 1 + np.random.uniform(
                    -source.activity_variation,
                    source.activity_variation
                )
                activity *= variation
            
            # Get all isotopes to include (source + full decay chain if requested)
            isotopes_to_add = [source.isotope_name]
            if source.include_daughters:
                # Use full descendants, not just immediate daughters
                descendants = get_full_descendants(source.isotope_name, include_self=False)
                isotopes_to_add.extend(descendants)
            
            # Add gamma lines from all isotopes in the chain
            for iso_name in isotopes_to_add:
                iso = get_isotope(iso_name)
                if iso is None:
                    continue
                
                for gamma_line in iso.gamma_lines:
                    # Apply gain/offset to peak energy
                    effective_energy = gamma_line.energy_kev * gain_factor + calibration_offset
                    
                    peak_params = PeakParameters(
                        energy_kev=effective_energy,
                        intensity=gamma_line.intensity,
                        activity_bq=activity,  # Secular equilibrium assumed
                        live_time_s=interval_duration
                    )
                    
                    # Use Compton-inclusive generation
                    if include_compton:
                        peak = generate_peak_with_compton(
                            effective_energy_bins,
                            peak_params,
                            effective_detector,
                            include_compton=True,
                            compton_ratio=compton_ratio
                        )
                    else:
                        peak = generate_peak_spectrum(
                            effective_energy_bins,
                            peak_params,
                            effective_detector
                        )
                    spectrum += peak
                
                if iso_name not in source_isotopes:
                    source_isotopes.append(iso_name)
        
        return spectrum, list(set(source_isotopes)), background_isotopes
    
    def generate_spectrum(
        self,
        config: SpectrumConfig
    ) -> GeneratedSpectrum:
        """
        Generate a full 2D spectrogram.
        
        Args:
            config: Spectrum configuration
        
        Returns:
            GeneratedSpectrum object with 2D data and metadata
        """
        # Calculate dimensions
        num_intervals = int(config.duration_seconds / config.time_interval_seconds)
        if num_intervals < 1:
            num_intervals = 1
        
        # Set detector config
        if config.detector_name in RADIACODE_CONFIGS:
            self.detector_config = RADIACODE_CONFIGS[config.detector_name]
            self.energy_bins = self.detector_config.get_energy_bins()
            self.num_channels = len(self.energy_bins)
        
        # Initialize 2D array (time x channels)
        spectrogram = np.zeros((num_intervals, self.num_channels))
        
        all_source_isotopes = []
        all_background_isotopes = []
        
        # Apply domain randomization: compute per-spectrum random variations
        # These are fixed for the whole spectrum (not per-interval)
        calibration_offset = config.calibration_offset_kev
        if config.calibration_jitter_kev > 0:
            calibration_offset += np.random.uniform(
                -config.calibration_jitter_kev, 
                config.calibration_jitter_kev
            )
        
        gain_factor = 1.0
        if config.gain_variation > 0:
            gain_factor = 1.0 + np.random.uniform(
                -config.gain_variation,
                config.gain_variation
            )
        
        # Generate each time interval with domain randomization settings
        background_config = {
            'background_cps': config.background_cps,
            'include_k40': config.include_k40,
            'include_radon': config.include_radon,
            'include_thorium': config.include_thorium,
            # Domain randomization
            'include_compton': config.include_compton,
            'compton_ratio': config.compton_ratio,
            'fwhm_variation': config.fwhm_variation,
            'efficiency_variation': config.efficiency_variation,
            'calibration_offset_kev': calibration_offset,
            'gain_factor': gain_factor,
        }
        
        for i in range(num_intervals):
            spectrum, src_iso, bg_iso = self.generate_single_interval(
                config.sources,
                config.time_interval_seconds,
                config.include_background,
                background_config
            )
            
            # Apply noise
            if config.apply_poisson:
                spectrum = apply_poisson_noise(spectrum)
            
            if config.apply_electronic:
                spectrum = apply_electronic_noise(
                    spectrum,
                    config.electronic_noise_sigma
                )
            
            spectrogram[i, :] = spectrum
            all_source_isotopes.extend(src_iso)
            all_background_isotopes.extend(bg_iso)
        
        # Normalize if requested
        if config.normalize:
            spectrogram = normalize_spectrum(spectrogram, config.normalization_method)
        
        # Generate unique sample ID
        sample_id = self._generate_sample_id(config)
        
        # Determine isotopes present
        isotopes_present = list(set(all_source_isotopes))
        background_isotopes = list(set(all_background_isotopes))
        
        # Create labels
        labels = {
            'isotopes': isotopes_present,
            'background_isotopes': background_isotopes,
            'source_activities_bq': {
                s.isotope_name: s.activity_bq for s in config.sources
            },
            'duration_seconds': config.duration_seconds,
            'time_interval_seconds': config.time_interval_seconds,
            'num_intervals': num_intervals,
            'detector': config.detector_name,
            'normalized': config.normalize,
            'normalization_method': config.normalization_method if config.normalize else None,
        }
        
        return GeneratedSpectrum(
            data=spectrogram,
            config=config,
            isotopes_present=isotopes_present,
            background_isotopes=background_isotopes,
            labels=labels,
            sample_id=sample_id
        )
    
    def _generate_sample_id(self, config: SpectrumConfig) -> str:
        """Generate a unique sample ID from config."""
        # Create a hash from config parameters
        hash_input = f"{datetime.now().timestamp()}"
        hash_input += f"_{config.duration_seconds}"
        hash_input += f"_{','.join(s.isotope_name for s in config.sources)}"
        hash_input += f"_{np.random.randint(0, 1000000)}"
        
        return hashlib.md5(hash_input.encode()).hexdigest()[:12]
    
    def generate_random_spectrum(
        self,
        duration_range: Tuple[float, float] = (60, 300),
        num_isotopes_range: Tuple[int, int] = (1, 3),
        activity_range: Tuple[float, float] = (1.0, 100.0),
        isotope_pool: Optional[List[str]] = None,
        **kwargs
    ) -> GeneratedSpectrum:
        """
        Generate a spectrum with random parameters.
        
        Args:
            duration_range: (min, max) duration in seconds
            num_isotopes_range: (min, max) number of isotopes to include
            activity_range: (min, max) activity in Bq
            isotope_pool: List of isotope names to choose from (default: all with gammas)
            **kwargs: Additional arguments passed to SpectrumConfig
        
        Returns:
            GeneratedSpectrum with random configuration
        """
        # Choose duration
        duration = np.random.uniform(*duration_range)
        
        # Choose number of isotopes
        num_isotopes = np.random.randint(num_isotopes_range[0], num_isotopes_range[1] + 1)
        
        # Build isotope pool if not provided
        if isotope_pool is None:
            isotope_pool = [
                iso.name for iso in get_all_isotopes()
                if len(iso.gamma_lines) > 0 and
                any(line.intensity > 0.01 for line in iso.gamma_lines)
            ]
        
        # Select random isotopes
        selected = np.random.choice(isotope_pool, size=min(num_isotopes, len(isotope_pool)), replace=False)
        
        # Create sources with random activities
        sources = []
        for isotope_name in selected:
            activity = np.random.uniform(*activity_range)
            sources.append(IsotopeSource(
                isotope_name=isotope_name,
                activity_bq=activity,
                include_daughters=np.random.random() > 0.3
            ))
        
        # Create config
        config = SpectrumConfig(
            duration_seconds=duration,
            sources=sources,
            **kwargs
        )
        
        return self.generate_spectrum(config)


def save_spectrum(
    spectrum: GeneratedSpectrum,
    output_dir: Path,
    save_image: bool = True,
    image_format: str = 'npy',
    save_individual_label: bool = True
) -> Dict[str, str]:
    """
    Save a generated spectrum to disk.
    
    Args:
        spectrum: GeneratedSpectrum to save
        output_dir: Output directory path
        save_image: Whether to save the spectrum data as an image/array
        image_format: Format for spectrum data ('npy', 'png', 'both')
        save_individual_label: Whether to save individual JSON label file per sample
    
    Returns:
        Dict of saved file paths
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    saved_files = {}
    base_name = f"spectrum_{spectrum.sample_id}"
    
    # Save spectrum data
    if save_image:
        if image_format in ('npy', 'both'):
            npy_path = output_dir / f"{base_name}.npy"
            np.save(npy_path, spectrum.data)
            saved_files['npy'] = str(npy_path)
        
        if image_format in ('png', 'both'):
            try:
                from PIL import Image
                
                # Convert to 8-bit grayscale image
                data_normalized = spectrum.data
                if data_normalized.max() > 0:
                    data_normalized = data_normalized / data_normalized.max()
                
                img_data = (data_normalized * 255).astype(np.uint8)
                img = Image.fromarray(img_data, mode='L')
                
                png_path = output_dir / f"{base_name}.png"
                img.save(png_path)
                saved_files['png'] = str(png_path)
            except ImportError:
                print("Warning: PIL not installed, skipping PNG save")
    
    # Save individual label JSON file (for efficient loading)
    if save_individual_label:
        json_path = output_dir / f"{base_name}.json"
        with open(json_path, 'w') as f:
            json.dump(spectrum.labels, f, indent=2)
        saved_files['json'] = str(json_path)
    
    saved_files['sample_id'] = spectrum.sample_id
    
    return saved_files


def generate_labels_json(
    spectra: List[GeneratedSpectrum],
    output_path: Path
) -> None:
    """
    Generate a combined JSON file with labels for all spectra.
    
    Note: This is for backward compatibility. For large datasets,
    individual JSON files per sample are more efficient.
    
    Args:
        spectra: List of generated spectra
        output_path: Path to save labels JSON
    """
    labels = {
        'metadata': {
            'generated_at': datetime.now().isoformat(),
            'num_samples': len(spectra),
            'channels': 1023,
            'energy_range_kev': [20, 3000],
        },
        'samples': {}
    }
    
    for spectrum in spectra:
        labels['samples'][spectrum.sample_id] = spectrum.labels
    
    with open(output_path, 'w') as f:
        json.dump(labels, f, indent=2)
