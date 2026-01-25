"""
Detector Configuration Module

Contains configuration parameters for Radiacode gamma spectrometers
and other detector settings.
"""

from dataclasses import dataclass, field
from typing import Dict, Optional
import numpy as np


@dataclass
class DetectorConfig:
    """Configuration for a gamma spectrometer detector."""
    
    name: str
    # Energy range in keV
    energy_min_kev: float = 20.0
    energy_max_kev: float = 3000.0
    
    # Number of channels
    num_channels: int = 1024

    # Some devices/software workflows treat channel 0 as unreliable/noisy.
    # This project models "usable" channels by skipping the first raw channel.
    skip_first_channel: bool = True
    
    # FWHM at 662 keV (Cs-137 reference) as fraction
    fwhm_at_662: float = 0.084  # 8.4%
    fwhm_uncertainty: float = 0.003  # ±0.3%
    
    # Detector crystal type
    crystal_type: str = "CsI(Tl)"
    
    # Sensitivity: counts per second at 1 μSv/h for Cs-137
    sensitivity_cps_per_usvh: float = 30.0
    
    # Detector volume in cm³
    detector_volume_cm3: float = 1.0
    
    def get_channel_width_kev(self) -> float:
        """Get the width of each channel in keV."""
        return (self.energy_max_kev - self.energy_min_kev) / self.num_channels
    
    def get_energy_bins(self) -> np.ndarray:
        """Get array of energy bin centers (keV) for the modeled usable channels."""
        channel_width = self.get_channel_width_kev()

        # Raw device channels are assumed to be 0..num_channels-1 with centers:
        #   E_center(k) = E_min + (k + 0.5) * channel_width
        # If we skip the first raw channel (k=0), we model usable channels k=1..num_channels-1.
        start_raw_channel = 1 if self.skip_first_channel else 0
        raw_channels = np.arange(start_raw_channel, self.num_channels, dtype=np.float64)
        return self.energy_min_kev + (raw_channels + 0.5) * channel_width
    
    def get_fwhm_at_energy(self, energy_kev: float) -> float:
        """
        Calculate FWHM at a given energy.
        
        For scintillators, FWHM scales approximately as sqrt(E).
        FWHM(E) = FWHM_662 * sqrt(662/E) * E / 662 = FWHM_662 * sqrt(E/662)
        """
        return self.fwhm_at_662 * np.sqrt(662.0 / energy_kev) * energy_kev
    
    def get_sigma_at_energy(self, energy_kev: float) -> float:
        """
        Get Gaussian sigma at a given energy.
        sigma = FWHM / (2 * sqrt(2 * ln(2))) ≈ FWHM / 2.355
        """
        fwhm = self.get_fwhm_at_energy(energy_kev)
        return fwhm / 2.355
    
    def energy_to_channel(self, energy_kev: float) -> int:
        """Convert energy in keV to modeled usable channel index."""
        channel_width = self.get_channel_width_kev()
        raw_channel = int((energy_kev - self.energy_min_kev) / channel_width)
        if self.skip_first_channel:
            channel = raw_channel - 1
            max_channel = self.num_channels - 2
        else:
            channel = raw_channel
            max_channel = self.num_channels - 1
        return max(0, min(max_channel, channel))

    def channel_to_energy(self, channel: int) -> float:
        """Convert modeled usable channel index to energy bin center (keV)."""
        channel_width = self.get_channel_width_kev()
        raw_channel = channel + (1 if self.skip_first_channel else 0)
        raw_channel = max(0, min(self.num_channels - 1, int(raw_channel)))
        return self.energy_min_kev + (raw_channel + 0.5) * channel_width


# Pre-defined configurations for Radiacode devices
RADIACODE_CONFIGS: Dict[str, DetectorConfig] = {
    "radiacode_101": DetectorConfig(
        name="Radiacode 101",
        fwhm_at_662=0.095,  # 9.5% (original model, similar to 102)
        fwhm_uncertainty=0.004,
        crystal_type="CsI(Tl)",
        sensitivity_cps_per_usvh=30.0,
        detector_volume_cm3=1.0,
    ),
    "radiacode_102": DetectorConfig(
        name="Radiacode 102",
        fwhm_at_662=0.095,  # 9.5%
        fwhm_uncertainty=0.004,
        crystal_type="CsI(Tl)",
        sensitivity_cps_per_usvh=30.0,
        detector_volume_cm3=1.0,
    ),
    "radiacode_103": DetectorConfig(
        name="Radiacode 103",
        fwhm_at_662=0.084,  # 8.4%
        fwhm_uncertainty=0.003,
        crystal_type="CsI(Tl)",
        sensitivity_cps_per_usvh=30.0,
        detector_volume_cm3=1.0,
    ),
    "radiacode_103g": DetectorConfig(
        name="Radiacode 103G",
        energy_min_kev=25.0,  # Tech spec lists 0.025…3 MeV
        fwhm_at_662=0.074,  # 7.4% (GAGG crystal - better resolution)
        fwhm_uncertainty=0.003,
        crystal_type="GAGG(Ce)",
        sensitivity_cps_per_usvh=40.0,
        detector_volume_cm3=1.0,
    ),
    "radiacode_110": DetectorConfig(
        name="Radiacode 110",
        fwhm_at_662=0.084,  # 8.4%
        fwhm_uncertainty=0.003,
        crystal_type="CsI(Tl)",
        sensitivity_cps_per_usvh=77.0,  # Higher sensitivity
        detector_volume_cm3=2.5,  # Larger crystal
    ),
}


def get_default_config() -> DetectorConfig:
    """Get the default detector configuration (Radiacode 103)."""
    return RADIACODE_CONFIGS["radiacode_103"]
