import argparse
import json
import os
import random
import struct
import sys
import time
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import numpy as np
import torch

# Add vega_ml root to sys.path so imports like `training.vega...` work.
THIS_DIR = Path(__file__).resolve().parent
VEGA_ML_ROOT = THIS_DIR.parent.parent
sys.path.insert(0, str(VEGA_ML_ROOT))

from training.vega.model_2d import Vega2DConfig, Vega2DModel
from training.vega.isotope_index import get_default_isotope_index


@dataclass
class ExperimentConfig:
    data_dir: str
    model_path: str
    target_time_intervals: int = 300
    isotopes: int = 20
    ref_per_isotope: int = 200
    test_per_isotope: int = 100
    seed: int = 42
    batch_size: int = 64
    device: str = "auto"  # auto|cpu|cuda


def _set_seeds(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def _resolve_device(device: str) -> torch.device:
    if device == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(device)


def _load_v4_index_to_isotope(data_dir: Path) -> Dict[int, str]:
    metadata_path = data_dir / "metadata.json"
    if not metadata_path.exists():
        return {}
    with open(metadata_path, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    # Stored as {"Cs-137": 12, ...}
    mapping = metadata.get("isotope_index", {})
    return {int(v): k for k, v in mapping.items()}


def _scan_single_isotope_v4_samples(data_dir: Path) -> Dict[str, List[str]]:
    spectra_dir = data_dir / "spectra"
    if not spectra_dir.exists():
        raise FileNotFoundError(f"Missing spectra dir: {spectra_dir}")

    index_to_isotope = _load_v4_index_to_isotope(data_dir)

    by_isotope: Dict[str, List[str]] = defaultdict(list)
    lbl_paths = sorted(spectra_dir.glob("s*.lbl"))
    if not lbl_paths:
        raise FileNotFoundError(
            f"No v4 label files found under {spectra_dir}. Expected s*.lbl"
        )

    for lbl_path in lbl_paths:
        with open(lbl_path, "rb") as f:
            header = f.read(2)
        if len(header) < 2:
            continue
        num_iso = header[0]
        if num_iso != 1:
            continue
        iso_idx = header[1]
        iso_name = index_to_isotope.get(int(iso_idx), f"Unknown-{iso_idx}")
        if iso_name.startswith("Unknown-"):
            continue
        by_isotope[iso_name].append(lbl_path.stem)

    return by_isotope


def _choose_isotopes(
    by_isotope: Dict[str, List[str]],
    num_isotopes: int,
    per_isotope_needed: int,
) -> List[str]:
    eligible = [
        (iso, len(ids))
        for iso, ids in by_isotope.items()
        if len(ids) >= per_isotope_needed
    ]
    eligible.sort(key=lambda x: x[1], reverse=True)
    return [iso for iso, _ in eligible[:num_isotopes]]


def _pad_or_truncate(spectrum: np.ndarray, target_time: int) -> np.ndarray:
    if spectrum.ndim == 1:
        spectrum = spectrum.reshape(1, -1)

    current_time = spectrum.shape[0]
    if current_time == target_time:
        return spectrum

    if current_time > target_time:
        idx = np.linspace(0, current_time - 1, target_time, dtype=int)
        return spectrum[idx, :]

    padded = np.zeros((target_time, spectrum.shape[1]), dtype=spectrum.dtype)
    padded[:current_time, :] = spectrum
    return padded


def _load_v4_spectrum(data_dir: Path, sample_id: str, target_time: int) -> np.ndarray:
    spectrum_path = data_dir / "spectra" / f"{sample_id}.npy"
    spectrum = np.load(spectrum_path)
    if spectrum.dtype == np.float16:
        spectrum = spectrum.astype(np.float32)
    if spectrum.ndim == 1:
        spectrum = spectrum.reshape(1, -1)

    spectrum = _pad_or_truncate(spectrum, target_time)

    max_val = float(np.max(spectrum))
    if max_val > 0:
        spectrum = spectrum / max_val

    return spectrum.astype(np.float32)


def _iter_batches(items: List[Tuple[str, int]], batch_size: int) -> Iterable[List[Tuple[str, int]]]:
    for i in range(0, len(items), batch_size):
        yield items[i : i + batch_size]


@torch.no_grad()
def _embed_batch(model: Vega2DModel, batch: torch.Tensor) -> torch.Tensor:
    """Return embedding from the FC backbone (semantic vector)."""
    x = batch
    if x.dim() == 3:
        x = x.unsqueeze(1)  # (B,T,C) -> (B,1,T,C)

    for conv_block in model.conv_blocks:
        x = conv_block(x)

    x = x.view(x.size(0), -1)
    x = model.fc_backbone(x)
    return x


@torch.no_grad()
def _predict_topk(model: Vega2DModel, batch: torch.Tensor, k: int = 1) -> torch.Tensor:
    logits, _ = model(batch)
    probs = torch.sigmoid(logits)
    return torch.topk(probs, k=k, dim=1).indices


@torch.no_grad()
def _predict_topk_restricted(
    model: Vega2DModel,
    batch: torch.Tensor,
    selected_global_indices: List[int],
    k: int = 1,
) -> torch.Tensor:
    """Top-k prediction restricted to a subset of isotope indices.

    Returns indices in the restricted (0..len(selected)-1) class space.
    """
    logits, _ = model(batch)
    probs = torch.sigmoid(logits)
    probs_sel = probs[:, selected_global_indices]
    return torch.topk(probs_sel, k=k, dim=1).indices


def _accuracy_topk(y_true: np.ndarray, topk: np.ndarray, k: int) -> float:
    assert topk.shape[1] == k
    hits = 0
    for i in range(len(y_true)):
        if int(y_true[i]) in set(int(x) for x in topk[i]):
            hits += 1
    return hits / max(1, len(y_true))


def _confusion_matrix(y_true: np.ndarray, y_pred: np.ndarray, num_classes: int) -> np.ndarray:
    cm = np.zeros((num_classes, num_classes), dtype=np.int64)
    for t, p in zip(y_true, y_pred):
        cm[int(t), int(p)] += 1
    return cm


def _nearest_centroid_predict(
    query: torch.Tensor,
    centroids: torch.Tensor,
) -> torch.Tensor:
    # query: (N,D), centroids: (C,D)
    dists = torch.cdist(query, centroids)
    return torch.argmin(dists, dim=1)


def _nearest_neighbor_predict(
    query: torch.Tensor,
    ref: torch.Tensor,
    ref_labels: torch.Tensor,
    block: int = 1024,
) -> torch.Tensor:
    """Brute-force 1-NN with batching to avoid big allocations."""
    preds = []
    for i in range(0, query.size(0), block):
        q = query[i : i + block]
        d = torch.cdist(q, ref)
        nn = torch.argmin(d, dim=1)
        preds.append(ref_labels[nn])
    return torch.cat(preds, dim=0)


def main() -> int:
    parser = argparse.ArgumentParser(description="Semantic Isolation experiment")
    parser.add_argument("--data-dir", required=True, help="Path to v4 dataset (contains spectra/ + metadata.json)")
    parser.add_argument("--model", required=True, help="Path to Vega2D checkpoint .pt")
    parser.add_argument("--target-time", type=int, default=300)
    parser.add_argument("--isotopes", type=int, default=20)
    parser.add_argument("--ref-per-isotope", type=int, default=200)
    parser.add_argument("--test-per-isotope", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])

    args = parser.parse_args()
    cfg = ExperimentConfig(
        data_dir=args.data_dir,
        model_path=args.model,
        target_time_intervals=args.target_time,
        isotopes=args.isotopes,
        ref_per_isotope=args.ref_per_isotope,
        test_per_isotope=args.test_per_isotope,
        seed=args.seed,
        batch_size=args.batch_size,
        device=args.device,
    )

    _set_seeds(cfg.seed)

    data_dir = Path(cfg.data_dir)
    device = _resolve_device(cfg.device)

    artifacts_dir = THIS_DIR / "artifacts"
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    print("Scanning v4 dataset for single-isotope samples...")
    t0 = time.time()
    by_iso = _scan_single_isotope_v4_samples(data_dir)
    scan_s = time.time() - t0
    total_single = sum(len(v) for v in by_iso.values())
    print(f"  Found {total_single:,} single-isotope samples across {len(by_iso)} isotopes ({scan_s:.1f}s)")

    per_needed = cfg.ref_per_isotope + cfg.test_per_isotope
    chosen_isos = _choose_isotopes(by_iso, cfg.isotopes, per_needed)
    if len(chosen_isos) < cfg.isotopes:
        raise RuntimeError(
            f"Only {len(chosen_isos)} isotopes have >= {per_needed} samples. "
            f"Reduce --isotopes or per-isotope counts."
        )

    iso_to_class = {iso: i for i, iso in enumerate(chosen_isos)}

    # Map chosen isotope names into the model's global isotope index
    isotope_index = get_default_isotope_index()
    selected_global_indices: List[int] = []
    for iso in chosen_isos:
        try:
            selected_global_indices.append(isotope_index.name_to_index(iso))
        except KeyError as e:
            raise RuntimeError(f"Selected isotope not found in model index: {iso}") from e

    # Build ref/test id lists
    ref_items: List[Tuple[str, int]] = []
    test_items: List[Tuple[str, int]] = []

    rng = random.Random(cfg.seed)
    for iso in chosen_isos:
        ids = list(by_iso[iso])
        rng.shuffle(ids)
        ref_ids = ids[: cfg.ref_per_isotope]
        test_ids = ids[cfg.ref_per_isotope : cfg.ref_per_isotope + cfg.test_per_isotope]
        cls = iso_to_class[iso]
        ref_items.extend([(sid, cls) for sid in ref_ids])
        test_items.extend([(sid, cls) for sid in test_ids])

    rng.shuffle(ref_items)
    rng.shuffle(test_items)

    print(f"Selected {len(chosen_isos)} isotopes")
    print(f"Reference set: {len(ref_items):,} samples")
    print(f"Test set:      {len(test_items):,} samples")

    # Load model
    print(f"Loading Vega2D model from {cfg.model_path}")
    checkpoint = torch.load(cfg.model_path, map_location="cpu")

    if isinstance(checkpoint, dict) and "model_config" in checkpoint:
        model_cfg = Vega2DConfig(**checkpoint["model_config"])
        # The checkpoint was trained with a specific fixed time dimension.
        # For correctness, we must honor that shape when loading weights.
        if cfg.target_time_intervals != model_cfg.num_time_intervals:
            print(
                f"WARNING: --target-time={cfg.target_time_intervals} does not match "
                f"checkpoint num_time_intervals={model_cfg.num_time_intervals}. "
                f"Using {model_cfg.num_time_intervals} to match the model."
            )
            cfg.target_time_intervals = model_cfg.num_time_intervals
    else:
        model_cfg = Vega2DConfig(num_time_intervals=cfg.target_time_intervals)

    model = Vega2DModel(model_cfg)

    state_dict = checkpoint["model_state_dict"] if isinstance(checkpoint, dict) and "model_state_dict" in checkpoint else checkpoint
    model.load_state_dict(state_dict, strict=True)

    model.to(device)
    model.eval()

    # Embedding extraction
    print(f"Computing reference embeddings on {device}...")
    ref_embeddings = []
    ref_labels = []

    t1 = time.time()
    for batch_items in _iter_batches(ref_items, cfg.batch_size):
        spectra = [
            _load_v4_spectrum(data_dir, sid, cfg.target_time_intervals)
            for sid, _ in batch_items
        ]
        batch = torch.from_numpy(np.stack(spectra, axis=0)).to(device)
        emb = _embed_batch(model, batch).detach().cpu()
        ref_embeddings.append(emb)
        ref_labels.append(torch.tensor([cls for _, cls in batch_items], dtype=torch.long))

    ref_embeddings_t = torch.cat(ref_embeddings, dim=0)
    ref_labels_t = torch.cat(ref_labels, dim=0)
    ref_embed_s = time.time() - t1
    print(f"  Ref embeddings: {ref_embeddings_t.shape} ({ref_embed_s:.1f}s)")

    # Centroids in embedding space
    centroids = torch.zeros((len(chosen_isos), ref_embeddings_t.shape[1]), dtype=torch.float32)
    for iso, cls in iso_to_class.items():
        mask = ref_labels_t == cls
        centroids[cls] = ref_embeddings_t[mask].mean(dim=0)

    # Also compute raw time-mean centroids (1023-d)
    print("Computing raw (time-mean) centroids...")
    raw_centroids = torch.zeros((len(chosen_isos), model_cfg.num_channels), dtype=torch.float32)
    raw_counts = torch.zeros((len(chosen_isos),), dtype=torch.long)

    for sid, cls in ref_items:
        spec = _load_v4_spectrum(data_dir, sid, cfg.target_time_intervals)
        mean_spec = spec.mean(axis=0)
        raw_centroids[cls] += torch.from_numpy(mean_spec)
        raw_counts[cls] += 1

    for cls in range(len(chosen_isos)):
        raw_centroids[cls] /= max(1, int(raw_counts[cls]))

    # Evaluate on test set
    print("Evaluating on test set...")
    y_true = np.array([cls for _, cls in test_items], dtype=np.int64)

    all_top1 = []
    all_top5 = []
    test_embeddings = []
    raw_features = []

    t2 = time.time()
    for batch_items in _iter_batches(test_items, cfg.batch_size):
        spectra = [
            _load_v4_spectrum(data_dir, sid, cfg.target_time_intervals)
            for sid, _ in batch_items
        ]
        batch_np = np.stack(spectra, axis=0)
        batch = torch.from_numpy(batch_np).to(device)

        top1 = _predict_topk_restricted(model, batch, selected_global_indices, k=1).detach().cpu().numpy()
        top5 = _predict_topk_restricted(model, batch, selected_global_indices, k=5).detach().cpu().numpy()
        all_top1.append(top1)
        all_top5.append(top5)

        emb = _embed_batch(model, batch).detach().cpu()
        test_embeddings.append(emb)

        raw = torch.from_numpy(batch_np.mean(axis=1)).to(torch.float32)  # (B,1023)
        raw_features.append(raw)

    baseline_s = time.time() - t2

    top1_idx = np.vstack(all_top1)[:, 0]
    top5_idx = np.vstack(all_top5)

    baseline_top1 = _accuracy_topk(y_true, top1_idx.reshape(-1, 1), 1)
    baseline_top5 = _accuracy_topk(y_true, top5_idx, 5)

    test_embeddings_t = torch.cat(test_embeddings, dim=0)
    raw_features_t = torch.cat(raw_features, dim=0)

    # kNN in embedding space
    print("Running embedding 1-NN (Euclidean)...")
    t3 = time.time()
    pred_nn = _nearest_neighbor_predict(test_embeddings_t, ref_embeddings_t, ref_labels_t)
    nn_s = time.time() - t3

    # Nearest centroid in embedding space
    print("Running embedding centroid NN (Euclidean)...")
    t4 = time.time()
    pred_cent = _nearest_centroid_predict(test_embeddings_t, centroids)
    cent_s = time.time() - t4

    # Nearest centroid on raw time-mean spectrum
    print("Running raw centroid NN (Euclidean)...")
    t5 = time.time()
    pred_raw_cent = _nearest_centroid_predict(raw_features_t, raw_centroids)
    raw_cent_s = time.time() - t5

    pred_nn_np = pred_nn.numpy().astype(np.int64)
    pred_cent_np = pred_cent.numpy().astype(np.int64)
    pred_raw_cent_np = pred_raw_cent.numpy().astype(np.int64)

    nn_acc = float((pred_nn_np == y_true).mean())
    cent_acc = float((pred_cent_np == y_true).mean())
    raw_cent_acc = float((pred_raw_cent_np == y_true).mean())

    num_classes = len(chosen_isos)
    cm_baseline = _confusion_matrix(y_true, top1_idx, num_classes)
    cm_nn = _confusion_matrix(y_true, pred_nn_np, num_classes)
    cm_cent = _confusion_matrix(y_true, pred_cent_np, num_classes)
    cm_raw_cent = _confusion_matrix(y_true, pred_raw_cent_np, num_classes)

    results = {
        "config": asdict(cfg),
        "device": str(device),
        "dataset": {
            "single_isotope_samples_total": int(total_single),
            "isotopes_available": int(len(by_iso)),
            "isotopes_selected": chosen_isos,
            "ref_samples": int(len(ref_items)),
            "test_samples": int(len(test_items)),
        },
        "timings_s": {
            "scan": float(scan_s),
            "ref_embed": float(ref_embed_s),
            "baseline_eval": float(baseline_s),
            "knn_1nn": float(nn_s),
            "knn_centroid": float(cent_s),
            "knn_raw_centroid": float(raw_cent_s),
        },
        "metrics": {
            "baseline_top1": float(baseline_top1),
            "baseline_top5": float(baseline_top5),
            "embedding_1nn_top1": float(nn_acc),
            "embedding_centroid_top1": float(cent_acc),
            "raw_centroid_top1": float(raw_cent_acc),
        },
        "confusion_matrices": {
            "baseline_top1": cm_baseline.tolist(),
            "embedding_1nn": cm_nn.tolist(),
            "embedding_centroid": cm_cent.tolist(),
            "raw_centroid": cm_raw_cent.tolist(),
        },
    }

    results_path = artifacts_dir / "results.json"
    with open(results_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    # Write report
    report_path = THIS_DIR / "REPORT.md"
    _write_report(report_path, results)

    print(f"\nWrote results: {results_path}")
    print(f"Wrote report:  {report_path}")

    print("\nSummary")
    print(f"  Baseline top-1:          {baseline_top1:.4f}")
    print(f"  Baseline top-5:          {baseline_top5:.4f}")
    print(f"  Embedding 1-NN top-1:    {nn_acc:.4f}")
    print(f"  Embedding centroid top-1:{cent_acc:.4f}")
    print(f"  Raw centroid top-1:      {raw_cent_acc:.4f}")

    return 0


def _write_report(report_path: Path, results: Dict) -> None:
    cfg = results["config"]
    ds = results["dataset"]
    tm = results["timings_s"]
    m = results["metrics"]

    isotopes_selected = ds["isotopes_selected"]

    lines = []
    lines.append("# Semantic Isolation Experiment Report")
    lines.append("")
    lines.append("## Setup")
    lines.append("")
    lines.append(f"- Date: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Device: `{results['device']}`")
    lines.append(f"- Data dir: `{cfg['data_dir']}`")
    lines.append(f"- Model: `{cfg['model_path']}`")
    lines.append(f"- Target time intervals: `{cfg['target_time_intervals']}`")
    lines.append("")

    lines.append("## Dataset")
    lines.append("")
    lines.append(f"- Single-isotope samples found: `{ds['single_isotope_samples_total']:,}`")
    lines.append(f"- Isotopes with any single-isotope samples: `{ds['isotopes_available']}`")
    lines.append(f"- Isotopes selected: `{len(isotopes_selected)}`")
    lines.append(f"- Reference samples: `{ds['ref_samples']:,}`")
    lines.append(f"- Test samples: `{ds['test_samples']:,}`")
    lines.append("")
    lines.append("Selected isotopes (class order used in confusion matrices):")
    for i, iso in enumerate(isotopes_selected):
        lines.append(f"- {i:02d}: {iso}")
    lines.append("")

    lines.append("## Methods Compared")
    lines.append("")
    lines.append("- Baseline: Vega2D classifier head (top-1 by sigmoid probability)")
    lines.append("- Embedding 1-NN: Euclidean nearest neighbor over Vega2D FC-backbone embeddings")
    lines.append("- Embedding centroid: Euclidean nearest class centroid in embedding space")
    lines.append("- Raw centroid: Euclidean nearest class centroid over time-mean 1023-channel spectrum")
    lines.append("")

    lines.append("## Results")
    lines.append("")
    lines.append("Accuracy (single-label, top-1 unless noted):")
    lines.append("")
    lines.append(f"- Baseline top-1: `{m['baseline_top1']:.4f}`")
    lines.append(f"- Baseline top-5: `{m['baseline_top5']:.4f}`")
    lines.append(f"- Embedding 1-NN top-1: `{m['embedding_1nn_top1']:.4f}`")
    lines.append(f"- Embedding centroid top-1: `{m['embedding_centroid_top1']:.4f}`")
    lines.append(f"- Raw centroid top-1: `{m['raw_centroid_top1']:.4f}`")
    lines.append("")

    lines.append("## Timings")
    lines.append("")
    for k, v in tm.items():
        lines.append(f"- {k}: `{v:.2f}s`")
    lines.append("")

    lines.append("## Notes / Interpretation")
    lines.append("")
    lines.append("- If embedding kNN beats baseline, it suggests the backbone learns a metric space that is more separable than the classifier head for this single-isotope regime.")
    lines.append("- If baseline beats embedding kNN, it suggests the classifier head is well-aligned to the task (or the embedding is not Euclidean-friendly without additional metric learning).")
    lines.append("- If raw centroid is competitive, a lot of separability may be explained by simple spectral shape averages in this dataset.")
    lines.append("")

    lines.append("## Artifacts")
    lines.append("")
    lines.append("- `artifacts/results.json` contains full metrics and confusion matrices.")

    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
