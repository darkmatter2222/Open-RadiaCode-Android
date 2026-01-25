"""
Spectrum Physics Module

Implements the physics of gamma spectrum generation including:
- Peak shape modeling (Gaussian with detector response)
- Background continuum generation
- Counting statistics (Poisson sampling)
- Detector efficiency modeling
"""

import numpy as np
from scipy import special
from typing import Optional, Tuple, List
from dataclasses import dataclass

from ..config import DetectorConfig, get_default_config


@dataclass
class PeakParameters:
    """Parameters for a single gamma peak."""
    energy_kev: float
    intensity: float  # Emission probability (photons/decay)
    activity_bq: float  # Source activity in Becquerels
    live_time_s: float  # Acquisition time in seconds


def gaussian_peak(
    energy_bins: np.ndarray,
    peak_energy: float,
    sigma: float,
    amplitude: float
) -> np.ndarray:
    """
    Generate a Gaussian peak.
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        peak_energy: Center energy of peak (keV)
        sigma: Standard deviation (keV)
        amplitude: Peak area (total counts)
    
    Returns:
        Array of counts in each bin
    """
    # Gaussian probability density
    prob = np.exp(-0.5 * ((energy_bins - peak_energy) / sigma) ** 2)
    prob /= (sigma * np.sqrt(2 * np.pi))
    
    # Scale by amplitude and bin width
    bin_width = energy_bins[1] - energy_bins[0] if len(energy_bins) > 1 else 1.0
    return amplitude * prob * bin_width


def calculate_fwhm(energy_kev: float, fwhm_at_662: float = 0.084) -> float:
    """
    Calculate FWHM at a given energy for scintillator detectors.
    
    FWHM scales as sqrt(E) for scintillators due to statistical fluctuations
    in light collection.
    
    FWHM(E) = FWHM_662 * sqrt(E/662) * 662 / E * E = FWHM_662 * sqrt(662/E) * E
    Actually: FWHM(E) / E = FWHM_662 / 662 * sqrt(662/E)
    So: FWHM(E) = E * FWHM_662 / 662 * sqrt(662/E) = FWHM_662 * sqrt(662 * E) / 662
                = FWHM_662 * sqrt(E / 662)
    
    Wait, let me recalculate:
    For scintillators, the relative resolution (FWHM/E) scales as 1/sqrt(E)
    FWHM(E)/E = (FWHM_662/662) * sqrt(662/E)
    FWHM(E) = FWHM_662 * sqrt(662 * E) / 662 = FWHM_662 * sqrt(E/662)
    
    At 662 keV: FWHM = FWHM_662 * sqrt(1) = FWHM_662 ✓
    At lower E: larger relative FWHM (worse resolution)
    At higher E: smaller relative FWHM (better resolution)
    
    Args:
        energy_kev: Energy in keV
        fwhm_at_662: FWHM at 662 keV as fraction (e.g., 0.084 for 8.4%)
    
    Returns:
        FWHM in keV at the given energy
    """
    # FWHM_662 is given as fraction, so at 662 keV, FWHM = 0.084 * 662 = ~55.6 keV
    fwhm_662_kev = fwhm_at_662 * 662.0
    # Scale by sqrt(E/662)
    fwhm_kev = fwhm_662_kev * np.sqrt(energy_kev / 662.0)
    return fwhm_kev


def fwhm_to_sigma(fwhm: float) -> float:
    """Convert FWHM to Gaussian sigma."""
    return fwhm / (2.0 * np.sqrt(2.0 * np.log(2.0)))  # ≈ FWHM / 2.355


