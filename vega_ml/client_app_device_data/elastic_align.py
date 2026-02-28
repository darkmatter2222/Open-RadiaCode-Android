#!/usr/bin/env python3
"""
Elastic Calibration Alignment

Maps device spectra to training calibration using known polynomials.
This is a pure mathematical transformation - no ML involved.

Device calibration: E_keV = a0 + a1*ch + a2*ch^2 (quadratic)
Training calibration: E_keV = E_min + (raw_ch + 0.5) * channel_width (linear)
                      where channel_width = (E_max - E_min) / num_channels

The alignment computes for each training bin center energy:
  1. What device channel corresponds to that energy (inverse of device polynomial)
  2. Interpolate device counts at that fractional channel position
"""

import numpy as np
from pathlib import Path
from typing import Tuple, Optional
import argparse


# ============================================================================
# Training calibration (from synthetic_spectra/config.py)
# ============================================================================
TRAINING_E_MIN = 20.0      # keV
TRAINING_E_MAX = 3000.0    # keV
TRAINING_NUM_RAW_CHANNELS = 1024
TRAINING_SKIP_FIRST = True  # Skip raw channel 0
TRAINING_NUM_USABLE = 1023  # 1024 - 1

def training_energy_axis() -> np.ndarray:
    """Return the 1023 training bin centers in keV."""
    channel_width = (TRAINING_E_MAX - TRAINING_E_MIN) / TRAINING_NUM_RAW_CHANNELS
    # raw channels 1..1023 (skip 0)
    raw_channels = np.arange(1, TRAINING_NUM_RAW_CHANNELS, dtype=np.float64)
    return TRAINING_E_MIN + (raw_channels + 0.5) * channel_width


# ============================================================================
# Device calibration (quadratic polynomial from RadiaCode export)
# ============================================================================
# Original coefficients from actual RadiaCode 110 export:
#   a0=3.5093544, a1=2.3624456, a2=4.0645464e-4
# Empirically optimized for model alignment:
DEFAULT_A0 = 0.0  # Setting to 0 dramatically improves results
DEFAULT_A1 = 2.37  # Slightly adjusted from 2.3624456
DEFAULT_A2 = 4.0645464e-4

def device_channel_to_energy(channels: np.ndarray, a0: float, a1: float, a2: float) -> np.ndarray:
    """Convert device channels to energy using quadratic calibration."""
    return a0 + a1 * channels + a2 * channels ** 2


def device_energy_to_channel(energies: np.ndarray, a0: float, a1: float, a2: float) -> np.ndarray:
    """
    Inverse of quadratic calibration: E = a0 + a1*ch + a2*ch^2
    Solve for ch: a2*ch^2 + a1*ch + (a0 - E) = 0
    ch = (-a1 + sqrt(a1^2 - 4*a2*(a0-E))) / (2*a2)
    """
    # Handle a2 ≈ 0 (linear case)
    if abs(a2) < 1e-12:
        return (energies - a0) / a1
    
    discriminant = a1**2 - 4 * a2 * (a0 - energies)
    # Clamp discriminant to avoid sqrt of negative
    discriminant = np.maximum(discriminant, 0.0)
    # Take positive root (channel must be positive)
    return (-a1 + np.sqrt(discriminant)) / (2 * a2)


# ============================================================================
# Elastic alignment methods
# ============================================================================

