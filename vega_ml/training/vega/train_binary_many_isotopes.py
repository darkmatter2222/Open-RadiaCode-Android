"""Train many tiny per-isotope binary models (no Optuna).

This is a thin runner around `train_binary_isotope.train_one`.

Recommended flow:
1) Build presence index once:
   python -m training.vega.build_presence_index --out artifacts/presence_index_isotopev4.npz
2) Train a set of isotopes:
   python -m training.vega.train_binary_many_isotopes --isotopes U-238,Ra-226,Th-232 --presence-index artifacts/presence_index_isotopev4.npz
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.isotope_index import get_default_isotope_index
from training.vega.train_binary_isotope import train_one
from training.vega.build_presence_index import build_presence_index


SUMMARY_FIELDS: List[str] = [
    "timestamp",
    "data_dir",
    "out_dir",
    "isotope",
    "status",
    "error",
    "seconds",
    "checkpoint",
    "meta",
    "best_epoch",
    "best_score",
    "best_f1",
    "best_tuned_threshold",
    # Best metrics at fixed threshold
    "best_loss",
    "best_acc",
    "best_precision",
    "best_recall",
    "best_f1_metric",
    "best_roc_auc",
    "best_avg_precision",
    "best_tp",
    "best_fp",
    "best_tn",
    "best_fn",
    "best_n",
    # Tuned metrics (threshold sweep)
    "tuned_threshold",
    "tuned_precision",
    "tuned_recall",
    "tuned_f1",
    "tuned_acc",
]


def parse_isotopes(isotopes_arg: str) -> List[str]:
    isotopes = [s.strip() for s in isotopes_arg.split(",") if s.strip()]
    return isotopes


def list_isotopes_from_dataset(data_dir: Path) -> List[str]:
    spectra_dir = Path(data_dir) / "spectra"
    if not spectra_dir.exists():
        return []
    isotopes: List[str] = []
    for child in sorted(spectra_dir.iterdir()):
        if not child.is_dir():
            continue
        if child.name.lower() == "background":
            continue
        isotopes.append(child.name)
    return isotopes


def _meta_path(out_dir: Path, isotope: str) -> Path:
    return out_dir / f"binary_{isotope.replace('-', '_')}_meta.json"


def _model_path(out_dir: Path, isotope: str) -> Path:
    return out_dir / f"binary_{isotope.replace('-', '_')}.pt"


def append_summary_row(summary_csv: Path, row: Dict[str, object]) -> None:
    summary_csv.parent.mkdir(parents=True, exist_ok=True)

    write_header = not summary_csv.exists()
    with open(summary_csv, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=SUMMARY_FIELDS, extrasaction="ignore")
        if write_header:
            writer.writeheader()

        out_row = {k: "" for k in SUMMARY_FIELDS}
        out_row.update(row)
        writer.writerow(out_row)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train many per-isotope binary models")
    parser.add_argument("--data-dir", type=str, default="O:/master_data_collection/isotopev5")
    parser.add_argument("--out-dir", type=str, default="models/binary_isotope_v5_micro")

    parser.add_argument(
        "--isotopes",
        type=str,
        default="",
        help="Comma-separated isotopes. If empty, uses --key-set.",
    )
    parser.add_argument(
        "--key-set",
        type=str,
        default="uranium",
        choices=["uranium", "radium_chain", "all", "dataset"],
        help="Convenience set when --isotopes is not provided",
    )

    parser.add_argument("--presence-index", type=str, default="", help=".npz/.json built by build_presence_index")
    parser.add_argument(
        "--build-presence-index",
        action="store_true",
        help="If set, builds --presence-index if missing (recommended for large datasets)",
    )

    parser.add_argument(
        "--summary-csv",
        type=str,
        default="",
        help="Append one row per isotope with best metrics (defaults to <out-dir>/summary.csv)",
    )
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="Skip isotopes that already have a checkpoint in --out-dir",
    )

    parser.add_argument("--time-intervals", type=int, default=300)
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-5)

    parser.add_argument("--num-workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)

    parser.add_argument("--max-train", type=int, default=50000)
    parser.add_argument("--max-val", type=int, default=10000)

    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--tune-threshold", action="store_true")
    parser.add_argument("--min-precision", type=float, default=0.50)

    parser.add_argument("--loss", type=str, default="bce", choices=["bce", "focal"])
    parser.add_argument("--focal-alpha", type=float, default=0.25)
    parser.add_argument("--focal-gamma", type=float, default=2.0)
    parser.add_argument("--pos-weight-mult", type=float, default=1.0)

    parser.add_argument("--steps-per-epoch", type=int, default=50)
    parser.add_argument("--val-batches", type=int, default=20)
    parser.add_argument("--eval-every", type=int, default=1)
    parser.add_argument("--threshold-grid-step", type=float, default=0.01)

    parser.add_argument(
        "--log-level",
        type=str,
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    parser.add_argument("--log-every", type=int, default=10)
    parser.add_argument("--profile-dataloader", action="store_true")
    parser.add_argument("--no-progress", action="store_true")
    parser.add_argument("--save-metric", type=str, default="tuned_recall", choices=["f1", "recall", "tuned_recall"])

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, str(args.log_level).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(message)s",
        stream=sys.stdout,
    )

    isotope_index = get_default_isotope_index()

    isotopes = parse_isotopes(args.isotopes)
    if len(isotopes) == 0:
        if args.key_set == "uranium":
            isotopes = ["U-238", "U-235"]
        elif args.key_set == "radium_chain":
            isotopes = ["Ra-226", "Pb-214", "Bi-214"]
        elif args.key_set == "all":
            isotopes = isotope_index.isotope_names
        elif args.key_set == "dataset":
            isotopes = list_isotopes_from_dataset(Path(args.data_dir))

    if len(isotopes) == 0:
        raise ValueError(
            "No isotopes selected. Provide --isotopes, or use --key-set dataset (requires data-dir/spectra/<isotope>/)."
        )

    # Validate names early (ignore unknown names if dataset contains extras)
    valid_isotopes: List[str] = []
    for iso in isotopes:
        try:
            isotope_index.name_to_index(iso)
            valid_isotopes.append(iso)
        except KeyError:
            logging.warning("Skipping unknown isotope name (not in isotope index): %s", iso)

    isotopes = valid_isotopes
    if len(isotopes) == 0:
        raise ValueError("No valid isotope names found after validation.")

    # Presence index: default path is based on data dir name.
    presence_index_path: Optional[Path]
    if args.presence_index:
        presence_index_path = Path(args.presence_index)
    else:
        presence_index_path = Path("artifacts") / f"presence_index_{Path(args.data_dir).name}.npz"

    if bool(args.build_presence_index):
        if not presence_index_path.exists():
            logging.info("Building presence index: %s", presence_index_path)
            build_presence_index(
                data_dir=Path(args.data_dir),
                out_path=presence_index_path,
                target_isotopes=None,
                target_time_intervals=int(args.time_intervals),
            )

    if not presence_index_path.exists():
        logging.warning(
            "Presence index not found (%s). Training will still work but will scan labels per isotope (slower).",
            presence_index_path,
        )
        presence_index_path = None

    summary_csv = Path(args.summary_csv) if args.summary_csv else (Path(args.out_dir) / "summary.csv")

    for iso in isotopes:
        print("=" * 80)
        print(f"Training binary model for {iso}")
        out_dir = Path(args.out_dir)

        if bool(args.skip_existing) and _model_path(out_dir, iso).exists():
            print(f"Skipping {iso}: checkpoint already exists")
            continue

        start = time.time()
        status = "ok"
        err = ""
        try:
            train_one(
                isotope_name=iso,
                data_dir=Path(args.data_dir),
                out_dir=out_dir,
            target_time_intervals=int(args.time_intervals),
            epochs=int(args.epochs),
            batch_size=int(args.batch_size),
            lr=float(args.lr),
            weight_decay=float(args.weight_decay),
            num_workers=int(args.num_workers),
            seed=int(args.seed),
            max_train=None if int(args.max_train) <= 0 else int(args.max_train),
            max_val=None if int(args.max_val) <= 0 else int(args.max_val),
            presence_index_path=presence_index_path,
            threshold=float(args.threshold),
            tune_threshold=bool(args.tune_threshold),
            min_precision=float(args.min_precision),
            loss_name=str(args.loss),
            focal_alpha=float(args.focal_alpha),
            focal_gamma=float(args.focal_gamma),
            pos_weight_mult=float(args.pos_weight_mult),
            steps_per_epoch=int(args.steps_per_epoch),
            val_batches=int(args.val_batches),
            eval_every=int(args.eval_every),
            threshold_grid_step=float(args.threshold_grid_step),
            log_every=int(args.log_every),
            profile_dataloader=bool(args.profile_dataloader),
            show_progress=not bool(args.no_progress),
            save_metric=str(args.save_metric),
            )
        except Exception as e:
            status = "error"
            err = str(e)
            logging.exception("Training failed for %s", iso)

        elapsed = time.time() - start

        meta_path = _meta_path(out_dir, iso)
        meta: Dict[str, object] = {}
        if meta_path.exists():
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except Exception as e:
                logging.warning("Failed to read meta for %s: %s", iso, e)

        best_metrics = meta.get("best_metrics") if isinstance(meta, dict) else None
        tuned_metrics = meta.get("best_tuned_metrics") if isinstance(meta, dict) else None

        row: Dict[str, object] = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "data_dir": str(Path(args.data_dir)),
            "out_dir": str(out_dir),
            "isotope": iso,
            "status": status,
            "error": err,
            "seconds": round(float(elapsed), 3),
            "checkpoint": str(_model_path(out_dir, iso)),
            "meta": str(meta_path),
            "best_epoch": meta.get("best_epoch") if isinstance(meta, dict) else None,
            "best_score": meta.get("best_score") if isinstance(meta, dict) else None,
            "best_f1": meta.get("best_f1") if isinstance(meta, dict) else None,
            "best_tuned_threshold": meta.get("best_tuned_threshold") if isinstance(meta, dict) else None,
        }

        if isinstance(best_metrics, dict):
            for k, v in best_metrics.items():
                if k == "f1":
                    row["best_f1"] = v
                else:
                    row[f"best_{k}"] = v

        if isinstance(tuned_metrics, dict):
            for k, v in tuned_metrics.items():
                row[f"tuned_{k}"] = v

        append_summary_row(summary_csv, row)
        print(f"Appended summary: {summary_csv}")


if __name__ == "__main__":
    main()
