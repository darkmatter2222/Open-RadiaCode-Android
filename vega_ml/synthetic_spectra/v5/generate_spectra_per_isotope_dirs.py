"""Synthetic spectra generation (v5): per-isotope subdirectories, no blends.

Creates a v4-compatible dataset layout but stores samples under:
  <output_dir>/spectra/<class_name>/sXXXXXXXX.npy
  <output_dir>/spectra/<class_name>/sXXXXXXXX.lbl

Where <class_name> is either:
- an isotope name like "Ra-226" (no blending)
- "background" for empty-source samples

This keeps the existing compact v4 label format and metadata.json structure,
so existing loaders can read it (with recursive scan enabled).

Usage:
  python -m vega_ml.synthetic_spectra.v5.generate_spectra_per_isotope_dirs \
    --output-dir O:/master_data_collection/isotopev5 \
    --samples-per-isotope 10000 \
    --background-samples 10000 \
    --detector radiacode_110 \
    --workers 16

Notes:
- By default this only generates isotopes with at least one gamma line.
- By default it does NOT include daughters for the labeled source isotope.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import time
from datetime import datetime
from multiprocessing import Pool, cpu_count
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

# Add parent directory to path for imports (matches existing scripts)
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from synthetic_spectra.config import RADIACODE_CONFIGS
from synthetic_spectra.generator import IsotopeSource, SpectrumConfig, SpectrumGenerator
from synthetic_spectra.ground_truth import get_all_isotopes


# Build master list of all isotopes with gamma lines (same idea as v4)
_ALL_GAMMA_ISOTOPES = [iso for iso in get_all_isotopes() if len(iso.gamma_lines) > 0]
ISOTOPE_INDEX = {iso.name: i for i, iso in enumerate(_ALL_GAMMA_ISOTOPES)}
INDEX_TO_ISOTOPE = {i: iso.name for i, iso in enumerate(_ALL_GAMMA_ISOTOPES)}


def pack_labels(isotopes: List[str], activities: Dict[str, float], flags: Dict[str, bool]) -> bytes:
    """Pack labels into the compact v4 binary format."""

    data = bytearray()

    num_iso = len(isotopes)
    data.append(min(num_iso, 255))

    for iso in isotopes[:255]:
        idx = ISOTOPE_INDEX.get(iso, 0)
        data.append(idx)

    for iso in isotopes[:255]:
        activity = float(activities.get(iso, 0.0))
        scaled = int(min(activity, 1000.0) * 65.535)
        scaled = max(0, min(65535, scaled))
        data.extend(struct.pack("<H", scaled))

    flag_byte = 0
    if flags.get("normalized", True):
        flag_byte |= 0x01
    if flags.get("include_k40", True):
        flag_byte |= 0x02
    if flags.get("include_radon", True):
        flag_byte |= 0x04
    if flags.get("include_thorium", True):
        flag_byte |= 0x08
    data.append(flag_byte)

    return bytes(data)


def _sanitize_class_dir(name: str) -> str:
    # Keep it readable and filesystem-safe.
    # Hyphens are fine; avoid path separators.
    return name.replace("/", "_").replace("\\", "_")


def _generate_one(args: Tuple[int, Dict]) -> Optional[str]:
    """Worker entrypoint."""

    sample_idx, cfg = args
    try:
        rng = np.random.default_rng(int(cfg["base_seed"]) + int(sample_idx))

        detector_name = str(cfg["detector_name"])
        detector_config = RADIACODE_CONFIGS.get(detector_name)
        generator = SpectrumGenerator(detector_config=detector_config)

        class_name = str(cfg["class_name"])
        isotope_name = cfg.get("isotope_name")

        # Background toggles
        bg_cps = rng.uniform(float(cfg["bg_min"]), float(cfg["bg_max"])) * 5.0
        include_k40 = bool(rng.random() < float(cfg["p_k40"]))
        include_radon = bool(rng.random() < float(cfg["p_radon"]))
        include_thorium = bool(rng.random() < float(cfg["p_thorium"]))

        sources: List[IsotopeSource]
        if isotope_name is None:
            sources = []
        else:
            activity = rng.uniform(float(cfg["activity_min"]), float(cfg["activity_max"]))
            sources = [
                IsotopeSource(
                    str(isotope_name),
                    float(activity),
                    include_daughters=bool(cfg["include_daughters"]),
                )
            ]

        duration = float(cfg["duration_seconds"])
        spec_config = SpectrumConfig(
            duration_seconds=duration,
            time_interval_seconds=1.0,
            sources=sources,
            include_background=True,
            background_cps=float(bg_cps),
            include_k40=include_k40,
            include_radon=include_radon,
            include_thorium=include_thorium,
            detector_name=detector_name,
        )

        spectrum = generator.generate_spectrum(spec_config)

        output_dir = Path(cfg["output_dir"]) / "spectra" / _sanitize_class_dir(class_name)
        output_dir.mkdir(parents=True, exist_ok=True)

        sample_id = f"{sample_idx:08d}"
        stem = f"s{sample_id}"

        npy_path = output_dir / f"{stem}.npy"
        np.save(npy_path, spectrum.data.astype(np.float16))

        label_path = output_dir / f"{stem}.lbl"

        isotopes = spectrum.isotopes_present
        activities = {s.isotope_name: float(s.activity_bq) for s in sources}
        flags = {
            "normalized": True,
            "include_k40": include_k40,
            "include_radon": include_radon,
            "include_thorium": include_thorium,
        }

        label_data = pack_labels(isotopes, activities, flags)
        with open(label_path, "wb") as f:
            f.write(label_data)

        return stem

    except Exception as e:
        print(f"\nError generating sample {sample_idx}: {e}")
        import traceback

        traceback.print_exc()
        return None


def _list_target_isotopes(*, only_gamma_emitters: bool) -> List[str]:
    # Currently the generator database already filters by gamma_lines.
    names = [iso.name for iso in get_all_isotopes()]
    if only_gamma_emitters:
        names = [n for n in names if n in ISOTOPE_INDEX]
    return sorted(names)


def _parse_isotope_csv(value: str) -> List[str]:
    value = (value or "").strip()
    if not value:
        return []
    parts = [p.strip() for p in value.split(",")]
    return [p for p in parts if p]


def generate_per_isotope_dataset(
    *,
    output_dir: Path,
    detector_name: str,
    samples_per_isotope: int,
    background_samples: int,
    activity_range: Tuple[float, float],
    bg_range: Tuple[float, float],
    include_daughters: bool,
    only_gamma_emitters: bool,
    isotopes: Optional[List[str]],
    max_isotopes: int,
    workers: Optional[int],
    seed: int,
) -> None:
    if workers is None:
        workers = max(1, cpu_count() - 1)

    output_dir = Path(output_dir)
    (output_dir / "spectra").mkdir(parents=True, exist_ok=True)

    all_isotopes = _list_target_isotopes(only_gamma_emitters=only_gamma_emitters)
    if isotopes:
        unknown = [n for n in isotopes if n not in all_isotopes]
        if unknown:
            raise KeyError(
                "Requested isotopes not available for generation: "
                + ", ".join(unknown)
                + "."
            )
        all_isotopes = sorted(isotopes)
    if max_isotopes > 0:
        all_isotopes = all_isotopes[: int(max_isotopes)]

    isotopes = all_isotopes

    print("=" * 80)
    print("SYNTHETIC GENERATION v5: per-isotope subdirectories (no blends)")
    print("=" * 80)
    print(f"Output: {output_dir}")
    print(f"Detector: {detector_name}")
    print(f"Workers: {workers}")
    print(f"Samples per isotope: {samples_per_isotope}")
    print(f"Background samples: {background_samples}")
    print(f"Activity range (Bq): {activity_range[0]} - {activity_range[1]}")
    print(f"Background CPS range (base): {bg_range[0]} - {bg_range[1]}")
    print(f"Include daughters: {bool(include_daughters)}")
    print(f"Only gamma emitters: {bool(only_gamma_emitters)}")
    print(f"Isotopes to generate: {len(isotopes)}")

    # Metadata: keep v4-compatible keys, but bump version and add layout info.
    metadata = {
        "version": 5,
        "layout": "v4_labels_per_class_subdirs",
        "duration_seconds": 300,
        "time_intervals": 300,
        "channels": 1023,
        "energy_range_kev": [20, 3000],
        "detector": detector_name,
        "activity_range": list(activity_range),
        "dtype": "float16",
        "label_format": "binary",
        "isotope_index": ISOTOPE_INDEX,
        "generated_at": datetime.now().isoformat(),
        "samples_per_isotope": int(samples_per_isotope),
        "background_samples": int(background_samples),
        "include_daughters": bool(include_daughters),
        "only_gamma_emitters": bool(only_gamma_emitters),
        "classes": ["background"] + isotopes,
    }
    with open(output_dir / "metadata.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    # Generation order: background first, then each isotope.
    # For reproducibility and to avoid accidental cross-class collisions, use a per-class seed offset.
    class_jobs: List[Tuple[str, Optional[str], int]] = []
    if background_samples > 0:
        class_jobs.append(("background", None, background_samples))
    for iso in isotopes:
        class_jobs.append((iso, iso, samples_per_isotope))

    # Background composition probabilities.
    p_k40 = 0.95
    p_radon = 0.80
    p_thorium = 0.60

    start = time.time()
    total_done = 0
    total_fail = 0

    for class_idx, (class_name, isotope_name, n_samples) in enumerate(class_jobs):
        if n_samples <= 0:
            continue

        print(f"\n[{class_name}] generating {n_samples:,} samples...")

        shared_cfg = {
            "output_dir": str(output_dir),
            "detector_name": detector_name,
            "class_name": class_name,
            "isotope_name": isotope_name,
            "duration_seconds": 300.0,
            "activity_min": float(activity_range[0]),
            "activity_max": float(activity_range[1]),
            "bg_min": float(bg_range[0]),
            "bg_max": float(bg_range[1]),
            "include_daughters": bool(include_daughters),
            "p_k40": float(p_k40),
            "p_radon": float(p_radon),
            "p_thorium": float(p_thorium),
            "base_seed": int(seed) + 1000003 * int(class_idx),
        }

        work_items = [(i, shared_cfg) for i in range(int(n_samples))]

        done = 0
        fail = 0
        last_report = 0
        class_start = time.time()

        with Pool(int(workers)) as pool:
            for r in pool.imap_unordered(_generate_one, work_items, chunksize=100):
                if r is not None:
                    done += 1
                else:
                    fail += 1

                t = done + fail
                if n_samples <= 200 or (t - last_report) >= max(1, int(n_samples) // 100) or t == n_samples:
                    elapsed = time.time() - class_start
                    rate = done / elapsed if elapsed > 0 else 0.0
                    eta = (n_samples - t) / rate if rate > 0 else 0.0
                    print(
                        f"\r  Progress: {t:,}/{n_samples:,} ({100.0*t/max(1,n_samples):.1f}%)"
                        f" | Rate: {rate:.1f}/s | ETA: {eta/60.0:.1f}m | Failed: {fail}",
                        end="",
                        flush=True,
                    )
                    last_report = t

        print("")
        print(f"[{class_name}] done={done:,} failed={fail}")

        total_done += done
        total_fail += fail

    elapsed_total = time.time() - start
    print("\nGeneration complete")
    print(f"Total successful: {total_done:,}")
    print(f"Total failed: {total_fail}")
    print(f"Elapsed: {elapsed_total/60.0:.1f} minutes")
    if elapsed_total > 0:
        print(f"Overall rate: {total_done/elapsed_total:.2f} samples/sec")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate synthetic spectra into per-class subdirectories")
    parser.add_argument(
        "--output-dir",
        type=str,
        default="O:/master_data_collection/isotopev5",
        help="Output dataset directory",
    )
    parser.add_argument("--detector", type=str, default="radiacode_110")
    parser.add_argument("--samples-per-isotope", type=int, default=10000)
    parser.add_argument("--background-samples", type=int, default=10000)

    parser.add_argument("--activity-min", type=float, default=1.0)
    parser.add_argument("--activity-max", type=float, default=100.0)
    parser.add_argument("--bg-min", type=float, default=0.3)
    parser.add_argument("--bg-max", type=float, default=3.0)

    parser.add_argument(
        "--include-daughters",
        action="store_true",
        help="If set, include decay daughters for the labeled isotope source (can make labels less separable)",
    )
    parser.add_argument(
        "--only-gamma-emitters",
        action="store_true",
        help="If set, only generate isotopes that have gamma lines in the database (recommended)",
    )

    parser.add_argument(
        "--isotopes",
        type=str,
        default="",
        help="Optional comma-separated isotope list to generate (e.g. 'Bi-214,Pb-214,Ra-226'). If empty, generates all.",
    )
    parser.add_argument(
        "--max-isotopes",
        type=int,
        default=0,
        help="Optional cap on number of isotopes to generate (useful for smoke tests). 0 means no cap.",
    )

    parser.add_argument("--workers", type=int, default=None)
    parser.add_argument("--seed", type=int, default=42)

    args = parser.parse_args()

    generate_per_isotope_dataset(
        output_dir=Path(args.output_dir),
        detector_name=str(args.detector),
        samples_per_isotope=int(args.samples_per_isotope),
        background_samples=int(args.background_samples),
        activity_range=(float(args.activity_min), float(args.activity_max)),
        bg_range=(float(args.bg_min), float(args.bg_max)),
        include_daughters=bool(args.include_daughters),
        only_gamma_emitters=bool(args.only_gamma_emitters),
        isotopes=_parse_isotope_csv(str(args.isotopes)),
        max_isotopes=int(args.max_isotopes),
        workers=args.workers,
        seed=int(args.seed),
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
