#!/usr/bin/env python3
"""Evaluate the deployed Vega 2D model on device-exported CSV spectrograms.

This script is intended for quick, production-like debugging using the exact
CSV payload format (T x 1023) produced by the Android client.

It loads the deployed checkpoint (default: models/vega_2d_final.pt), runs the
same preprocessing as the API, and prints top-k isotopes.

Optionally, it also prints decay-chain rollups (U/Th) and shows raw vs
post-decay-inference probabilities for debugging.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np


def _load_csv_matrix(csv_path: Path) -> np.ndarray:
    # Header is: channel_0,channel_1,...channel_1022
    # Data rows are float values.
    return np.genfromtxt(csv_path, delimiter=",", skip_header=1, dtype=np.float32)


def _topk(names: List[str], probs: List[float], k: int) -> List[Tuple[str, float]]:
    idx = np.argsort(np.asarray(probs))[::-1][:k]
    return [(names[int(i)], float(probs[int(i)])) for i in idx]


@dataclass
class FileResult:
    file: str
    shape: Tuple[int, int]
    threshold: float
    topk_raw: List[Tuple[str, float]]
    topk_post_chain: List[Tuple[str, float]]
    selected: Dict[str, Dict[str, float]]


def _extract_selected(names: List[str], raw: List[float], post: List[float], selected: List[str]) -> Dict[str, Dict[str, float]]:
    name_to_idx = {n: i for i, n in enumerate(names)}
    out: Dict[str, Dict[str, float]] = {}
    for n in selected:
        i = name_to_idx.get(n)
        if i is None:
            continue
        out[n] = {"raw": float(raw[i]), "post_chain": float(post[i])}
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Run Vega 2D inference on device CSV spectrograms")
    parser.add_argument(
        "--model",
        default="models/vega_2d_final.pt",
        help="Path to deployed checkpoint (.pt). Relative to middleware/vega-isotope-identification by default.",
    )
    parser.add_argument(
        "--csv",
        nargs="+",
        required=True,
        help="One or more CSV files (each is T x 1023 with a header row).",
    )
    parser.add_argument("--device", default="cpu", help="Torch device (cpu/cuda)")
    parser.add_argument("--threshold", type=float, default=0.5, help="Detection threshold")
    parser.add_argument("--topk", type=int, default=15, help="How many top isotopes to print")
    parser.add_argument(
        "--out",
        default="artifacts/device_csv_eval_results.json",
        help="Optional JSON output path (relative to this folder). Set to empty string to disable.",
    )

    args = parser.parse_args()

    # Import here so the script can still parse args if deps are missing.
    from isotope_api import Vega2DInferenceEngine

    engine = Vega2DInferenceEngine()
    engine.initialize(str(Path(args.model)), args.device)

    results: List[FileResult] = []

    for csv in args.csv:
        csv_path = Path(csv)
        if not csv_path.exists():
            # Allow running from this folder with paths relative to repo root.
            csv_path = (Path(__file__).resolve().parent.parent.parent / csv).resolve()
        if not csv_path.exists():
            raise FileNotFoundError(f"CSV not found: {csv}")

        mat = _load_csv_matrix(csv_path)
        if mat.ndim != 2:
            raise ValueError(f"Expected 2D matrix from CSV, got shape {mat.shape} ({csv_path})")

        # Raw model probabilities (before decay-chain inference)
        tensor = engine.preprocess(mat, normalize=True)
        logits, activities = engine.model(tensor)
        raw_probs = np.squeeze(1 / (1 + np.exp(-logits.detach().cpu().numpy()))).tolist()

        # Post-chain probabilities (exactly what API uses)
        isotope_names = engine.isotope_index.isotope_names
        post_probs = engine._apply_decay_chain_inference(raw_probs, isotope_names, args.threshold)

        topk_raw = _topk(isotope_names, raw_probs, args.topk)
        topk_post = _topk(isotope_names, post_probs, args.topk)

        selected = _extract_selected(
            isotope_names,
            raw_probs,
            post_probs,
            selected=[
                "U-238",
                "U-235",
                "U-234",
                "Th-232",
                "Th-234",
                "Ra-226",
                "Ra-224",
                "Pb-214",
                "Bi-214",
                "Pb-212",
                "Tl-208",
                "Ac-228",
            ],
        )

        results.append(
            FileResult(
                file=str(csv_path.as_posix()),
                shape=(int(mat.shape[0]), int(mat.shape[1])),
                threshold=float(args.threshold),
                topk_raw=topk_raw,
                topk_post_chain=topk_post,
                selected=selected,
            )
        )

    for r in results:
        print("=" * 90)
        print(f"File: {r.file}")
        print(f"Shape: {r.shape[0]} x {r.shape[1]}")
        print(f"Threshold: {r.threshold}")
        print("\nTop-k (raw probs):")
        for name, p in r.topk_raw:
            print(f"  {name:>7s}  {p:.4f}")
        print("\nTop-k (post decay-chain inference):")
        for name, p in r.topk_post_chain:
            print(f"  {name:>7s}  {p:.4f}")

        if r.selected:
            print("\nSelected isotopes (raw -> post_chain):")
            for name, vals in r.selected.items():
                print(f"  {name:>7s}  {vals['raw']:.4f} -> {vals['post_chain']:.4f}")

    if args.out:
        out_path = (Path(__file__).resolve().parent / args.out).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        payload: Dict[str, Any] = {
            "model": str(Path(args.model)),
            "device": str(args.device),
            "threshold": float(args.threshold),
            "topk": int(args.topk),
            "results": [
                {
                    "file": r.file,
                    "shape": list(r.shape),
                    "topk_raw": r.topk_raw,
                    "topk_post_chain": r.topk_post_chain,
                    "selected": r.selected,
                }
                for r in results
            ],
        }
        out_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        print("=" * 90)
        print(f"Wrote: {out_path.as_posix()}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
