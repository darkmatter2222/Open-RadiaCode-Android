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
    Calculate detector full-energy peak efficiency for small CsI scintillators.
    
    This models the PHOTOPEAK (full-energy deposition) efficiency, NOT the
    total detection efficiency. For small (~1 cm³) CsI crystals:
    
    - Low energy (<100 keV): Efficiency limited by housing absorption and
      photoelectric effect dominates, efficiency ~50-80%
    - Medium energy (100-300 keV): Peak efficiency ~40-60%  
    - High energy (>400 keV): Compton scatter dominates, most gammas escape
      after partial energy deposition. Photopeak efficiency drops dramatically.
    
    The key insight is that a 609 keV gamma has a very small probability
    of depositing ALL its energy in a 1 cm³ crystal - most Compton scatter
    and escape, contributing to the Compton continuum instead.
    
    Args:
        energy_kev: Gamma energy in keV
        detector_config: Detector configuration
    
    Returns:
        Photopeak efficiency as fraction (0-1)
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    if energy_kev < 20:
        return 0.0
    
    # Volume-dependent characteristic length
    # For ~1 cm³, char_length ~ 1 cm
    vol = detector_config.detector_volume_cm3
    char_length = vol ** (1/3)
    
    # CsI mass attenuation coefficients (approximate, from NIST XCOM)
    # At different energies (cm²/g), CsI density = 4.51 g/cm³
    # 50 keV: ~6.0 cm²/g, 100 keV: ~1.5 cm²/g, 200 keV: ~0.4 cm²/g
    # 300 keV: ~0.22 cm²/g, 500 keV: ~0.12 cm²/g, 1000 keV: ~0.07 cm²/g
    
    # Photoelectric fraction (probability of full absorption vs Compton)
    # Drops very rapidly with energy in the 100-500 keV range
    if energy_kev < 100:
        # Photoelectric dominates below 100 keV
        pe_fraction = 0.85 - 0.3 * (energy_kev - 50) / 50
    elif energy_kev < 300:
        # Transition region - Compton becomes significant
        pe_fraction = 0.55 * np.exp(-((energy_kev - 100) / 150))
    else:
        # High energy - Compton dominates, photopeak very weak
        # At 609 keV, only ~3-5% of detected events are full energy
        pe_fraction = 0.15 * np.exp(-((energy_kev - 300) / 400))
    
    # Geometric detection probability (solid angle / attenuation)
    # Low energy cutoff (housing absorption ~0.5mm Al)
    low_cutoff = 1.0 - np.exp(-energy_kev / 40.0)
    
    # Total interaction probability (will gamma interact at all?)
    # Linear attenuation coeff * path length
    # Higher energy = longer path, lower interaction prob
    mu_rho = 0.5 * np.exp(-energy_kev / 200.0) + 0.08  # cm²/g (simplified)
    density = 4.51  # CsI g/cm³
    mu = mu_rho * density  # Linear attenuation (1/cm)
    interaction_prob = 1.0 - np.exp(-mu * char_length)
    
    # Photopeak efficiency = interact AND deposit full energy
    eff = interaction_prob * pe_fraction * low_cutoff
    
    # Empirical scaling to match real detector behavior
    # RadiaCode 110 with ~1 cm³ crystal
    eff *= 0.5  # Overall geometric/collection efficiency factor
    
    return max(0.0, min(1.0, eff))


