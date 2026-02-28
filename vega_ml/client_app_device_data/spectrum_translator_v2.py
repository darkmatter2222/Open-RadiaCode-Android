"""
Spectrum translator v2 with flux-conserving interpolation.

This version properly redistributes counts when rebinning spectra,
preserving total counts (important for Poisson statistics).
"""
import numpy as np
from typing import Tuple

# Training calibration constants
TRAINING_E_MIN = 20.0      # keV
TRAINING_E_MAX = 3000.0    # keV  
TRAINING_RAW_CHANNELS = 1024
TRAINING_USABLE_CHANNELS = 1023  # Skip raw channel 0
TRAINING_KEV_PER_CHANNEL = (TRAINING_E_MAX - TRAINING_E_MIN) / TRAINING_RAW_CHANNELS  # ~2.9102

def get_training_energy_edges() -> np.ndarray:
    """
    Get the 1024 bin EDGES for training energy axis.
    Bin i spans [edge[i], edge[i+1]).
    """
    # Raw channels 0..1023 -> usable channels 1..1023 (indices 0..1022)
    # Edge i corresponds to the left edge of training bin i
    edges = np.zeros(TRAINING_USABLE_CHANNELS + 1)
    for i in range(TRAINING_USABLE_CHANNELS + 1):
        raw_ch = i + 1  # Skip raw channel 0
        edges[i] = TRAINING_E_MIN + raw_ch * TRAINING_KEV_PER_CHANNEL
    return edges

def get_training_energy_centers() -> np.ndarray:
    """Get bin centers for training energy axis."""
    edges = get_training_energy_edges()
    return 0.5 * (edges[:-1] + edges[1:])

def get_device_energy_edges(a0: float, a1: float, a2: float, n_channels: int = 1023) -> np.ndarray:
    """
    Get the bin EDGES for device energy axis with quadratic calibration.
    E(ch) = a0 + a1*ch + a2*ch^2
    """
    edges = np.zeros(n_channels + 1)
    for i in range(n_channels + 1):
        edges[i] = a0 + a1 * i + a2 * i * i
    return edges

def get_device_energy_centers(a0: float, a1: float, a2: float, n_channels: int = 1023) -> np.ndarray:
    """Get bin centers for device energy axis."""
    edges = get_device_energy_edges(a0, a1, a2, n_channels)
    return 0.5 * (edges[:-1] + edges[1:])

def flux_conserving_rebin(
    src_counts: np.ndarray,
    src_edges: np.ndarray,
    dst_edges: np.ndarray
) -> np.ndarray:
    """
    Rebin a histogram (counts) from source bins to destination bins,
    conserving total flux (counts).
    
    Args:
        src_counts: Counts in each source bin (length N)
        src_edges: Source bin edges (length N+1)
        dst_edges: Destination bin edges (length M+1)
    
    Returns:
        Counts in destination bins (length M)
    """
    n_src = len(src_counts)
    n_dst = len(dst_edges) - 1
    dst_counts = np.zeros(n_dst, dtype=np.float32)
    
    # For each destination bin, find overlapping source bins
    for i_dst in range(n_dst):
        dst_lo = dst_edges[i_dst]
        dst_hi = dst_edges[i_dst + 1]
        
        # Find source bins that overlap with this destination bin
        for i_src in range(n_src):
            src_lo = src_edges[i_src]
            src_hi = src_edges[i_src + 1]
            
            # Skip if no overlap
            if src_hi <= dst_lo or src_lo >= dst_hi:
                continue
            
            # Calculate overlap
            overlap_lo = max(src_lo, dst_lo)
            overlap_hi = min(src_hi, dst_hi)
            overlap_width = overlap_hi - overlap_lo
            src_width = src_hi - src_lo
            
            # Fraction of source bin that falls in destination bin
            if src_width > 0:
                fraction = overlap_width / src_width
                dst_counts[i_dst] += src_counts[i_src] * fraction
    
    return dst_counts

def translate_spectrum_flux_conserving(
    device_counts: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4
) -> np.ndarray:
    """
    Translate device spectrum to training energy axis using flux-conserving rebinning.
    
    Args:
        device_counts: 1D array of counts from device (1023 channels)
        a0, a1, a2: Device calibration coefficients E = a0 + a1*ch + a2*ch^2
    
    Returns:
        1D array of counts in training energy bins (1023 channels)
    """
    device_edges = get_device_energy_edges(a0, a1, a2, len(device_counts))
    training_edges = get_training_energy_edges()
    
    return flux_conserving_rebin(device_counts, device_edges, training_edges)

def translate_spectrum_linear(
    device_counts: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4
) -> np.ndarray:
    """
    Translate device spectrum to training energy axis using linear interpolation.
    (Original method from spectrum_translator.py)
    """
    device_centers = get_device_energy_centers(a0, a1, a2, len(device_counts))
    training_centers = get_training_energy_centers()
    
    return np.interp(training_centers, device_centers, device_counts, left=0, right=0)

def translate_spectrogram_flux(
    spectrogram: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4
) -> np.ndarray:
    """Translate 2D spectrogram (time x channels) using flux-conserving rebinning."""
    n_rows = spectrogram.shape[0]
    n_channels = 1023
    result = np.zeros((n_rows, n_channels), dtype=np.float32)
    
    device_edges = get_device_energy_edges(a0, a1, a2, spectrogram.shape[1])
    training_edges = get_training_energy_edges()
    
    for i in range(n_rows):
        result[i] = flux_conserving_rebin(spectrogram[i], device_edges, training_edges)
    
    return result

def translate_spectrogram_linear(
    spectrogram: np.ndarray,
    a0: float = 3.5093544,
    a1: float = 2.3624456,
    a2: float = 4.0645464e-4
) -> np.ndarray:
    """Translate 2D spectrogram using linear interpolation."""
    n_rows = spectrogram.shape[0]
    n_channels = 1023
    result = np.zeros((n_rows, n_channels), dtype=np.float32)
    
    device_centers = get_device_energy_centers(a0, a1, a2, spectrogram.shape[1])
    training_centers = get_training_energy_centers()
    
    for i in range(n_rows):
        result[i] = np.interp(training_centers, device_centers, spectrogram[i], left=0, right=0)
    
    return result


if __name__ == "__main__":
    # Test
    import matplotlib.pyplot as plt
    
    # Create a test spectrum with a peak
    device_counts = np.zeros(1023)
    device_counts[200:210] = 100  # Peak around channel 200-210
    
    a0, a1, a2 = 3.5093544, 2.3624456, 4.0645464e-4
    
    flux_result = translate_spectrum_flux_conserving(device_counts, a0, a1, a2)
    linear_result = translate_spectrum_linear(device_counts, a0, a1, a2)
    
    print(f"Original total counts: {device_counts.sum():.2f}")
    print(f"Flux-conserving total: {flux_result.sum():.2f}")
    print(f"Linear interp total: {linear_result.sum():.2f}")