def detector_efficiency(
    energy_kev: float,
    detector_config: Optional[DetectorConfig] = None
) -> float:
    """
    Calculate detector full-energy peak efficiency.
    
    For CsI and GAGG scintillators, efficiency varies with energy.
    This is a simplified model - real efficiency curves should be
    measured for each detector.
    
    Args:
        energy_kev: Gamma energy in keV
        detector_config: Detector configuration
    
    Returns:
        Efficiency as fraction (0-1)
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    # Simplified efficiency model for ~1 cm³ scintillator
    # Low energy: efficiency increases (more stopping power)
    # High energy: efficiency decreases (photons pass through)
    # Peak around 100-300 keV for small scintillators
    
    # This is a phenomenological model
    # Real efficiency should be calibrated
    
    if energy_kev < 20:
        return 0.0
    
    # Simple model: efficiency peaks around 100-200 keV
    # Falls off at low energy (absorption in housing)
    # Falls off at high energy (less stopping power)
    
    # Low energy cutoff (absorption)
    low_eff = 1.0 - np.exp(-energy_kev / 50.0)
    
    # High energy falloff (escape)
    # For 1 cm³ CsI, efficiency drops significantly above ~500 keV
    high_eff = np.exp(-energy_kev / 2000.0)
    
    # Combine effects
    eff = 0.8 * low_eff * high_eff
    
    # Scale by detector volume
    volume_factor = (detector_config.detector_volume_cm3 / 1.0) ** (1/3)
    eff *= min(1.0, volume_factor)
    
    return max(0.0, min(1.0, eff))


def calculate_expected_counts(
    peak_params: PeakParameters,
    detector_config: Optional[DetectorConfig] = None
) -> float:
    """
    Calculate expected counts in a photopeak.
    
    λ = A * t * I * ε * T
    
    Where:
        A = activity (decays/s)
        t = live time (s)
        I = emission probability (photons/decay)
        ε = detector efficiency
        T = transmission factor (assumed 1 for now)
    
    Args:
        peak_params: Peak parameters
        detector_config: Detector configuration
    
    Returns:
        Expected number of counts in the photopeak
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    efficiency = detector_efficiency(peak_params.energy_kev, detector_config)
    
    expected = (
        peak_params.activity_bq *
        peak_params.live_time_s *
        peak_params.intensity *
        efficiency
    )
    
    return expected