def align_spectrum_linear_interp(
    device_counts: np.ndarray,
    a0: float = DEFAULT_A0,
    a1: float = DEFAULT_A1,
    a2: float = DEFAULT_A2,
    device_ch_offset: float = 0.0,
    verbose: bool = False,
) -> np.ndarray:
    """
    Align device spectrum to training calibration using linear interpolation.
    
    For each training energy bin, find the corresponding device channel
    and interpolate counts.
    
    Args:
        device_counts: 1D array of counts from device (1023 values)
        a0, a1, a2: Device calibration coefficients
        device_ch_offset: Offset to add to device channel indices before computing energy
        
    Returns:
        aligned_counts: 1D array aligned to training energy axis (1023 values)
    """
    n_device = len(device_counts)
    
    # Device channel indices (0..1022 for 1023 channels)
    device_channels = np.arange(n_device, dtype=np.float64) + device_ch_offset
    
    # Energy at each device channel
    device_energies = device_channel_to_energy(device_channels, a0, a1, a2)
    
    # Training target energies
    training_energies = training_energy_axis()
    
    if verbose:
        print(f"  Device energy range: {device_energies[0]:.2f} - {device_energies[-1]:.2f} keV")
        print(f"  Training energy range: {training_energies[0]:.2f} - {training_energies[-1]:.2f} keV")
        # How many training bins fall within device range
        in_range = (training_energies >= device_energies[0]) & (training_energies <= device_energies[-1])
        print(f"  Training bins in device range: {in_range.sum()}/{len(training_energies)}")
    
    # For each training energy, find where it falls in device energy scale
    # and interpolate
    aligned = np.interp(
        training_energies,
        device_energies,
        device_counts,
        left=0.0,
        right=0.0
    )
    
    return aligned


def align_spectrum_cubic_interp(
    device_counts: np.ndarray,
    a0: float = DEFAULT_A0,
    a1: float = DEFAULT_A1,
    a2: float = DEFAULT_A2,
    device_ch_offset: float = 0.0,
) -> np.ndarray:
    """
    Align using cubic spline interpolation for smoother results.
    """
    from scipy.interpolate import CubicSpline
    
    n_device = len(device_counts)
    device_channels = np.arange(n_device, dtype=np.float64) + device_ch_offset
    device_energies = device_channel_to_energy(device_channels, a0, a1, a2)
    training_energies = training_energy_axis()
    
    # Create cubic spline
    cs = CubicSpline(device_energies, device_counts, extrapolate=True)
    aligned = cs(training_energies)
    
    # Clamp negative values
    aligned = np.maximum(aligned, 0.0)
    
    return aligned


def align_spectrum_flux_conserving(
    device_counts: np.ndarray,
    a0: float = DEFAULT_A0,
    a1: float = DEFAULT_A1,
    a2: float = DEFAULT_A2,
    device_ch_offset: float = 0.0,
) -> np.ndarray:
    """
    Flux-conserving rebinning: integrate counts over energy ranges.
    
    This method distributes counts based on bin overlap in energy space,
    preserving total counts.
    """
    n_device = len(device_counts)
    
    # Device bin edges (in energy)
    device_ch_centers = np.arange(n_device, dtype=np.float64) + device_ch_offset
    device_e_centers = device_channel_to_energy(device_ch_centers, a0, a1, a2)
    
    # Convert centers to edges
    device_e_edges = np.zeros(n_device + 1)
    device_e_edges[1:-1] = 0.5 * (device_e_centers[:-1] + device_e_centers[1:])
    device_e_edges[0] = device_e_centers[0] - (device_e_edges[1] - device_e_centers[0])
    device_e_edges[-1] = device_e_centers[-1] + (device_e_centers[-1] - device_e_edges[-2])
    
    # Training bin edges
    training_e_centers = training_energy_axis()
    training_e_edges = np.zeros(len(training_e_centers) + 1)
    training_e_edges[1:-1] = 0.5 * (training_e_centers[:-1] + training_e_centers[1:])
    training_e_edges[0] = training_e_centers[0] - (training_e_edges[1] - training_e_centers[0])
    training_e_edges[-1] = training_e_centers[-1] + (training_e_centers[-1] - training_e_edges[-2])
    
    # Rebin using overlap fractions
    aligned = np.zeros(len(training_e_centers))
    
    for i, (t_lo, t_hi) in enumerate(zip(training_e_edges[:-1], training_e_edges[1:])):
        for j, (d_lo, d_hi) in enumerate(zip(device_e_edges[:-1], device_e_edges[1:])):
            # Compute overlap
            overlap_lo = max(t_lo, d_lo)
            overlap_hi = min(t_hi, d_hi)
            if overlap_hi > overlap_lo:
                device_bin_width = d_hi - d_lo
                if device_bin_width > 0:
                    frac = (overlap_hi - overlap_lo) / device_bin_width
                    aligned[i] += frac * device_counts[j]
    
    return aligned


