#!/usr/bin/env python3
"""
Spectrum Translation Layer

Translates device spectra from quadratic calibration to linear training calibration.

The Problem:
- Training data uses LINEAR energy bins: E = 20 + (ch + 0.5) * 2.91 keV
- Device data uses QUADRATIC energy: E = a0 + a1*ch + a2*ch^2

The Solution:
For each training energy bin, find the corresponding position in the device spectrum
and interpolate counts. This "stretches" the device spectrum to match training bins.

Visual explanation:
- At low channels: device keV < training keV, so we're reading from earlier device channels
- At mid channels: device keV << training keV, maximum stretch needed
- At high channels: device keV catches up somewhat, less stretch needed
"""

import numpy as np
from typing import Tuple

# Training calibration constants (from synthetic_spectra/config.py)
TRAIN_E_MIN = 20.0      # keV
TRAIN_E_MAX = 3000.0    # keV
TRAIN_NUM_RAW = 1024    # Total raw channels
TRAIN_SKIP_FIRST = True # Skip raw channel 0
TRAIN_CH_WIDTH = (TRAIN_E_MAX - TRAIN_E_MIN) / TRAIN_NUM_RAW  # 2.9102 keV

def get_training_energy_axis() -> np.ndarray:
    """
    Get the 1023 training bin centers in keV.
    These are LINEARLY spaced.
    """
    # Raw channels 1..1023 (skip 0), bin center at raw_ch + 0.5
    raw_channels = np.arange(1, TRAIN_NUM_RAW, dtype=np.float64)
    return TRAIN_E_MIN + (raw_channels + 0.5) * TRAIN_CH_WIDTH


def get_device_energy_axis(a0: float, a1: float, a2: float, n_channels: int = 1023) -> np.ndarray:
    """
    Get device energy for each channel using quadratic calibration.
    E = a0 + a1*ch + a2*ch^2
    """
    channels = np.arange(n_channels, dtype=np.float64)
    return a0 + a1 * channels + a2 * channels**2


def translate_spectrum(
    device_counts: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4,
) -> np.ndarray:
    """
    Translate device spectrum from quadratic to linear calibration.
    
    This is the core translation function. For each training energy bin,
    it finds the corresponding fractional position in the device spectrum
    and interpolates the counts.
    
    Args:
        device_counts: 1D array of counts from device (1023 values)
        a0, a1, a2: Device quadratic calibration coefficients
        
    Returns:
        translated_counts: 1D array aligned to training energy axis (1023 values)
    """
    n_ch = len(device_counts)
    
    # Get energy axes
    device_energies = get_device_energy_axis(a0, a1, a2, n_ch)
    training_energies = get_training_energy_axis()
    
    # Interpolate: for each training energy, find device counts
    # np.interp(x, xp, fp) returns fp values at positions x, given known points (xp, fp)
    translated = np.interp(
        training_energies,      # Where we want values (training bins)
        device_energies,        # Known x positions (device energies)
        device_counts,          # Known y values (device counts)
        left=0.0,               # Out-of-range handling
        right=0.0
    )
    
    return translated


def translate_spectrogram(
    spectrogram: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4,
) -> np.ndarray:
    """
    Translate 2D spectrogram (rows=time, cols=channels).
    """
    translated_rows = [translate_spectrum(row, a0, a1, a2) for row in spectrogram]
    return np.array(translated_rows)


def get_channel_mapping(a0: float, a1: float, a2: float, n_ch: int = 1023) -> np.ndarray:
    """
    Get the mapping: for each training channel, which device channel does it read from?
    
    This shows exactly how the translation "stretches" the spectrum.
    
    Returns:
        device_channel_positions: Array where index i = training channel,
                                  value = fractional device channel position
    """
    device_energies = get_device_energy_axis(a0, a1, a2, n_ch)
    training_energies = get_training_energy_axis()
    
    # For each training energy, find where it falls in device energy scale
    # This is the inverse of the interpolation
    device_positions = np.interp(
        training_energies,
        device_energies,
        np.arange(n_ch, dtype=np.float64)
    )
    
    return device_positions


def print_mapping_table(a0: float = 3.5093544, a1: float = 2.3624456, a2: float = 4.0645464e-4):
    """Print the channel mapping table."""
    device_energies = get_device_energy_axis(a0, a1, a2)
    training_energies = get_training_energy_axis()
    mapping = get_channel_mapping(a0, a1, a2)
    
    print("Training Ch | Training keV | -> Device Ch | Device keV | Stretch Factor")
    print("-" * 75)
    
    for i in [0, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1022]:
        t_e = training_energies[i]
        d_ch = mapping[i]
        d_ch_int = int(round(d_ch))
        if d_ch_int < len(device_energies):
            d_e = device_energies[d_ch_int]
        else:
            d_e = float('nan')
        
        # Stretch factor: how many device channels map to one training channel
        if i > 0:
            stretch = (mapping[i] - mapping[i-1])
        else:
            stretch = mapping[1] - mapping[0]
        
        print(f"{i:11d} | {t_e:12.2f} | -> {d_ch:10.2f} | {d_e:10.2f} | {stretch:.4f}")


if __name__ == "__main__":
    print("=" * 75)
    print("SPECTRUM TRANSLATION LAYER")
    print("=" * 75)
    print()
    print("Training calibration: LINEAR")
    print(f"  E = {TRAIN_E_MIN} + (raw_ch + 0.5) * {TRAIN_CH_WIDTH:.4f} keV")
    print(f"  Range: {TRAIN_E_MIN} - {TRAIN_E_MAX} keV")
    print(f"  Channel width: {TRAIN_CH_WIDTH:.4f} keV (constant)")
    print()
    print("Device calibration: QUADRATIC")
    print("  E = a0 + a1*ch + a2*ch^2")
    print("  Default: a0=3.5093544, a1=2.3624456, a2=4.0645464e-4")
    print()
    
    print_mapping_table()
    
    print()
    print("Translation concept:")
    print("  Training channel 100 is at 315 keV")
    print("  315 keV in device calibration is at channel ~129")
    print("  So training channel 100 reads from device channel 129")
    print("  This 'stretches' the device spectrum to match training")