def total_interaction_efficiency(
    energy_kev: float,
    detector_config: Optional[DetectorConfig] = None
) -> float:
    """
    Calculate total interaction efficiency (any energy deposition).
    
    This is the probability that a gamma interacts AT ALL in the crystal,
    regardless of whether it deposits full energy or partial energy.
    This is higher than photopeak efficiency, especially at high energies.
    
    Used to calculate Compton scatter contributions.
    
    Args:
        energy_kev: Gamma energy in keV
        detector_config: Detector configuration
    
    Returns:
        Total interaction efficiency as fraction (0-1)
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    if energy_kev < 20:
        return 0.0
    
    vol = detector_config.detector_volume_cm3
    char_length = vol ** (1/3)
    
    # Approximate total linear attenuation coefficient for CsI
    # Includes both photoelectric and Compton cross-sections
    # CsI density = 4.51 g/cm³
    
    # Mass attenuation coefficients (cm²/g) from NIST XCOM
    # These are TOTAL (PE + Compton + pair), not just PE
    if energy_kev < 100:
        mu_rho = 6.0 * np.exp(-(energy_kev - 50) / 50) + 0.5
    elif energy_kev < 500:
        mu_rho = 0.5 * np.exp(-(energy_kev - 100) / 300) + 0.12
    else:
        mu_rho = 0.12 * np.exp(-(energy_kev - 500) / 800) + 0.06
    
    density = 4.51
    mu = mu_rho * density  # Linear attenuation (1/cm)
    
    # Probability of at least one interaction
    interaction_prob = 1.0 - np.exp(-mu * char_length)
    
    # Housing absorption for low energies
    low_cutoff = 1.0 - np.exp(-energy_kev / 40.0)
    
    # Geometric efficiency
    eff = interaction_prob * low_cutoff * 0.5
    
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
    compton_to_peak_ratio: float = 0.5,
    detector_config: Optional[DetectorConfig] = None
) -> np.ndarray:
    """
    Generate realistic Compton continuum for a gamma line in small CsI scintillators.
    
    For RadiaCode devices (~1 cm³ CsI crystal), the Compton continuum has 
    characteristic shape:
    - Relatively flat plateau from low energy to near Compton edge
    - Mild rise near Compton edge (not a sharp peak like in large detectors)
    - The plateau level is significant because most gammas scatter once and escape
    - Very low energies (<50 keV) are absorbed/attenuated by housing
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        peak_energy: Energy of the gamma line (keV)
        peak_counts: Total counts in the photopeak
        compton_to_peak_ratio: Ratio of Compton counts to peak counts
        detector_config: Detector configuration for response modeling
    
    Returns:
        Array of Compton continuum counts
    """
    if peak_energy < 50:
        return np.zeros_like(energy_bins)
    
    # Compton edge energy: E_edge = E * 2α / (1 + 2α) where α = E / 511 keV
    alpha = peak_energy / 511.0
    compton_edge = peak_energy * (2 * alpha) / (1 + 2 * alpha)
    
    # Backscatter peak energy (180 degree scatter)
    backscatter_energy = peak_energy / (1 + 2 * alpha)
    
    continuum = np.zeros_like(energy_bins, dtype=np.float64)
    
    # Compton continuum extends from low energy to Compton edge
    # But for small detectors, even energies above edge can have some counts
    # due to multiple scatter and incomplete energy deposition
    mask = (energy_bins > 30) & (energy_bins < compton_edge * 1.05)
    
    if not np.any(mask):
        return continuum
    
    E = energy_bins[mask]
    
    # Normalized position in continuum (0 = lowest, 1 = Compton edge)
    x = E / compton_edge
    x = np.clip(x, 0, 1.05)
    
    # Klein-Nishina differential cross section (simplified)
    # For small scintillators, the shape is dominated by:
    # 1. Relatively FLAT plateau (single scatter + escape)
    # 2. Moderate rise near Compton edge
    # 3. Mild backscatter bump around 170-220 keV
    # 4. Low-energy attenuation from housing
    
    # Housing attenuation (reduces counts below ~50-80 keV)
    housing_atten = 1.0 - np.exp(-(E - 30) / 30.0)
    
    # Main plateau - relatively flat for small detectors
    # The Klein-Nishina shape is "smeared" by multiple scatter/escape
    plateau = 0.7 + 0.3 * x  # Nearly flat with slight rise
    
    # Compton edge region - broader enhancement for poor resolution
    edge_sigma = 0.15  # Broader than large detectors
    edge_enhancement = 1.0 + 0.5 * np.exp(-((x - 0.95) / edge_sigma)**2)
    
    # Backscatter bump (broad)
    bs_x = backscatter_energy / compton_edge
    backscatter_bump = 0.3 * np.exp(-((x - bs_x) / 0.15)**2)
    
    # Combine components
    shape = housing_atten * (plateau + backscatter_bump) * edge_enhancement
    
    # Handle energies slightly above Compton edge (tail)
    above_edge = x > 1.0
    if np.any(above_edge):
        shape[above_edge] *= np.exp(-((x[above_edge] - 1.0) / 0.03)**2)
    
    # Normalize and scale
    total_compton = peak_counts * compton_to_peak_ratio
    shape_sum = np.sum(shape)
    if shape_sum > 0:
        continuum[mask] = shape * (total_compton / shape_sum)
    
    return continuum


def generate_peak_with_compton(
    energy_bins: np.ndarray,
    peak_params: PeakParameters,
    detector_config: Optional[DetectorConfig] = None,
    include_compton: bool = True,
    compton_ratio: float = 0.6
) -> np.ndarray:
    """
    Generate a gamma peak with associated Compton continuum.
    
    The Compton continuum is calculated based on the difference between
    total interaction probability and photopeak efficiency. This means
    high-energy gammas that interact but don't deposit full energy
    contribute to the Compton continuum.
    
    For small CsI scintillators (~1 cm³), high-energy gammas have:
    - Low photopeak efficiency (hard to deposit ALL energy)
    - Moderate total interaction probability (often interact at least once)
    - High Compton contribution (difference between the two)
    
    Args:
        energy_bins: Array of energy bin centers (keV)
        peak_params: Peak parameters
        detector_config: Detector configuration
        include_compton: Whether to add Compton continuum
        compton_ratio: Scaling factor for Compton (typically 0.5-1.0)
    
    Returns:
        Array of counts including peak and continuum
    """
    if detector_config is None:
        detector_config = get_default_config()
    
    # Generate photopeak using photopeak efficiency
    peak = generate_peak_spectrum(energy_bins, peak_params, detector_config)
    
    if not include_compton or peak_params.energy_kev < 100:
        return peak
    
    E = peak_params.energy_kev
    
    # Get efficiencies
    eff_photopeak = detector_efficiency(E, detector_config)
    eff_total = total_interaction_efficiency(E, detector_config)
    
    # Compton events = interactions that don't deposit full energy
    # = total interactions - photopeak events
    # Scale by compton_ratio for tuning
    eff_compton = (eff_total - eff_photopeak) * compton_ratio
    
    if eff_compton <= 0:
        return peak
    
    # Calculate expected Compton counts
    # This is based on the same formula as photopeak but with Compton efficiency
    expected_compton = (
        peak_params.activity_bq *
        peak_params.live_time_s *
        peak_params.intensity *
        eff_compton
    )
    
    if expected_compton > 0:
        compton = generate_compton_continuum(
            energy_bins,
            E,
            expected_compton,  # Use expected Compton counts directly
            compton_to_peak_ratio=1.0,  # Already calculated the counts
            detector_config=detector_config
        )
        peak += compton
    
    return peak
    return peak


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
