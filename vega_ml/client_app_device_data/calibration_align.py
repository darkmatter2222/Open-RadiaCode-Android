"""Calibration alignment helpers for RadiaCode spectra.

The Vega training pipeline assumes a linear energy axis with 1023 channels spanning
20–3000 keV.

RadiaCode devices produce spectra in channel space with a per-device energy
calibration (typically quadratic).

This module provides a deterministic mapping (energy-domain resampling) from a
RadiaCode spectrum to the Vega model's expected channel grid.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Tuple

import numpy as np


@dataclass(frozen=True)
class QuadraticEnergyCalibration:
    """Energy calibration: E_keV = a0 + a1*ch + a2*ch^2."""

    a0: float
    a1: float
    a2: float

    def energy_kev(self, channels: np.ndarray) -> np.ndarray:
        channels = np.asarray(channels, dtype=np.float64)
        return self.a0 + self.a1 * channels + self.a2 * (channels ** 2)


_COEFF_RE = re.compile(r"^#\s*a(?P<idx>[012])\b.*=\s*(?P<value>[-+0-9.eE]+)\s*keV")


def load_radiacode_calibration_coeffs(calibration_csv: str | Path) -> QuadraticEnergyCalibration:
    """Load the RC energy calibration coefficients from the RadiaCode export CSV."""

    calibration_csv = Path(calibration_csv)
    a0 = a1 = a2 = None

    with calibration_csv.open("r", encoding="utf-8", errors="replace") as f:
        for _ in range(100):
            line = f.readline()
            if not line:
                break
            match = _COEFF_RE.match(line.strip())
            if not match:
                continue
            idx = int(match.group("idx"))
            value = float(match.group("value"))
            if idx == 0:
                a0 = value
            elif idx == 1:
                a1 = value
            elif idx == 2:
                a2 = value

    if a0 is None or a1 is None or a2 is None:
        raise ValueError(
            "Could not parse a0/a1/a2 from calibration export. "
            "Expected comment lines like '# a1 (linear) = 2.36 keV/channel'."
        )

    return QuadraticEnergyCalibration(a0=a0, a1=a1, a2=a2)


def vega_energy_axis_kev(
    num_channels: int = 1023,
    energy_min_kev: float = 20.0,
    energy_max_kev: float = 3000.0,
) -> np.ndarray:
    """Legacy helper: simple linear energy axis.

    Note: The training data generator uses bin centers derived from a 1024-channel
    MCA model and typically skips raw channel 0. Prefer `vega_training_energy_axis_kev`.
    """

    return np.linspace(float(energy_min_kev), float(energy_max_kev), int(num_channels))


def vega_training_energy_axis_kev(detector_key: str = "radiacode_110") -> np.ndarray:
    """Energy bin centers used by the synthetic training pipeline.

    The generator models a 1024-channel MCA and (by default) skips raw channel 0,
    resulting in 1023 usable channels with centers:

      E_center(k) = E_min + (k + 0.5) * ((E_max - E_min) / 1024),  k=1..1023
    """

    from vega_ml.synthetic_spectra.config import RADIACODE_CONFIGS

    if detector_key not in RADIACODE_CONFIGS:
        raise KeyError(f"Unknown detector_key={detector_key!r}. Known: {sorted(RADIACODE_CONFIGS.keys())}")

    return RADIACODE_CONFIGS[detector_key].get_energy_bins()


def warp_spectrum_to_vega_energy_axis(
    spectrum_counts: np.ndarray,
    device_calibration: QuadraticEnergyCalibration,
    *,
    vega_energy_axis: np.ndarray | None = None,
    device_channel_offset: int = 0,
) -> np.ndarray:
    """Resample a 1D spectrum onto Vega's energy grid.

    This performs a count-conserving rebin in energy space.

    Why: the device calibration is nonlinear, so channel widths in keV vary by
    channel. Treating counts as point samples and interpolating can distort peak
    areas. Instead we:
      1) derive source bin edges from calibrated bin centers
      2) derive target bin edges from Vega training bin centers
      3) integrate (overlap) source-bin count density into each target bin

    Values outside the device's calibrated energy range are set to 0.
    """

    spectrum_counts = np.asarray(spectrum_counts, dtype=np.float64)
    if spectrum_counts.ndim != 1:
        raise ValueError(f"Expected 1D spectrum; got shape {spectrum_counts.shape}")

    num_channels = spectrum_counts.shape[0]
    if vega_energy_axis is None:
        vega_energy_axis = vega_energy_axis_kev(num_channels=num_channels)
    else:
        vega_energy_axis = np.asarray(vega_energy_axis, dtype=np.float64)
        if vega_energy_axis.shape != (num_channels,):
            raise ValueError(
                f"vega_energy_axis must have shape {(num_channels,)}; got {vega_energy_axis.shape}"
            )

    device_channels = np.arange(num_channels, dtype=np.float64) + float(device_channel_offset)
    device_energy_centers = device_calibration.energy_kev(device_channels)

    # Guard monotonicity (should be increasing; if not, sort to preserve overlap math).
    if not np.all(np.diff(device_energy_centers) > 0):
        order = np.argsort(device_energy_centers)
        device_energy_centers = device_energy_centers[order]
        spectrum_counts = spectrum_counts[order]

    source_edges = _centers_to_edges(device_energy_centers)
    target_edges = _centers_to_edges(vega_energy_axis)
    warped = _rebin_counts_by_edges(spectrum_counts, source_edges, target_edges)
    return warped.astype(np.float32)


def _centers_to_edges(centers: np.ndarray) -> np.ndarray:
    """Convert monotonically increasing bin centers to bin edges."""

    centers = np.asarray(centers, dtype=np.float64)
    if centers.ndim != 1 or centers.size < 2:
        raise ValueError("centers must be 1D with length >= 2")

    edges = np.empty(centers.size + 1, dtype=np.float64)
    edges[1:-1] = (centers[:-1] + centers[1:]) / 2.0
    # Extrapolate end edges assuming same spacing as nearest midpoint.
    edges[0] = centers[0] - (edges[1] - centers[0])
    edges[-1] = centers[-1] + (centers[-1] - edges[-2])
    return edges


def _rebin_counts_by_edges(source_counts: np.ndarray, source_edges: np.ndarray, target_edges: np.ndarray) -> np.ndarray:
    """Rebin counts from source bins into target bins conserving total counts.

    Assumes both edge arrays are strictly increasing.
    """

    source_counts = np.asarray(source_counts, dtype=np.float64)
    source_edges = np.asarray(source_edges, dtype=np.float64)
    target_edges = np.asarray(target_edges, dtype=np.float64)

    if source_counts.ndim != 1:
        raise ValueError("source_counts must be 1D")
    if source_edges.shape != (source_counts.size + 1,):
        raise ValueError("source_edges must have length len(source_counts)+1")
    if target_edges.ndim != 1 or target_edges.size < 2:
        raise ValueError("target_edges must be 1D with length >= 2")

    if not (np.all(np.diff(source_edges) > 0) and np.all(np.diff(target_edges) > 0)):
        raise ValueError("edges must be strictly increasing")

    n = source_counts.size
    m = target_edges.size - 1
    out = np.zeros(m, dtype=np.float64)

    # Quick reject if ranges don't overlap.
    if target_edges[-1] <= source_edges[0] or target_edges[0] >= source_edges[-1]:
        return out

    i = 0
    for j in range(m):
        t0 = target_edges[j]
        t1 = target_edges[j + 1]
        if t1 <= source_edges[0] or t0 >= source_edges[-1]:
            continue

        # Move source bin index so source_edges[i] <= t0 < source_edges[i+1]
        while i < n - 1 and source_edges[i + 1] <= t0:
            i += 1

        acc = 0.0
        k = i
        x = t0
        while x < t1 and k < n:
            s0 = source_edges[k]
            s1 = source_edges[k + 1]

            # No overlap with this source bin; advance.
            if s1 <= x:
                k += 1
                continue

            overlap0 = max(x, s0)
            overlap1 = min(t1, s1)
            if overlap1 > overlap0:
                width = s1 - s0
                acc += source_counts[k] * ((overlap1 - overlap0) / width)

            if s1 >= t1:
                x = t1
            else:
                x = s1
                k += 1

        out[j] = acc

    return out


def warp_spectrogram_to_vega_energy_axis(
    spectrogram_counts: np.ndarray,
    device_calibration: QuadraticEnergyCalibration,
    *,
    vega_energy_axis: np.ndarray | None = None,
    device_channel_offset: int = 0,
) -> np.ndarray:
    """Resample a (time, channels) array onto Vega's energy grid, row-by-row."""

    spectrogram_counts = np.asarray(spectrogram_counts, dtype=np.float64)
    if spectrogram_counts.ndim != 2:
        raise ValueError(f"Expected 2D spectrogram; got shape {spectrogram_counts.shape}")

    return np.stack(
        [
            warp_spectrum_to_vega_energy_axis(
                row,
                device_calibration,
                vega_energy_axis=vega_energy_axis,
                device_channel_offset=device_channel_offset,
            )
            for row in spectrogram_counts
        ],
        axis=0,
    )
