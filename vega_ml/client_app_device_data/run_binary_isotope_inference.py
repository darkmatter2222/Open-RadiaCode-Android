"""Run per-isotope *binary* model inference on RadiaCode device spectra.

This script:
- Loads one or more device CSV spectrograms (rows=time, cols=1023 channels)
- Translates (warps) device calibration to Vega training calibration using
  vega_ml/client_app_device_data/spectrum_translator.py
- Loads all trained per-isotope binary checkpoints from a directory
- Prints per-isotope probability ("confidence") and present/absent decision

Usage (from repo root):
  python -m vega_ml.client_app_device_data.run_binary_isotope_inference \
    --models-dir vega_ml/models/binary_isotope \
    --inputs vega_ml/client_app_device_data/Uranium-300-1023-rc110-0cm.csv

Or run a whole folder:
  python -m vega_ml.client_app_device_data.run_binary_isotope_inference \
    --models-dir vega_ml/models/binary_isotope \
    --input-dir vega_ml/client_app_device_data \
    --glob "*300-1023-rc110-0cm.csv"

Notes:
- Device spectra are max-normalized (like the training dataset).
- Threshold defaults to the tuned threshold saved in the model's *_meta.json
  if present; otherwise defaults to 0.5.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import numpy as np
import torch

from vega_ml.client_app_device_data.spectrum_translator import translate_spectrogram
from vega_ml.client_app_device_data.calibration_align import (
    load_radiacode_calibration_coeffs,
    vega_training_energy_axis_kev,
    warp_spectrogram_to_vega_energy_axis,
)


@dataclass(frozen=True)
class ModelSpec:
    isotope: str
    ckpt_path: Path
    meta_path: Optional[Path]


def _load_wide_numeric_csv(path: Path) -> np.ndarray:
    """Load a 1023-column spectra CSV produced for this repo."""

    try:
        arr = np.loadtxt(path, delimiter=",", skiprows=1, dtype=np.float64)
    except Exception:
        arr = np.genfromtxt(path, delimiter=",", skip_header=1, dtype=np.float64)

    if arr.ndim == 1:
        arr = arr.reshape(1, -1)

    return arr


def _pad_or_truncate_time(spectrogram: np.ndarray, target_time_intervals: int) -> np.ndarray:
    if spectrogram.ndim != 2:
        raise ValueError(f"Expected 2D spectrogram, got shape={spectrogram.shape}")

    current_time = int(spectrogram.shape[0])
    if current_time == target_time_intervals:
        return spectrogram

    if current_time > target_time_intervals:
        # Truncate: evenly spaced indices to preserve temporal coverage
        indices = np.linspace(0, current_time - 1, target_time_intervals, dtype=int)
        return spectrogram[indices, :]

    # Pad with zeros at end
    padded = np.zeros((target_time_intervals, spectrogram.shape[1]), dtype=spectrogram.dtype)
    padded[:current_time, :] = spectrogram
    return padded


def _max_normalize(spectrogram: np.ndarray) -> np.ndarray:
    max_val = float(np.max(spectrogram))
    if max_val > 0.0:
        return spectrogram / max_val
    return spectrogram


def _find_meta_for_ckpt(ckpt_path: Path) -> Optional[Path]:
    # train_binary_isotope.py writes:
    #   binary_<isotope>_meta.json
    # next to:
    #   binary_<isotope>.pt
    candidate = ckpt_path.with_name(ckpt_path.stem + "_meta.json")
    if candidate.exists():
        return candidate
    return None


def _parse_isotope_from_ckpt_name(ckpt_path: Path) -> str:
    # Expected: binary_U_238.pt
    name = ckpt_path.stem
    if name.startswith("binary_"):
        name = name[len("binary_") :]
    # reverse the training replacement '-' -> '_' for filenames
    # This is best-effort: "U_238" -> "U-238"
    return name.replace("_", "-")


def _load_models(models_dir: Path, pattern: str) -> List[ModelSpec]:
    ckpts = sorted(models_dir.glob(pattern))
    if not ckpts:
        raise FileNotFoundError(f"No checkpoints found in {models_dir} matching {pattern}")

    specs: List[ModelSpec] = []
    for ckpt in ckpts:
        isotope = _parse_isotope_from_ckpt_name(ckpt)
        specs.append(ModelSpec(isotope=isotope, ckpt_path=ckpt, meta_path=_find_meta_for_ckpt(ckpt)))
    return specs


def _load_threshold_from_meta(meta_path: Optional[Path]) -> Optional[float]:
    if meta_path is None or not meta_path.exists():
        return None

    try:
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
    except Exception:
        return None

    # Prefer tuned threshold if present
    tuned = meta.get("best_tuned_threshold")
    if tuned is not None:
        try:
            return float(tuned)
        except Exception:
            return None

    # Fall back to fixed threshold used during training
    t = meta.get("threshold")
    if t is not None:
        try:
            return float(t)
        except Exception:
            return None

    return None


def _iter_input_files(inputs: List[str], input_dir: Optional[str], glob_pat: str) -> List[Path]:
    files: List[Path] = []

    for p in inputs:
        files.append(Path(p))

    if input_dir:
        root = Path(input_dir)
        files.extend(sorted(root.glob(glob_pat)))

    # Dedup while preserving order
    seen: set[str] = set()
    out: List[Path] = []
    for p in files:
        key = str(p.resolve())
        if key in seen:
            continue
        seen.add(key)
        out.append(p)

    return out


def _load_binary_model(ckpt_path: Path, device: torch.device):
    # Import from the training script so we exactly match the architecture.
    from vega_ml.training.vega.train_binary_isotope import Binary2DConfig, TinyBinary2DCNN

    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
    cfg_dict = ckpt.get("config")
    if not isinstance(cfg_dict, dict):
        raise ValueError(f"Checkpoint missing config dict: {ckpt_path}")

    cfg = Binary2DConfig(**cfg_dict)
    model = TinyBinary2DCNN(cfg)
    state_dict = ckpt.get("state_dict")
    if not isinstance(state_dict, dict):
        raise ValueError(f"Checkpoint missing state_dict: {ckpt_path}")

    model.load_state_dict(state_dict, strict=True)
    model.eval()
    model.to(device)
    return model, cfg


@torch.inference_mode()
def _predict_one(model: torch.nn.Module, spectrogram: np.ndarray, device: torch.device) -> float:
    x = torch.tensor(spectrogram, dtype=torch.float32, device=device)
    x = x.unsqueeze(0)  # (1, T, C)
    logit = model(x)
    prob = torch.sigmoid(logit).detach().float().cpu().item()
    return float(prob)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run per-isotope binary inference on device CSVs")

    parser.add_argument(
        "--models-dir",
        type=str,
        required=True,
        help="Directory containing binary_*.pt checkpoints",
    )
    parser.add_argument(
        "--models-pattern",
        type=str,
        default="binary_*.pt",
        help="Glob pattern inside --models-dir (default: binary_*.pt)",
    )

    parser.add_argument(
        "--inputs",
        type=str,
        nargs="*",
        default=[],
        help="One or more CSV files to run (can also use --input-dir)",
    )
    parser.add_argument(
        "--input-dir",
        type=str,
        default="",
        help="Directory containing CSV files to run",
    )
    parser.add_argument(
        "--glob",
        type=str,
        default="*300-1023-rc110-0cm.csv",
        help="Glob pattern within --input-dir",
    )

    parser.add_argument(
        "--calibration",
        type=str,
        default="vega_ml/client_app_device_data/rc110-calibration/RadiaCode Energy Calibration.csv",
        help="Path to RadiaCode energy calibration export CSV (used with --warp)",
    )
    parser.add_argument(
        "--detector",
        type=str,
        default="radiacode_110",
        help="Vega detector key (used with --warp to select training energy-bin centers)",
    )
    parser.add_argument(
        "--device-channel-offset",
        type=int,
        default=0,
        help="Offset applied when evaluating device calibration polynomial (often 1 if CSV channel_0 maps to device channel 1)",
    )
    parser.add_argument(
        "--warp",
        action="store_true",
        help="Use calibration_align warp (count-conserving rebin) instead of spectrum_translator interpolation",
    )
    parser.add_argument(
        "--no-translate",
        action="store_true",
        help="Disable any device->training calibration translation/warping (not recommended)",
    )
    parser.add_argument(
        "--a0",
        type=float,
        default=3.5093544,
        help="Device calibration coefficient a0 (keV)",
    )
    parser.add_argument(
        "--a1",
        type=float,
        default=2.3624456,
        help="Device calibration coefficient a1 (keV/ch)",
    )
    parser.add_argument(
        "--a2",
        type=float,
        default=4.0645464e-4,
        help="Device calibration coefficient a2 (keV/ch^2)",
    )

    parser.add_argument(
        "--threshold",
        type=float,
        default=-1.0,
        help="Decision threshold; if <0, uses tuned threshold from *_meta.json when available",
    )
    parser.add_argument(
        "--topk",
        type=int,
        default=0,
        help="If >0, only print top-K probabilities per file",
    )

    args = parser.parse_args()

    models_dir = Path(args.models_dir)
    if not models_dir.exists():
        raise FileNotFoundError(f"models-dir not found: {models_dir}")

    input_dir = args.input_dir.strip() or None

    input_files = _iter_input_files(args.inputs, input_dir, args.glob)
    if not input_files:
        raise FileNotFoundError("No inputs provided. Use --inputs and/or --input-dir.")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}")
    if device.type == "cuda":
        print(f"GPU: {torch.cuda.get_device_name()}")

    specs = _load_models(models_dir, args.models_pattern)
    print(f"Loaded {len(specs)} model checkpoints from {models_dir}")

    # Load models once
    loaded: List[Tuple[ModelSpec, torch.nn.Module, object]] = []
    for spec in specs:
        model, cfg = _load_binary_model(spec.ckpt_path, device=device)
        loaded.append((spec, model, cfg))

    do_translate = not bool(args.no_translate)
    do_warp = bool(args.warp)
    if do_translate and do_warp:
        print(
            "Translation: warp mode enabled "
            f"(calibration={args.calibration}, detector={args.detector}, device_channel_offset={args.device_channel_offset})"
        )
    elif do_translate:
        print(f"Translation: translator enabled (a0={args.a0}, a1={args.a1}, a2={args.a2})")
    else:
        print("Translation: disabled")

    device_cal = None
    vega_axis = None
    if do_translate and do_warp:
        device_cal = load_radiacode_calibration_coeffs(args.calibration)
        vega_axis = vega_training_energy_axis_kev(detector_key=args.detector)

    for path in input_files:
        if not path.exists():
            print(f"\n[missing] {path}")
            continue

        raw = _load_wide_numeric_csv(path)
        if raw.shape[1] != 1023:
            print(f"\n[skip] {path} unexpected columns: shape={raw.shape}")
            continue

        spect = raw
        if do_translate and do_warp:
            spect = warp_spectrogram_to_vega_energy_axis(
                spect,
                device_cal,
                vega_energy_axis=vega_axis,
                device_channel_offset=int(args.device_channel_offset),
            )
        elif do_translate:
            spect = translate_spectrogram(spect, a0=args.a0, a1=args.a1, a2=args.a2)

        results: List[Tuple[str, float, float]] = []  # (isotope, prob, threshold_used)

        for spec, model, cfg in loaded:
            # cfg is Binary2DConfig; avoid importing type here.
            target_time = int(getattr(cfg, "num_time_intervals"))
            spect_i = _pad_or_truncate_time(spect, target_time_intervals=target_time)
            spect_i = _max_normalize(spect_i)

            prob = _predict_one(model, spect_i, device=device)

            thr_used: float
            if args.threshold >= 0.0:
                thr_used = float(args.threshold)
            else:
                thr_meta = _load_threshold_from_meta(spec.meta_path)
                thr_used = float(thr_meta) if thr_meta is not None else 0.5

            results.append((spec.isotope, prob, thr_used))

        # Sort by probability desc
        results.sort(key=lambda t: t[1], reverse=True)

        print(f"\n[{path}] rows={raw.shape[0]} translate={do_translate}")

        to_print = results
        if args.topk and args.topk > 0:
            to_print = results[: int(args.topk)]

        for isotope, prob, thr_used in to_print:
            present = prob >= thr_used
            print(f"  {prob:0.4f}  thr={thr_used:0.3f}  present={int(present)}  {isotope}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
