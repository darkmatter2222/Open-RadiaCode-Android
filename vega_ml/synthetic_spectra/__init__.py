"""
Synthetic Gamma Spectra Generation Module

This module provides tools for generating realistic synthetic gamma spectra
for training isotope identification models. It simulates detector responses
compatible with Radiacode devices (101, 102, 103, 103G, 110).

Detector Specifications:
- Energy Range: 20 keV to 3000 keV (0.02 - 3 MeV)
- Channels: 1024 (usable: 1023)
- FWHM Resolution: 7.4% - 9.5% @ 662 keV (model dependent)
- Detector Types: CsI(Tl) and GAGG(Ce) scintillators
"""

__version__ = "0.1.0"
__author__ = "Isotope ID ML Project"

from .config import DetectorConfig, RADIACODE_CONFIGS
