"""Build a reusable isotope presence index for v4/v3 datasets.

This scans only label metadata (NOT spectra) and writes an index:
- Output .npz: key = isotope name, value = array of dataset indices where present

Why: per-isotope binary training needs quick access to positive/negative samples.
Without this cache, we'd rescan labels for each isotope, which is slow on NAS.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

# Add project root to path (matches other training scripts)
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.dataset_2d import SpectrumDataset2D
from training.vega.isotope_index import get_default_isotope_index


def build_presence_index(
    *,
    data_dir: Path,
    out_path: Path,
    target_isotopes: Optional[List[str]],
    target_time_intervals: int,
) -> None:
    isotope_index = get_default_isotope_index()

    ds = SpectrumDataset2D(
        data_dir=data_dir,
        isotope_index=isotope_index,
        max_activity_bq=1000.0,
        target_time_intervals=target_time_intervals,
    )

    if target_isotopes is None or len(target_isotopes) == 0:
        isotopes = isotope_index.isotope_names
    else:
        isotopes = target_isotopes

    wanted = set(isotopes)
    for iso in wanted:
        isotope_index.name_to_index(iso)  # validate

    out_path.parent.mkdir(parents=True, exist_ok=True)

    buckets: Dict[str, List[int]] = {iso: [] for iso in isotopes}

    t0 = time.time()
    for idx, sample_id in enumerate(ds.sample_ids):
        meta = ds._load_sample_label(sample_id)  # pylint: disable=protected-access
        for iso in meta.get("isotopes", []):
            if iso in wanted:
                buckets[iso].append(idx)

        if (idx + 1) % 20000 == 0:
            elapsed = time.time() - t0
            rate = (idx + 1) / max(elapsed, 1e-9)
            print(f"Scanned {idx + 1:,}/{len(ds):,} labels ({rate:.1f}/s)")

    arrays = {iso: np.asarray(indices, dtype=np.int64) for iso, indices in buckets.items()}

    np.savez_compressed(out_path, **arrays)

    t1 = time.time()
    total_pos = sum(len(v) for v in buckets.values())
    print(f"Wrote: {out_path}")
    print(f"Isotopes: {len(arrays)}")
    print(f"Total positive assignments (sum over isotopes): {total_pos:,}")
    print(f"Elapsed: {t1 - t0:.1f}s")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build isotope presence index (.npz)")
    parser.add_argument("--data-dir", type=str, default="O:/master_data_collection/isotopev4")
    parser.add_argument("--out", type=str, default="artifacts/presence_index_isotopev4.npz")
    parser.add_argument(
        "--isotopes",
        type=str,
        default="",
        help="Comma-separated isotope names to include (default: all)",
    )
    parser.add_argument(
        "--time-intervals",
        type=int,
        default=300,
        help="Only used to initialize dataset; spectra are not loaded",
    )

    args = parser.parse_args()

    isotopes = [s.strip() for s in args.isotopes.split(",") if s.strip()]
    build_presence_index(
        data_dir=Path(args.data_dir),
        out_path=Path(args.out),
        target_isotopes=isotopes if len(isotopes) > 0 else None,
        target_time_intervals=int(args.time_intervals),
    )


if __name__ == "__main__":
    main()
