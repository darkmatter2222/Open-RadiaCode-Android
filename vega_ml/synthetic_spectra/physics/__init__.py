"""
Physics Module

Contains spectrum generation physics including:
- Peak shape modeling
- Background generation
- Detector response
- Counting statistics
"""

from .spectrum_physics import (
    PeakParameters,
    gaussian_peak,
    calculate_fwhm,
    fwhm_to_sigma,
    detector_efficiency,
    calculate_expected_counts,
    generate_peak_spectrum,
    generate_compton_continuum,
    generate_exponential_background,
    generate_polynomial_background,
    generate_environmental_background,
    apply_poisson_noise,
    apply_electronic_noise,
    normalize_spectrum,
)