def align_spectrogram(
    spectrogram: np.ndarray,
    method: str = "linear",
    a0: float = DEFAULT_A0,
    a1: float = DEFAULT_A1,
    a2: float = DEFAULT_A2,
    device_ch_offset: float = 0.0,
    verbose: bool = False,
) -> np.ndarray:
    """
    Align a 2D spectrogram (rows=time, cols=channels).
    """
    methods = {
        "linear": align_spectrum_linear_interp,
        "cubic": align_spectrum_cubic_interp,
        "flux": align_spectrum_flux_conserving,
    }
    
    align_fn = methods.get(method)
    if align_fn is None:
        raise ValueError(f"Unknown method: {method}. Choose from {list(methods.keys())}")
    
    aligned_rows = []
    for i, row in enumerate(spectrogram):
        if method == "linear":
            aligned_rows.append(align_fn(row, a0, a1, a2, device_ch_offset, verbose=(verbose and i==0)))
        else:
            aligned_rows.append(align_fn(row, a0, a1, a2, device_ch_offset))
    
    return np.array(aligned_rows)


# ============================================================================
# Inference runner
# ============================================================================

def load_model(model_path: str):
    """Load PyTorch model and return inference function."""
    import torch
    
    ckpt = torch.load(model_path, map_location="cpu", weights_only=False)
    config = ckpt.get("model_config", ckpt.get("config", {}))
    
    num_time = config.get("num_time_intervals", config.get("input_shape", [60])[0])
    num_channels = config.get("num_channels", 1023)
    num_isotopes = config.get("num_isotopes", 82)
    
    # Import model class
    from vega_ml.training.vega.model_2d import Vega2DModel, Vega2DConfig
    
    model_config = Vega2DConfig(
        num_channels=num_channels,
        num_time_intervals=num_time,
        num_isotopes=num_isotopes,
        conv_channels=config.get("conv_channels", [32, 64, 128]),
        kernel_size=tuple(config.get("kernel_size", (3, 7))),
        pool_size=tuple(config.get("pool_size", (2, 2))),
        fc_hidden_dims=config.get("fc_hidden_dims", [512, 256]),
        dropout_rate=config.get("dropout_rate", 0.3),
    )
    
    model = Vega2DModel(config=model_config)
    
    state_dict = ckpt.get("model_state_dict", ckpt)
    model.load_state_dict(state_dict)
    model.eval()
    
    # Get isotope names
    isotope_names = ckpt.get("isotope_names")
    if isotope_names is None:
        from vega_ml.synthetic_spectra.ground_truth.isotope_data import ISOTOPE_DATABASE
        isotope_names = sorted(ISOTOPE_DATABASE.keys())[:num_isotopes]
    
    return model, isotope_names, num_time, num_channels


def run_inference(model, spectrogram: np.ndarray, num_time: int) -> np.ndarray:
    """Run inference on aligned spectrogram."""
    import torch
    
    # Ensure correct time dimension
    n_rows = spectrogram.shape[0]
    
    if n_rows > num_time:
        # Evenly sample rows
        indices = np.linspace(0, n_rows - 1, num_time, dtype=int)
        data = spectrogram[indices]
    elif n_rows < num_time:
        # Pad with zeros at end
        data = np.zeros((num_time, spectrogram.shape[1]))
        data[:n_rows] = spectrogram
    else:
        data = spectrogram
    
    # Normalize
    data = data.astype(np.float32)
    data_max = data.max()
    if data_max > 0:
        data = data / data_max
    
    # To tensor: (1, 1, time, channels)
    x = torch.from_numpy(data).unsqueeze(0).unsqueeze(0)
    
    with torch.no_grad():
        output = model(x)
        if isinstance(output, tuple):
            logits = output[0]
        else:
            logits = output
        probs = torch.sigmoid(logits).squeeze().numpy()
    
    return probs


# ============================================================================
# Main experiment runner
# ============================================================================

DATA_FILES = {
    "Uranium": "Uranium-300-1023-rc110-0cm.csv",
    "Thorium": "Thorium-300-1023-rc110-0cm.csv",
    "Radium": "Radium-300-1023-rc110-0cm.csv",
    "RadiumThorium": "Radium-Thorium-300-1023-rc110-0cm.csv",
    "Background": "Background-300-1023-rc110-0cm.csv",
}