def generate_peak_spectrum(
    energy_bins: np.ndarray,
    peak_params: PeakParameters,
    detector_config: Optional[DetectorConfig] = None
) -> np.ndarray:
    """
    Generate a single gamma peak with detector response.
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        peak_params: Peak parameters
        detector_config: Detector configuration
    
    Returns:
        Array of expected counts in each bin (not yet Poisson sampled)
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    # Calculate expected counts
    amplitude = calculate_expected_counts(peak_params, detector_config)
    
    if amplitude <= 0:
        return np.zeros_like(energy_bins)
    
    # Calculate peak width
    fwhm_kev = calculate_fwhm(peak_params.energy_kev, detector_config.fwhm_at_662)
    sigma = fwhm_to_sigma(fwhm_kev)
    
    # Generate Gaussian peak
    peak = gaussian_peak(energy_bins, peak_params.energy_kev, sigma, amplitude)
    
    return peak


def generate_compton_continuum(
    energy_bins: np.ndarray,
    peak_energy: float,
    peak_counts: float,
    compton_to_peak_ratio: float = 0.5
) -> np.ndarray:
    """
    Generate simplified Compton continuum for a gamma line.
    
    The Compton continuum extends from 0 to the Compton edge.
    Compton edge energy = E * (1 - 1/(1 + 2*E/(511)))
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        peak_energy: Energy of the gamma line (keV)
        peak_counts: Total counts in the photopeak
        compton_to_peak_ratio: Ratio of Compton counts to peak counts
    
    Returns:
        Array of Compton continuum counts
    """
    # Compton edge energy
    alpha = peak_energy / 511.0  # E / m_e c²
    compton_edge = peak_energy * (2 * alpha) / (1 + 2 * alpha)
    
    # Create continuum (simplified flat + edge shape)
    continuum = np.zeros_like(energy_bins)
    
    # Mask for energies below Compton edge
    mask = energy_bins < compton_edge
    
    if np.any(mask):
        # Simple model: roughly flat with enhancement near edge
        base_level = peak_counts * compton_to_peak_ratio / np.sum(mask)
        continuum[mask] = base_level
        
        # Add edge enhancement (Klein-Nishina-like shape)
        edge_region = (energy_bins > 0.8 * compton_edge) & (energy_bins < compton_edge)
        if np.any(edge_region):
            enhancement = 1.5 * np.exp(-((energy_bins[edge_region] - compton_edge) / (0.05 * compton_edge)) ** 2)
            continuum[edge_region] *= (1 + enhancement)
    
    return continuum


# =============================================================================
# BACKGROUND GENERATION
# =============================================================================

def generate_exponential_background(
    energy_bins: np.ndarray,
    amplitude: float = 100.0,
    decay_constant: float = 0.003
) -> np.ndarray:
    """
    Generate exponential background continuum.
    
    B(E) = A * exp(-b * E)
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        amplitude: Background amplitude at E=0
        decay_constant: Exponential decay constant (1/keV)
    
    Returns:
        Array of background counts
    """
    return amplitude * np.exp(-decay_constant * energy_bins)


def generate_polynomial_background(
    energy_bins: np.ndarray,
    coefficients: List[float] = None
) -> np.ndarray:
    """
    Generate polynomial background.
    
    B(E) = Σ c_m * E^m
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        coefficients: Polynomial coefficients [c0, c1, c2, ...]
    
    Returns:
        Array of background counts
    """
    if coefficients is None:
        coefficients = [10.0, -0.005, 1e-6]  # Default quadratic
    
    background = np.zeros_like(energy_bins)
    for m, c in enumerate(coefficients):
        background += c * (energy_bins ** m)
    
    return np.maximum(0, background)


def generate_environmental_background(
    energy_bins: np.ndarray,
    duration_seconds: float,
    background_cps: float = 5.0,
    include_k40: bool = True,
    include_radon: bool = True,
    include_thorium: bool = True,
    detector_config: Optional[DetectorConfig] = None
) -> Tuple[np.ndarray, List[str]]:
    """
    Generate realistic environmental background spectrum.
    
    Includes:
    - Exponential continuum (cosmic rays, scattered gammas)
    - K-40 peak (1460 keV) - ubiquitous in environment
    - Radon daughters (Pb-214, Bi-214) - indoor air
    - Thorium daughters (Pb-212, Tl-208) - building materials
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        duration_seconds: Acquisition time
        background_cps: Average background count rate (cps)
        include_k40: Include potassium-40 peak
        include_radon: Include radon daughter peaks
        include_thorium: Include thorium daughter peaks
        detector_config: Detector configuration
    
    Returns:
        Tuple of (background_spectrum, list_of_background_isotopes)
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    background_isotopes = []
    
    # Start with exponential continuum
    total_continuum_counts = background_cps * duration_seconds * 0.7
    background = generate_exponential_background(
        energy_bins,
        amplitude=total_continuum_counts / 500,
        decay_constant=0.002
    )
    
    # Normalize continuum to target count rate
    if background.sum() > 0:
        background *= (total_continuum_counts / background.sum())
    
    # Add K-40 peak (very common)
    if include_k40:
        k40_activity = np.random.uniform(0.5, 5.0)  # Bq
        peak = generate_peak_spectrum(
            energy_bins,
            PeakParameters(
                energy_kev=1460.83,
                intensity=0.1066,
                activity_bq=k40_activity,
                live_time_s=duration_seconds
            ),
            detector_config
        )
        background += peak
        background_isotopes.append("K-40")
    
    # Add radon daughters
    if include_radon:
        radon_activity = np.random.uniform(0.1, 2.0)  # Bq
        
        # Pb-214 lines
        for energy, intensity in [(295.22, 0.1842), (351.93, 0.356)]:
            peak = generate_peak_spectrum(
                energy_bins,
                PeakParameters(
                    energy_kev=energy,
                    intensity=intensity,
                    activity_bq=radon_activity,
                    live_time_s=duration_seconds
                ),
                detector_config
            )
            background += peak
        
        # Bi-214 lines
        for energy, intensity in [(609.31, 0.4549), (1120.29, 0.1492), (1764.49, 0.1531)]:
            peak = generate_peak_spectrum(
                energy_bins,
                PeakParameters(
                    energy_kev=energy,
                    intensity=intensity,
                    activity_bq=radon_activity,
                    live_time_s=duration_seconds
                ),
                detector_config
            )
            background += peak
        
        background_isotopes.extend(["Pb-214", "Bi-214"])
    
    # Add thorium daughters
    if include_thorium:
        thorium_activity = np.random.uniform(0.05, 1.0)  # Bq
        
        # Ac-228 line
        peak = generate_peak_spectrum(
            energy_bins,
            PeakParameters(
                energy_kev=911.20,
                intensity=0.258,
                activity_bq=thorium_activity,
                live_time_s=duration_seconds
            ),
            detector_config
        )
        background += peak
        
        # Pb-212 line
        peak = generate_peak_spectrum(
            energy_bins,
            PeakParameters(
                energy_kev=238.63,
                intensity=0.436,
                activity_bq=thorium_activity,
                live_time_s=duration_seconds
            ),
            detector_config
        )
        background += peak
        
        # Tl-208 lines
        for energy, intensity in [(583.19, 0.845 * 0.36), (2614.51, 0.998 * 0.36)]:
            # Branching ratio of 36% for Tl-208 path
            peak = generate_peak_spectrum(
                energy_bins,
                PeakParameters(
                    energy_kev=energy,
                    intensity=intensity,
                    activity_bq=thorium_activity,
                    live_time_s=duration_seconds
                ),
                detector_config
            )
            background += peak
        
        background_isotopes.extend(["Ac-228", "Pb-212", "Tl-208"])
    
    return background, background_isotopes


