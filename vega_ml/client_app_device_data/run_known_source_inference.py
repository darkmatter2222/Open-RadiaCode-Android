"""Run Vega inference on known-source RadiaCode spectra with optional calibration warping.

Usage (from repo root):
  python -m vega_ml.client_app_device_data.run_known_source_inference --model vega_ml/models/vega_2d_final.pt --warp

This script is intentionally lightweight (no pandas dependency).
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

import numpy as np
import torch

from vega_ml.client_app_device_data.calibration_align import (
    load_radiacode_calibration_coeffs,
    vega_training_energy_axis_kev,
    warp_spectrogram_to_vega_energy_axis,
)


@dataclass(frozen=True)
class SampleSpec:
    label: str
    path: Path


DEFAULT_SAMPLES: List[SampleSpec] = [
    SampleSpec("Uranium", Path("vega_ml/client_app_device_data/Uranium-300-1023-rc110-0cm.csv")),
    SampleSpec("Thorium", Path("vega_ml/client_app_device_data/Thorium-300-1023-rc110-0cm.csv")),
    SampleSpec("Radium", Path("vega_ml/client_app_device_data/Radium-300-1023-rc110-0cm.csv")),
    SampleSpec(
        "Radium+Thorium", Path("vega_ml/client_app_device_data/Radium-Thorium-300-1023-rc110-0cm.csv")
    ),
    SampleSpec("Background", Path("vega_ml/client_app_device_data/Background-300-1023-rc110-0cm.csv")),
]


def _load_wide_numeric_csv(path: Path) -> np.ndarray:
    """Load a 1023-column spectra CSV produced for this repo."""

    # np.loadtxt is faster but less forgiving; fall back to genfromtxt.
    try:
        arr = np.loadtxt(path, delimiter=",", skiprows=1, dtype=np.float64)
    except Exception:
        arr = np.genfromtxt(path, delimiter=",", skip_header=1, dtype=np.float64)

    if arr.ndim == 1:
        arr = arr.reshape(1, -1)
    return arr


def _topk(pred: Dict[str, float], k: int) -> List[Tuple[str, float]]:
    return sorted(pred.items(), key=lambda kv: kv[1], reverse=True)[:k]


def _prediction_to_dict(spectrum_prediction) -> Dict[str, float]:
    # SpectrumPrediction has .isotopes list (possibly filtered). We want all.
    return {iso.name: float(iso.probability) for iso in spectrum_prediction.isotopes}


def _chain_scores(pred: Dict[str, float]) -> Dict[str, float]:
    from vega_ml.synthetic_spectra.ground_truth.decay_chains import DECAY_CHAINS

    scores: Dict[str, float] = {}
    for parent in ("U-238", "Th-232", "U-235"):
        chain = DECAY_CHAINS.get(parent)
        if not chain:
            continue
        # Prefer gamma emitters so the score isn't dominated by weak/non-gamma parents.
        members = chain.get_gamma_emitters() or chain.get_member_names()
        scores[f"chain:{parent}"] = float(sum(pred.get(name, 0.0) for name in members))

    # Key individual markers
    for name in ("U-238", "Th-232", "U-235", "Ra-226", "Pb-214", "Bi-214", "Tl-208", "Ac-228"):
        scores[f"iso:{name}"] = float(pred.get(name, 0.0))

    return scores


def _load_inference_engine(model_path: Path):
    checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)
    config = checkpoint.get("model_config", {})

    if isinstance(config, dict) and "num_time_intervals" in config:
        from vega_ml.inference.vega_portable_inference_2d import Vega2DInference

        return Vega2DInference(model_path=model_path)

    from vega_ml.inference.vega_inference import VegaInference

    return VegaInference(model_path=model_path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model",
        type=str,
        default="vega_ml/models/vega_2d_final.pt",
        help="Path to model checkpoint (.pt)",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.5,
        help="Probability threshold for 'present' isotopes",
    )
    parser.add_argument(
        "--topk",
        type=int,
        default=10,
        help="Show top-K isotopes (by probability)",
    )
    parser.add_argument(
        "--warp",
        action="store_true",
        help="Warp device spectra to Vega's expected linear 20–3000 keV axis",
    )
    parser.add_argument(
        "--calibration",
        type=str,
        default="vega_ml/client_app_device_data/rc110-calibration/RadiaCode Energy Calibration.csv",
        help="Path to RadiaCode calibration export CSV",
    )
    parser.add_argument(
        "--device-channel-offset",
        type=int,
        default=0,
        help="Offset applied when evaluating the device calibration polynomial (e.g., 1 if CSV channel_0 corresponds to device channel 1).",
    )
    parser.add_argument(
        "--detector",
        type=str,
        default="radiacode_110",
        help="Vega detector config key (controls the model energy-bin centers)",
    )
    parser.add_argument(
        "--per-row",
        action="store_true",
        help="Run inference per row (instead of mean over rows)",
    )
    parser.add_argument(
        "--collapse",
        type=str,
        default="none",
        choices=["none", "mean", "sum"],
        help="How to reduce 300x1023 CSV rows before inference. For 2D models, 'none' is closest to training (engine will pad/truncate).",
    )

    args = parser.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        raise FileNotFoundError(f"Model not found: {model_path}")

    inference = _load_inference_engine(model_path)

    device_cal = None
    energy_axis = None
    if args.warp:
        device_cal = load_radiacode_calibration_coeffs(args.calibration)
        energy_axis = vega_training_energy_axis_kev(detector_key=args.detector)

    print(f"Model: {model_path}")
    print(f"Warp enabled: {bool(args.warp)}")
    if args.warp:
        print(f"Calibration: a0={device_cal.a0}, a1={device_cal.a1}, a2={device_cal.a2}")

    for sample in DEFAULT_SAMPLES:
        if not sample.path.exists():
            print(f"\n[{sample.label}] missing file: {sample.path}")
            continue

        raw = _load_wide_numeric_csv(sample.path)
        if raw.shape[1] != 1023:
            print(f"\n[{sample.label}] unexpected channel count: {raw.shape}")
            continue

        data = raw
        if args.warp:
            data = warp_spectrogram_to_vega_energy_axis(
                raw,
                device_cal,
                vega_energy_axis=energy_axis,
                device_channel_offset=args.device_channel_offset,
            )

        if args.per_row:
            # Run on each row and average probabilities.
            probs_accum: Dict[str, float] = {}
            for row in data:
                pred = inference.predict(row, threshold=args.threshold, return_all=True)
                pred_dict = _prediction_to_dict(pred)
                for name, prob in pred_dict.items():
                    probs_accum[name] = probs_accum.get(name, 0.0) + prob

            pred_mean = {k: v / data.shape[0] for k, v in probs_accum.items()}
            top = _topk(pred_mean, args.topk)
            print(f"\n[{sample.label}] rows={data.shape[0]} (per-row avg)")
            for name, prob in top:
                print(f"  {prob:0.4f}  {name}")

            scores = _chain_scores(pred_mean)
            print(
                "  chain_scores  "
                f"chain:U-238={scores.get('chain:U-238', 0.0):0.3f}  "
                f"chain:Th-232={scores.get('chain:Th-232', 0.0):0.3f}  "
                f"chain:U-235={scores.get('chain:U-235', 0.0):0.3f}  "
                f"iso:Ra-226={scores.get('iso:Ra-226', 0.0):0.3f}"
            )
        else:
            # For 2D models, these CSVs are usually better treated as a bag of spectra.
            spectrum_for_model: np.ndarray
            if hasattr(inference, "model_config") and getattr(inference.model_config, "num_time_intervals", None):
                if args.collapse == "none":
                    spectrum_for_model = data
                    mode = "time (pad/truncate like training)"
                elif args.collapse == "mean":
                    spectrum_for_model = data.mean(axis=0)
                    mode = "mean→tile"
                else:
                    spectrum_for_model = data.sum(axis=0)
                    mode = "sum→tile"
            else:
                # 1D model can accept 2D and average; or we can pass 1D.
                if args.collapse == "none":
                    spectrum_for_model = data
                    mode = "engine default"
                elif args.collapse == "mean":
                    spectrum_for_model = data.mean(axis=0)
                    mode = "mean"
                else:
                    spectrum_for_model = data.sum(axis=0)
                    mode = "sum"

            pred = inference.predict(spectrum_for_model, threshold=args.threshold, return_all=True)
            pred_dict = _prediction_to_dict(pred)
            top = _topk(pred_dict, args.topk)
            print(f"\n[{sample.label}] rows={data.shape[0]} ({mode})")
            for name, prob in top:
                print(f"  {prob:0.4f}  {name}")

            scores = _chain_scores(pred_dict)
            print(
                "  chain_scores  "
                f"chain:U-238={scores.get('chain:U-238', 0.0):0.3f}  "
                f"chain:Th-232={scores.get('chain:Th-232', 0.0):0.3f}  "
                f"chain:U-235={scores.get('chain:U-235', 0.0):0.3f}  "
                f"iso:Ra-226={scores.get('iso:Ra-226', 0.0):0.3f}"
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