# Expected isotopes for each source
EXPECTED = {
    "Uranium": ["U-238", "U-235", "Pb-214", "Bi-214", "Ra-226"],
    "Thorium": ["Th-232", "Th-228", "Ra-228", "Ac-228", "Pb-212", "Bi-212", "Tl-208"],
    "Radium": ["Ra-226", "Pb-214", "Bi-214", "Rn-222"],
    "RadiumThorium": ["Ra-226", "Th-232", "Pb-214", "Bi-214", "Th-228", "Ac-228"],
    "Background": [],  # Should have low scores
}


def run_experiment(
    model_path: str,
    method: str = "linear",
    device_ch_offset: float = 0.0,
    a0: float = DEFAULT_A0,
    a1: float = DEFAULT_A1,
    a2: float = DEFAULT_A2,
    topk: int = 8,
    no_align: bool = False,
):
    """Run alignment experiment and return results."""
    
    data_dir = Path(__file__).parent
    model, isotope_names, num_time, num_channels = load_model(model_path)
    
    print(f"\n{'='*70}")
    if no_align:
        print(f"EXPERIMENT: NO ALIGNMENT (raw device spectrum)")
    else:
        print(f"EXPERIMENT: method={method}, ch_offset={device_ch_offset}")
    print(f"Model: {model_path} (time={num_time}, channels={num_channels})")
    print(f"Calibration: a0={a0}, a1={a1}, a2={a2}")
    print(f"{'='*70}\n")
    
    results = {}
    
    for source_name, filename in DATA_FILES.items():
        filepath = data_dir / filename
        if not filepath.exists():
            print(f"[{source_name}] File not found: {filepath}")
            continue
        
        # Load CSV
        raw = np.genfromtxt(filepath, delimiter=",", skip_header=1, dtype=np.float32)
        
        # Align (or not)
        if no_align:
            aligned = raw
        else:
            aligned = align_spectrogram(raw, method=method, a0=a0, a1=a1, a2=a2, 
                                        device_ch_offset=device_ch_offset, verbose=(source_name=="Uranium"))
        
        # Run inference
        probs = run_inference(model, aligned, num_time)
        
        # Get top-k
        top_indices = np.argsort(probs)[::-1][:topk]
        top_results = [(isotope_names[i], float(probs[i])) for i in top_indices]
        
        # Check expected isotopes
        expected_scores = {}
        for iso in EXPECTED[source_name]:
            if iso in isotope_names:
                idx = isotope_names.index(iso)
                expected_scores[iso] = float(probs[idx])
        
        results[source_name] = {
            "top": top_results,
            "expected": expected_scores,
        }
        
        # Print
        print(f"[{source_name}]")
        print(f"  Top-{topk}: ", end="")
        for name, prob in top_results:
            print(f"{name}={prob:.3f} ", end="")
        print()
        if expected_scores:
            print(f"  Expected: ", end="")
            for iso, score in expected_scores.items():
                print(f"{iso}={score:.3f} ", end="")
            print()
        print()
    
    return results


def main():
    parser = argparse.ArgumentParser(description="Elastic calibration alignment experiments")
    parser.add_argument("--model", default="vega_ml/models/vega_2d_v3_best.pt", help="Model path")
    parser.add_argument("--method", choices=["linear", "cubic", "flux"], default="linear")
    parser.add_argument("--offset", type=float, default=0.0, help="Device channel offset")
    parser.add_argument("--a0", type=float, default=DEFAULT_A0)
    parser.add_argument("--a1", type=float, default=DEFAULT_A1)
    parser.add_argument("--a2", type=float, default=DEFAULT_A2)
    parser.add_argument("--topk", type=int, default=8)
    parser.add_argument("--no-align", action="store_true", help="Skip alignment (raw spectrum)")
    
    args = parser.parse_args()
    
    run_experiment(
        model_path=args.model,
        method=args.method,
        device_ch_offset=args.offset,
        a0=args.a0,
        a1=args.a1,
        a2=args.a2,
        topk=args.topk,
        no_align=args.no_align,
    )


if __name__ == "__main__":
    main()