def apply_poisson_noise(spectrum: np.ndarray) -> np.ndarray:
    """
    Apply Poisson counting statistics to a spectrum.
    
    Each bin is sampled from a Poisson distribution with
    lambda = expected counts in that bin.
    
    Args:
        spectrum: Array of expected counts (can be float)
    
    Returns:
        Array of actual counts (integers)
    """
    # Handle negative values (shouldn't happen but be safe)
    spectrum = np.maximum(0, spectrum)
    
    # Sample from Poisson distribution
    return np.random.poisson(spectrum).astype(np.float64)


def apply_electronic_noise(
    spectrum: np.ndarray,
    sigma: float = 0.5
) -> np.ndarray:
    """
    Apply small Gaussian electronic noise.
    
    Args:
        spectrum: Count spectrum
        sigma: Standard deviation of electronic noise (counts)
    
    Returns:
        Spectrum with added electronic noise
    """
    noise = np.random.normal(0, sigma, spectrum.shape)
    result = spectrum + noise
    return np.maximum(0, result)


# =============================================================================
# NORMALIZATION
# =============================================================================

def normalize_spectrum(
    spectrum: np.ndarray,
    method: str = "max"
) -> np.ndarray:
    """
    Normalize a spectrum for ML training.
    
    Args:
        spectrum: Raw count spectrum
        method: Normalization method
            - "max": Divide by maximum value (range 0-1)
            - "sum": Divide by total counts (probability distribution)
            - "log": Log transform then max normalize
            - "sqrt": Square root transform then max normalize
    
    Returns:
        Normalized spectrum
    """
    if method == "max":
        max_val = spectrum.max()
        if max_val > 0:
            return spectrum / max_val
        return spectrum
    
    elif method == "sum":
        total = spectrum.sum()
        if total > 0:
            return spectrum / total
        return spectrum
    
    elif method == "log":
        # Log transform (add 1 to handle zeros)
        log_spec = np.log1p(spectrum)
        max_val = log_spec.max()
        if max_val > 0:
            return log_spec / max_val
        return log_spec
    
    elif method == "sqrt":
        sqrt_spec = np.sqrt(spectrum)
        max_val = sqrt_spec.max()
        if max_val > 0:
            return sqrt_spec / max_val
        return sqrt_spec
    
    else:
        raise ValueError(f"Unknown normalization method: {method}")
