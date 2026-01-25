"""Analyze a captured middleware inference log (request+response).

Reads a JSON file like analyzer/out/last_inference_detail_*.json produced by
analyzer/fetch_last_inference.ps1 and prints diagnostics focused on:
- input spectrum shape/range
- quantization / clamping artifacts
- energy-window evidence for uranium chain peaks
- server output probabilities for U-234/U-235/U-238

Usage:
  python analyzer/analyze_last_inference.py --path analyzer/out/last_inference_detail_*.json

Exit code is always 0; this is a reporting tool.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np


@dataclass(frozen=True)
class ModelGrid:
    emin_kev: float = 20.0
    emax_kev: float = 3000.0
    num_channels: int = 1023

    @property
    def step_kev(self) -> float:
        return (self.emax_kev - self.emin_kev) / self.num_channels

    def energy_to_channel(self, energy_kev: float) -> int:
        # Mirror how the repo’s helper scripts commonly approximate channel index.
        ch = int(round((energy_kev - self.emin_kev) / self.step_kev))
        return int(np.clip(ch, 0, self.num_channels - 1))

    def channel_to_energy(self, channel: int) -> float:
        return self.emin_kev + channel * self.step_kev


def _head(values: Iterable[float], n: int = 12) -> str:
    vals = list(values)
    return ", ".join(f"{v:.6g}" for v in vals[:n])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--path", required=True, help="Path to last_inference_detail_*.json")
    ap.add_argument("--window", type=int, default=2, help="Half-window (channels) for peak window sum")
    args = ap.parse_args()

    path = Path(args.path)
    # PowerShell may write UTF-8 with BOM; handle both.
    obj = json.loads(path.read_text(encoding="utf-8-sig"))

    req = obj.get("request", {}).get("json", {})
    resp = obj.get("response", {}).get("json", {})

    spectrum = req.get("spectrum")
    if spectrum is None:
        raise SystemExit("No request.json.spectrum found in the log detail JSON")

    arr = np.asarray(spectrum, dtype=np.float64)
    grid = ModelGrid()

    print(f"file: {path}")
    print(f"request spectrum shape: {arr.shape}")
    print(f"request spectrum range: min={arr.min():.6g} max={arr.max():.6g} mean={arr.mean():.6g}")

    # Quantization / clamping check
    flat = arr.ravel()
    # Sample to keep this quick on huge logs
    sample = flat[:: max(1, flat.size // 200_000)]
    uniq = np.unique(np.round(sample, 12))
    print(f"unique(sampled,rounded) count={len(uniq)}")
    print(f"unique head: {_head(uniq)}")

    # “Looks like quantized steps” heuristic
    if len(uniq) <= 64:
        steps = np.diff(uniq)
        steps = steps[steps > 0]
        if steps.size:
            step_med = float(np.median(steps))
            print(f"quantization hint: median_step≈{step_med:.6g}")

    # Channel energy distribution
    channel_sums = arr.sum(axis=0)
    nonzero_channels = int(np.count_nonzero(channel_sums))
    print(f"channels with any signal: {nonzero_channels}/{grid.num_channels} ({nonzero_channels/grid.num_channels:.1%})")

    # Top channels (where energy actually is)
    top_k = 12
    top_idx = np.argsort(channel_sums)[::-1][:top_k]
    print("top channels by sum (time-collapsed):")
    for ch in top_idx:
        s = float(channel_sums[ch])
        e = grid.channel_to_energy(int(ch))
        print(f"  ch={int(ch):4d}  E≈{e:7.1f} keV  sum={s:.6g}")

    # Window-sum helper
    w = int(args.window)

    def window_sum(center_ch: int) -> float:
        lo = max(0, center_ch - w)
        hi = min(grid.num_channels - 1, center_ch + w)
        return float(arr[:, lo : hi + 1].sum())

    # Evidence around key uranium chain energies.
    energies = {
        "U-238 49.6": 49.6,
        "U-238 113.5": 113.5,
        "Ra-226 186.2": 186.2,
        "Pb-214 295.2": 295.2,
        "Pb-214 351.9": 351.9,
        "Bi-214 609.3": 609.3,
        "Bi-214 1120": 1120.3,
        "Bi-214 1764": 1764.5,
        "Tl-208 2614": 2614.5,
    }

    print(f"energy-window sums (±{w} channels):")
    for name, e in energies.items():
        ch = grid.energy_to_channel(e)
        s = window_sum(ch)
        print(f"  {name:12s} ch={ch:4d}  window_sum={s:.6g}")

    # Server response: uranium-related probabilities
    names = resp.get("isotope_names") or []
    probs = resp.get("probabilities") or []
    thr = resp.get("threshold_used")

    if names and probs and len(names) == len(probs):
        name_to_idx = {n: i for i, n in enumerate(names)}
        print("server output (selected):")
        if thr is not None:
            print(f"  threshold_used={thr}")
        for iso in ("U-234", "U-235", "U-238", "Pb-214", "Bi-214", "Ra-226", "Th-232", "Th-234"):
            i = name_to_idx.get(iso)
            if i is None:
                print(f"  {iso}: not in isotope_names")
            else:
                p = float(probs[i])
                flag = "PRESENT" if (thr is not None and p >= float(thr)) else "-"
                print(f"  {iso:6s} idx={i:2d} prob={p:.6g} {flag}")

        # Top-10
        pairs = sorted(((n, float(probs[i])) for i, n in enumerate(names)), key=lambda x: x[1], reverse=True)[:10]
        print("top-10 probabilities:")
        for n, p in pairs:
            print(f"  {n:8s} {p:.6g}")

    else:
        print("No response.json.isotope_names/probabilities found (or lengths mismatch).")

    print("\nInterpretation hints:")
    print("- If the uranium/daughter energy-window sums are ~0, the client is likely rebinning/calibrating incorrectly, zeroing high-energy channels, or over-normalizing/quantizing.")
    print("- If the spectrum is already [0,1] with very few unique values, the client is likely clamping/quantizing (lossy) before sending to the server.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
