"""Binary (per-isotope) training for 2D spectra.

Goal: test the hypothesis that training a tiny model per isotope (present/absent)
can outperform a single multi-label model for difficult isotopes (e.g., uranium).

- Input: 2D spectrum (time x channels), e.g. (300, 1023)
- Output: single logit -> sigmoid -> probability isotope present

This script uses the existing v4 dataset layout (spectra/*.npy + s*.lbl).
"""

from __future__ import annotations

import argparse
import json
import itertools
import logging
import os
import random
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset, Subset, WeightedRandomSampler

# Add project root to path (matches other training scripts)
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.dataset_2d import SpectrumDataset2D
from training.vega.isotope_index import get_default_isotope_index

try:
    from tqdm.auto import tqdm  # type: ignore
except Exception:  # pragma: no cover
    tqdm = None


logger = logging.getLogger(__name__)


def _rankdata_average_ties(scores: np.ndarray) -> np.ndarray:
    """Compute 1-based ranks with average ranks for ties.

    Minimal, dependency-free equivalent of scipy.stats.rankdata(method='average').
    """

    n = int(scores.size)
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(n, dtype=np.float64)

    i = 0
    while i < n:
        j = i
        # Find run of equal scores in sorted order
        while j + 1 < n and scores[order[j + 1]] == scores[order[i]]:
            j += 1

        # Average rank for positions i..j (1-based ranks)
        avg_rank = (i + 1 + j + 1) / 2.0
        ranks[order[i : j + 1]] = avg_rank
        i = j + 1

    return ranks


def _roc_auc(y_true: np.ndarray, y_score: np.ndarray) -> float:
    """Binary ROC AUC via Mann–Whitney U statistic (tie-aware).

    Returns NaN if only one class is present.
    """

    y_true = np.asarray(y_true, dtype=np.int32).reshape(-1)
    y_score = np.asarray(y_score, dtype=np.float64).reshape(-1)

    if y_true.size == 0:
        return float("nan")

    n_pos = int(np.sum(y_true == 1))
    n_neg = int(np.sum(y_true == 0))
    if n_pos == 0 or n_neg == 0:
        return float("nan")

    ranks = _rankdata_average_ties(y_score)
    rank_sum_pos = float(np.sum(ranks[y_true == 1]))
    auc = (rank_sum_pos - (n_pos * (n_pos + 1) / 2.0)) / float(n_pos * n_neg)
    return float(auc)


def _average_precision(y_true: np.ndarray, y_score: np.ndarray) -> float:
    """Binary average precision (area under precision-recall curve).

    Returns NaN if there are no positive samples.
    """

    y_true = np.asarray(y_true, dtype=np.int32).reshape(-1)
    y_score = np.asarray(y_score, dtype=np.float64).reshape(-1)

    n_pos = int(np.sum(y_true == 1))
    if y_true.size == 0 or n_pos == 0:
        return float("nan")

    order = np.argsort(-y_score, kind="mergesort")
    y_sorted = y_true[order]

    tp_cum = np.cumsum(y_sorted == 1)
    fp_cum = np.cumsum(y_sorted == 0)
    precision = tp_cum / np.maximum(tp_cum + fp_cum, 1)

    # AP is average of precision at each true positive position
    ap = float(np.sum(precision[y_sorted == 1]) / max(n_pos, 1))
    return ap


# -----------------------------
# Model
# -----------------------------


@dataclass
class Binary2DConfig:
    num_channels: int = 1023
    num_time_intervals: int = 300

    # Extremely tiny CNN
    conv1_channels: int = 4
    conv2_channels: int = 8
    conv3_channels: int = 16

    kernel_size: Tuple[int, int] = (3, 3)
    pool1: Tuple[int, int] = (5, 5)
    pool2: Tuple[int, int] = (5, 5)
    pool3: Tuple[int, int] = (4, 8)

    # Classification head (2 hidden layers)
    fc1_dim: int = 64
    fc2_dim: int = 32
    dropout: float = 0.2


class TinyBinary2DCNN(nn.Module):
    def __init__(self, cfg: Binary2DConfig):
        super().__init__()
        self.cfg = cfg

        pad = (cfg.kernel_size[0] // 2, cfg.kernel_size[1] // 2)

        self.conv1 = nn.Conv2d(1, cfg.conv1_channels, cfg.kernel_size, padding=pad)
        self.bn1 = nn.BatchNorm2d(cfg.conv1_channels)
        self.pool1 = nn.MaxPool2d(cfg.pool1)

        self.conv2 = nn.Conv2d(cfg.conv1_channels, cfg.conv2_channels, cfg.kernel_size, padding=pad)
        self.bn2 = nn.BatchNorm2d(cfg.conv2_channels)
        self.pool2 = nn.MaxPool2d(cfg.pool2)

        self.conv3 = nn.Conv2d(cfg.conv2_channels, cfg.conv3_channels, cfg.kernel_size, padding=pad)
        self.bn3 = nn.BatchNorm2d(cfg.conv3_channels)
        self.pool3 = nn.MaxPool2d(cfg.pool3)

        self.act = nn.ReLU(inplace=True)
        self.dropout2d = nn.Dropout2d(cfg.dropout)

        self.gap = nn.AdaptiveAvgPool2d(1)

        # Two-layer classification head for more capacity
        self.fc1 = nn.Linear(cfg.conv3_channels, cfg.fc1_dim)
        self.fc1_bn = nn.BatchNorm1d(cfg.fc1_dim)
        self.fc2 = nn.Linear(cfg.fc1_dim, cfg.fc2_dim)
        self.fc2_bn = nn.BatchNorm1d(cfg.fc2_dim)
        self.fc_drop = nn.Dropout(cfg.dropout)

        self.out = nn.Linear(cfg.fc2_dim, 1)

        self._init_weights()

    def _init_weights(self) -> None:
        for m in self.modules():
            if isinstance(m, (nn.Conv2d, nn.Linear)):
                nn.init.kaiming_normal_(m.weight, mode="fan_out", nonlinearity="relu")
                if getattr(m, "bias", None) is not None:
                    nn.init.constant_(m.bias, 0)
            elif isinstance(m, (nn.BatchNorm1d, nn.BatchNorm2d)):
                nn.init.constant_(m.weight, 1)
                nn.init.constant_(m.bias, 0)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # (B, T, C) -> (B, 1, T, C)
        if x.dim() == 3:
            x = x.unsqueeze(1)

        x = self.pool1(self.act(self.bn1(self.conv1(x))))
        x = self.dropout2d(x)

        x = self.pool2(self.act(self.bn2(self.conv2(x))))
        x = self.dropout2d(x)

        x = self.pool3(self.act(self.bn3(self.conv3(x))))
        x = self.dropout2d(x)

        x = self.gap(x).view(x.size(0), -1)
        x = self.fc_drop(self.act(self.fc1_bn(self.fc1(x))))
        x = self.fc_drop(self.act(self.fc2_bn(self.fc2(x))))
        logit = self.out(x).squeeze(-1)
        return logit


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


class FocalLoss(nn.Module):
    def __init__(self, alpha: float = 0.25, gamma: float = 2.0, reduction: str = "mean"):
        super().__init__()
        self.alpha = alpha
        self.gamma = gamma
        self.reduction = reduction

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        bce = nn.functional.binary_cross_entropy_with_logits(logits, targets, reduction="none")
        probs = torch.sigmoid(logits)
        p_t = probs * targets + (1.0 - probs) * (1.0 - targets)
        alpha_t = self.alpha * targets + (1.0 - self.alpha) * (1.0 - targets)
        loss = alpha_t * (1.0 - p_t).pow(self.gamma) * bce
        if self.reduction == "sum":
            return loss.sum()
        if self.reduction == "none":
            return loss
        return loss.mean()


def load_presence_index(path: Path) -> Dict[str, np.ndarray]:
    """Load an isotope->indices presence index.

    Supported formats:
    - .npz: arrays keyed by isotope name
    - .json: dict[str, list[int]]
    """

    if not path.exists():
        raise FileNotFoundError(f"Presence index not found: {path}")

    if path.suffix.lower() == ".npz":
        data = np.load(path, allow_pickle=False)
        return {k: np.asarray(data[k], dtype=np.int64) for k in data.files}

    if path.suffix.lower() == ".json":
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
        return {k: np.asarray(v, dtype=np.int64) for k, v in raw.items()}

    raise ValueError(f"Unsupported presence index format: {path.suffix}")


# -----------------------------
# Dataset wrapper
# -----------------------------


class BinaryIsotopeDataset(Dataset):
    def __init__(self, subset: Subset, target_index: int):
        self.subset = subset
        self.target_index = target_index

    def __len__(self) -> int:
        return len(self.subset)

    def __getitem__(self, i: int) -> Dict[str, torch.Tensor]:
        item = self.subset[i]
        # item['presence_labels'] is (num_isotopes,)
        y = item["presence_labels"][self.target_index]
        return {
            "spectrum": item["spectrum"],
            "label": y,
        }


def collate_binary(batch: List[Dict[str, torch.Tensor]]) -> Dict[str, torch.Tensor]:
    return {
        "spectrum": torch.stack([b["spectrum"] for b in batch]),
        "label": torch.stack([b["label"] for b in batch]).float(),
    }


def _presence_flags_for_subset(
    subset: Subset,
    *,
    isotope_name: str,
    target_index: int,
    presence_index: Optional[Dict[str, np.ndarray]] = None,
) -> np.ndarray:
    """Compute boolean labels for a Subset without loading spectra.

    If `presence_index` is provided, we compute flags by set membership.
    Otherwise we read only label files via the underlying dataset.
    """

    indices: np.ndarray = np.asarray(list(subset.indices), dtype=np.int64)  # type: ignore[attr-defined]

    if presence_index is not None:
        pos_indices = presence_index.get(isotope_name)
        if pos_indices is None:
            raise KeyError(
                f"Isotope '{isotope_name}' not found in presence index. "
                f"Presence index contains: {sorted(list(presence_index.keys()))[:10]}..."
            )
        flags = np.isin(indices, pos_indices, assume_unique=False).astype(np.uint8)
        return flags

    base: SpectrumDataset2D = subset.dataset  # type: ignore[assignment]
    flags = np.zeros(len(indices), dtype=np.uint8)

    last_log = time.time()
    for j, base_idx in enumerate(indices.tolist()):
        sample_id = base.sample_ids[int(base_idx)]
        meta = base._load_sample_label(sample_id)  # pylint: disable=protected-access
        present = 0
        for iso in meta.get("isotopes", []):
            try:
                if base.isotope_index.name_to_index(iso) == target_index:
                    present = 1
                    break
            except KeyError:
                continue
        flags[j] = present

        # Throttled progress logging for label scan.
        if (j + 1) % 10000 == 0:
            now = time.time()
            if now - last_log >= 0.5:
                logger.info("Label scan: %d/%d (%.1f%%)", j + 1, len(indices), 100.0 * (j + 1) / max(len(indices), 1))
                last_log = now

    return flags


def make_balanced_sampler(flags: np.ndarray, seed: int) -> Tuple[WeightedRandomSampler, float, float]:
    """Create a sampler that approximately balances pos/neg.

    Returns sampler and (pos_frac, pos_weight).
    """

    pos = float(flags.sum())
    n = float(len(flags))
    neg = n - pos

    pos_frac = (pos / n) if n > 0 else 0.0

    # BCEWithLogitsLoss pos_weight is weight for positive examples.
    # Common choice: neg/pos. Clamp to avoid inf.
    pos_weight = (neg / max(pos, 1.0))

    # Per-sample weights for WeightedRandomSampler.
    w_pos = 0.5 / max(pos, 1.0)
    w_neg = 0.5 / max(neg, 1.0)
    weights = torch.tensor(np.where(flags == 1, w_pos, w_neg), dtype=torch.double)

    g = torch.Generator()
    g.manual_seed(seed)

    sampler = WeightedRandomSampler(
        weights=weights,
        num_samples=len(weights),
        replacement=True,
        generator=g,
    )

    return sampler, pos_frac, pos_weight


# -----------------------------
# Train / Eval
# -----------------------------


@torch.no_grad()
def evaluate(model: nn.Module, loader: DataLoader, device: torch.device, threshold: float = 0.5) -> Dict[str, float]:
    model.eval()

    total = 0
    tp = fp = tn = fn = 0
    loss_sum = 0.0

    bce = nn.BCEWithLogitsLoss(reduction="sum")

    probs_list: List[np.ndarray] = []
    y_list: List[np.ndarray] = []

    for batch in loader:
        x = batch["spectrum"].to(device, non_blocking=True)
        y = batch["label"].to(device, non_blocking=True)

        logits = model(x)
        loss_sum += float(bce(logits, y).item())

        probs = torch.sigmoid(logits)
        pred = (probs >= threshold).float()

        probs_list.append(probs.detach().float().cpu().numpy().reshape(-1))
        y_list.append(y.detach().float().cpu().numpy().reshape(-1))

        total += y.numel()
        tp += int(((pred == 1) & (y == 1)).sum().item())
        fp += int(((pred == 1) & (y == 0)).sum().item())
        tn += int(((pred == 0) & (y == 0)).sum().item())
        fn += int(((pred == 0) & (y == 1)).sum().item())

    precision = tp / max(tp + fp, 1)
    recall = tp / max(tp + fn, 1)
    f1 = (2 * precision * recall) / max(precision + recall, 1e-12)
    acc = (tp + tn) / max(total, 1)

    probs_all = np.concatenate(probs_list, axis=0) if len(probs_list) > 0 else np.asarray([], dtype=np.float64)
    y_all = np.concatenate(y_list, axis=0) if len(y_list) > 0 else np.asarray([], dtype=np.float64)
    y_bin = (y_all >= 0.5).astype(np.int32)

    roc_auc = _roc_auc(y_bin, probs_all)
    avg_precision = _average_precision(y_bin, probs_all)

    return {
        "loss": loss_sum / max(total, 1),
        "acc": acc,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "roc_auc": float(roc_auc),
        "avg_precision": float(avg_precision),
        "tp": float(tp),
        "fp": float(fp),
        "tn": float(tn),
        "fn": float(fn),
        "n": float(total),
    }


@torch.no_grad()
def evaluate_limited(
    model: nn.Module,
    loader: DataLoader,
    device: torch.device,
    *,
    threshold: float,
    max_batches: int,
) -> Dict[str, float]:
    """Evaluate on at most max_batches batches (speed knob).

    If max_batches <= 0, evaluates full loader.
    """

    if max_batches <= 0:
        return evaluate(model, loader, device=device, threshold=threshold)

    model.eval()

    total = 0
    tp = fp = tn = fn = 0
    loss_sum = 0.0

    bce = nn.BCEWithLogitsLoss(reduction="sum")

    probs_list: List[np.ndarray] = []
    y_list: List[np.ndarray] = []

    for b_idx, batch in enumerate(loader):
        if b_idx >= max_batches:
            break

        x = batch["spectrum"].to(device, non_blocking=True)
        y = batch["label"].to(device, non_blocking=True)

        logits = model(x)
        loss_sum += float(bce(logits, y).item())

        probs = torch.sigmoid(logits)
        pred = (probs >= threshold).float()

        probs_list.append(probs.detach().float().cpu().numpy().reshape(-1))
        y_list.append(y.detach().float().cpu().numpy().reshape(-1))

        total += y.numel()
        tp += int(((pred == 1) & (y == 1)).sum().item())
        fp += int(((pred == 1) & (y == 0)).sum().item())
        tn += int(((pred == 0) & (y == 0)).sum().item())
        fn += int(((pred == 0) & (y == 1)).sum().item())

    precision = tp / max(tp + fp, 1)
    recall = tp / max(tp + fn, 1)
    f1 = (2 * precision * recall) / max(precision + recall, 1e-12)
    acc = (tp + tn) / max(total, 1)

    probs_all = np.concatenate(probs_list, axis=0) if len(probs_list) > 0 else np.asarray([], dtype=np.float64)
    y_all = np.concatenate(y_list, axis=0) if len(y_list) > 0 else np.asarray([], dtype=np.float64)
    y_bin = (y_all >= 0.5).astype(np.int32)

    roc_auc = _roc_auc(y_bin, probs_all)
    avg_precision = _average_precision(y_bin, probs_all)

    return {
        "loss": loss_sum / max(total, 1),
        "acc": acc,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "roc_auc": float(roc_auc),
        "avg_precision": float(avg_precision),
        "tp": float(tp),
        "fp": float(fp),
        "tn": float(tn),
        "fn": float(fn),
        "n": float(total),
    }


@torch.no_grad()
def evaluate_threshold_sweep(
    model: nn.Module,
    loader: DataLoader,
    device: torch.device,
    *,
    thresholds: List[float],
    min_precision: float,
    max_batches: int,
) -> Dict[str, float]:
    """Pick a threshold that maximizes recall subject to a minimum precision.

    IMPORTANT: This implementation is optimized to avoid re-running the model for
    each threshold. It runs the model once to collect logits/labels, then sweeps
    thresholds in-memory.
    """

    collect_start = time.time()
    model.eval()

    logits_list: List[torch.Tensor] = []
    y_list: List[torch.Tensor] = []

    # Collect a fixed amount of validation data for fast threshold sweeps.
    for b_idx, batch in enumerate(loader):
        if max_batches > 0 and b_idx >= max_batches:
            break

        x = batch["spectrum"].to(device, non_blocking=True)
        y = batch["label"].to(device, non_blocking=True)
        logits = model(x)

        logits_list.append(logits.detach().float().cpu())
        y_list.append(y.detach().float().cpu())

    if len(logits_list) == 0:
        return {"threshold": 0.5, "recall": 0.0, "precision": 0.0, "f1": 0.0, "acc": 0.0}

    logits_all = torch.cat(logits_list, dim=0)
    y_all = torch.cat(y_list, dim=0)

    probs = torch.sigmoid(logits_all)
    y_bool = (y_all >= 0.5)

    logger.info(
        "Threshold sweep: collected %d samples in %.2fs",
        int(y_all.numel()),
        time.time() - collect_start,
    )

    sweep_start = time.time()

    # Vectorized sweep: compare probs (N,) against thresholds (T,) -> (T,N)
    t = torch.tensor(thresholds, dtype=probs.dtype).view(-1, 1)
    pred = probs.view(1, -1) >= t

    y_pos = y_bool.view(1, -1)
    y_neg = (~y_bool).view(1, -1)

    tp = (pred & y_pos).sum(dim=1).float()
    fp = (pred & y_neg).sum(dim=1).float()
    fn = ((~pred) & y_pos).sum(dim=1).float()
    tn = ((~pred) & y_neg).sum(dim=1).float()

    precision = tp / torch.clamp(tp + fp, min=1.0)
    recall = tp / torch.clamp(tp + fn, min=1.0)
    f1 = (2.0 * precision * recall) / torch.clamp(precision + recall, min=1e-12)
    acc = (tp + tn) / torch.clamp(tp + tn + fp + fn, min=1.0)

    # Apply min precision constraint.
    # If none pass, DO NOT fall back to max recall (that tends to pick very low thresholds
    # and creates "always positive" detectors). Instead, fall back to a conservative
    # threshold near 0.5.
    ok = precision >= float(min_precision)
    constraint_met = bool(ok.any().item())
    fallback: Optional[str] = None
    if constraint_met:
        recall_ok = recall.clone()
        recall_ok[~ok] = -1.0
        best_idx = int(torch.argmax(recall_ok).item())
    else:
        target = torch.tensor(0.5, dtype=t.dtype)
        best_idx = int(torch.argmin(torch.abs(t.view(-1) - target)).item())
        fallback = "nearest_to_0.5"
        logger.warning(
            "No threshold met min_precision=%.2f; falling back to t=%.3f (%s)",
            float(min_precision),
            float(thresholds[best_idx]),
            fallback,
        )

    best = {
        "threshold": float(thresholds[best_idx]),
        "recall": float(recall[best_idx].item()),
        "precision": float(precision[best_idx].item()),
        "f1": float(f1[best_idx].item()),
        "acc": float(acc[best_idx].item()),
        "constraint_met": bool(constraint_met),
        "fallback": fallback,
    }

    logger.info(
        "Threshold sweep done in %.2fs (T=%d, min_precision=%.2f) => t=%.3f p=%.3f r=%.3f",
        time.time() - sweep_start,
        len(thresholds),
        float(min_precision),
        best["threshold"],
        best["precision"],
        best["recall"],
    )

    return best


def train_one(
    *,
    isotope_name: str,
    data_dir: Path,
    out_dir: Path,
    target_time_intervals: int,
    epochs: int,
    batch_size: int,
    lr: float,
    weight_decay: float,
    num_workers: int,
    seed: int,
    max_train: Optional[int],
    max_val: Optional[int],
    presence_index_path: Optional[Path],
    threshold: float,
    tune_threshold: bool,
    min_precision: float,
    loss_name: str,
    focal_alpha: float,
    focal_gamma: float,
    pos_weight_mult: float,
    steps_per_epoch: int,
    val_batches: int,
    eval_every: int,
    threshold_grid_step: float,
    log_every: int,
    profile_dataloader: bool,
    show_progress: bool,
    save_metric: str,
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    overall_start = time.time()

    rng = random.Random(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    isotope_index = get_default_isotope_index()
    target_index = isotope_index.name_to_index(isotope_name)

    presence_index: Optional[Dict[str, np.ndarray]] = None
    if presence_index_path is not None:
        presence_index = load_presence_index(presence_index_path)

    logger.info("Initializing dataset from %s", data_dir)
    dataset_start = time.time()
    base = SpectrumDataset2D(
        data_dir=data_dir,
        isotope_index=isotope_index,
        max_activity_bq=1000.0,
        target_time_intervals=target_time_intervals,
    )
    logger.info("Dataset init done in %.2fs", time.time() - dataset_start)

    total = len(base)
    train_size = int(total * 0.8)
    val_size = int(total * 0.1)
    test_size = total - train_size - val_size

    generator = torch.Generator().manual_seed(seed)
    logger.info("Splitting dataset: train=%d val=%d test=%d", train_size, val_size, test_size)
    train_subset, val_subset, _test_subset = torch.utils.data.random_split(
        base, [train_size, val_size, test_size], generator=generator
    )

    # Optionally reduce sizes for a fast experiment
    if max_train is not None and max_train < len(train_subset):
        idx = list(range(len(train_subset)))
        rng.shuffle(idx)
        train_subset = Subset(train_subset, idx[:max_train])

    if max_val is not None and max_val < len(val_subset):
        idx = list(range(len(val_subset)))
        rng.shuffle(idx)
        val_subset = Subset(val_subset, idx[:max_val])

    # Build balanced sampler from labels-only scan.
    t0 = time.time()
    logger.info("Building balanced sampler (presence_index=%s)", str(presence_index_path) if presence_index_path else "<none>")
    train_flags = _presence_flags_for_subset(
        train_subset,
        isotope_name=isotope_name,
        target_index=target_index,
        presence_index=presence_index,
    )
    sampler, pos_frac, pos_weight = make_balanced_sampler(train_flags, seed=seed)
    t1 = time.time()
    logger.info("Sampler ready in %.2fs (pos_frac=%.4f)", t1 - t0, pos_frac)

    train_ds = BinaryIsotopeDataset(train_subset, target_index)
    val_ds = BinaryIsotopeDataset(val_subset, target_index)

    train_loader = DataLoader(
        train_ds,
        batch_size=batch_size,
        sampler=sampler,
        num_workers=num_workers,
        pin_memory=True,
        persistent_workers=num_workers > 0,
        prefetch_factor=2 if num_workers > 0 else None,
        drop_last=True,
        collate_fn=collate_binary,
    )

    val_loader = DataLoader(
        val_ds,
        batch_size=batch_size,
        shuffle=False,
        num_workers=max(0, num_workers // 2),
        pin_memory=True,
        persistent_workers=(num_workers // 2) > 0,
        prefetch_factor=2 if (num_workers // 2) > 0 else None,
        collate_fn=collate_binary,
    )

    # Timing the first batch is the fastest way to diagnose startup stalls
    # (worker process spawn, NAS latency, etc.).
    logger.info(
        "DataLoader config: train_workers=%d val_workers=%d pin_memory=%s persistent=%s prefetch=%s",
        num_workers,
        max(0, num_workers // 2),
        True,
        num_workers > 0,
        2 if num_workers > 0 else None,
    )
    logger.info("Fetching first training batch (this may take time on Windows/NAS)...")
    first_batch_start = time.time()
    _ = next(iter(train_loader))
    logger.info("First batch fetched in %.2fs", time.time() - first_batch_start)

    cfg = Binary2DConfig(num_channels=1023, num_time_intervals=target_time_intervals)
    model = TinyBinary2DCNN(cfg).to(device)

    scaler = torch.amp.GradScaler("cuda") if (device.type == "cuda") else None

    effective_pos_weight = float(pos_weight) * float(pos_weight_mult)
    if loss_name == "bce":
        criterion: nn.Module = nn.BCEWithLogitsLoss(pos_weight=torch.tensor([effective_pos_weight], device=device))
    elif loss_name == "focal":
        # Focal loss already biases toward hard examples; we keep sampling balanced.
        criterion = FocalLoss(alpha=focal_alpha, gamma=focal_gamma, reduction="mean")
    else:
        raise ValueError(f"Unknown loss: {loss_name}")

    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)

    best_f1 = -1.0
    best_score = -1.0
    best_path = out_dir / f"binary_{isotope_name.replace('-', '_')}.pt"

    meta_path = out_dir / f"binary_{isotope_name.replace('-', '_')}_meta.json"
    meta: Dict[str, object] = {
        "isotope": isotope_name,
        "target_index": int(target_index),
        "device": str(device),
        "train_pos_frac": float(pos_frac),
        "train_pos_weight": float(pos_weight),
        "train_pos_weight_mult": float(pos_weight_mult),
        "effective_pos_weight": float(effective_pos_weight),
        "label_scan_seconds": float(t1 - t0),
        "presence_index": str(presence_index_path) if presence_index_path else None,
        "model_params": int(count_parameters(model)),
        "config": asdict(cfg),
        "train_subset": len(train_ds),
        "val_subset": len(val_ds),
        "batch_size": batch_size,
        "lr": lr,
        "weight_decay": weight_decay,
        "num_workers": num_workers,
        "epochs": epochs,
        "threshold": float(threshold),
        "tune_threshold": bool(tune_threshold),
        "min_precision": float(min_precision),
        "loss": loss_name,
        "focal_alpha": float(focal_alpha),
        "focal_gamma": float(focal_gamma),
        "save_metric": str(save_metric),
    }

    print(f"Device: {device}")
    if device.type == "cuda":
        print(f"GPU: {torch.cuda.get_device_name()}")
    print(f"Target isotope: {isotope_name} (index={target_index})")
    print(f"Model parameters: {count_parameters(model):,}")
    print(f"Train subset: {len(train_ds):,} (pos_frac={pos_frac:.4f}, pos_weight={pos_weight:.2f})")
    print(f"Val subset: {len(val_ds):,}")
    print(f"Label scan: {t1 - t0:.1f}s")
    print(f"Loss: {loss_name} (effective_pos_weight={effective_pos_weight:.2f})")
    print(f"Threshold: {threshold:.2f} (tune_threshold={tune_threshold}, min_precision={min_precision:.2f})")
    print(
        f"Speed knobs: steps_per_epoch={steps_per_epoch} val_batches={val_batches} eval_every={eval_every} "
        f"threshold_grid_step={threshold_grid_step:.3f}"
    )

    if profile_dataloader:
        print(
            "Profiling enabled: printing batch fetch/compute timing. "
            "If fetch dominates, you're I/O bound; if compute dominates, model/GPU bound."
        )

    epoch_iter = range(epochs)
    if show_progress and tqdm is not None:
        epoch_iter = tqdm(epoch_iter, desc=f"{isotope_name} epochs", leave=True)

    for epoch in epoch_iter:
        model.train()
        epoch_loss = 0.0
        seen = 0

        epoch_start = time.time()

        batch_count = 0
        train_total = steps_per_epoch if steps_per_epoch > 0 else None
        batch_range = range(train_total) if train_total is not None else None
        batch_pbar = None
        if show_progress and tqdm is not None and train_total is not None:
            batch_pbar = tqdm(total=train_total, desc=f"{isotope_name} train", leave=False)

        # Manual iterator so we can time how long it takes to fetch each batch.
        it = iter(train_loader)
        while True:
            if train_total is not None and batch_count >= train_total:
                break

            fetch_start = time.time()
            try:
                batch = next(it)
            except StopIteration:
                break
            fetch_seconds = time.time() - fetch_start

            x = batch["spectrum"].to(device, non_blocking=True)
            y = batch["label"].to(device, non_blocking=True)

            opt.zero_grad(set_to_none=True)

            compute_start = time.time()
            if scaler is not None:
                with torch.amp.autocast("cuda"):
                    logits = model(x)
                    loss = criterion(logits, y)
                scaler.scale(loss).backward()
                scaler.step(opt)
                scaler.update()
            else:
                logits = model(x)
                loss = criterion(logits, y)
                loss.backward()
                opt.step()

            if device.type == "cuda":
                torch.cuda.synchronize()
            compute_seconds = time.time() - compute_start

            epoch_loss += float(loss.item()) * y.numel()
            seen += int(y.numel())
            batch_count += 1

            if batch_pbar is not None:
                batch_pbar.update(1)

            if log_every > 0 and (batch_count % log_every == 0):
                logger.info(
                    "Epoch %d batch %d | fetch=%.3fs compute=%.3fs loss=%.4f",
                    epoch,
                    batch_count,
                    fetch_seconds,
                    compute_seconds,
                    float(loss.item()),
                )

        if batch_pbar is not None:
            batch_pbar.close()

        epoch_seconds = time.time() - epoch_start

        train_loss = epoch_loss / max(seen, 1)

        do_eval = (eval_every <= 1) or (epoch % eval_every == 0) or (epoch == epochs - 1)
        metrics = None
        tuned = None

        if do_eval:
            eval_start = time.time()
            metrics = evaluate_limited(model, val_loader, device=device, threshold=threshold, max_batches=val_batches)
            logger.info("Eval done in %.2fs (val_batches=%s)", time.time() - eval_start, val_batches)

            if tune_threshold:
                step = max(0.001, float(threshold_grid_step))
                thresholds = [round(float(x), 3) for x in np.arange(0.01, 0.991, step).tolist()]
                sweep_start = time.time()
                tuned = evaluate_threshold_sweep(
                    model,
                    val_loader,
                    device=device,
                    thresholds=thresholds,
                    min_precision=min_precision,
                    max_batches=val_batches,
                )
                logger.info("Threshold sweep done in %.2fs (%d thresholds)", time.time() - sweep_start, len(thresholds))

        msg = f"Epoch {epoch:03d} | {batch_count:4d} batches | {epoch_seconds:.2f}s | train_loss={train_loss:.4f}"
        if metrics is not None:
            msg += (
                f" | val@t={threshold:.3f} acc={metrics['acc']:.3f} "
                f"p={metrics['precision']:.3f} r={metrics['recall']:.3f} f1={metrics['f1']:.3f}"
            )
            if tuned is not None:
                msg += (
                    f" | tuned@t={tuned['threshold']:.3f} p={tuned['precision']:.3f} "
                    f"r={tuned['recall']:.3f} f1={tuned['f1']:.3f}"
                )
        else:
            msg += " | val=SKIPPED"
        print(msg)

        if metrics is not None:
            # Decide what we're optimizing for when selecting the best checkpoint.
            # Threshold-independent metrics (roc_auc, avg_precision) are generally preferred.
            if save_metric == "f1":
                score = float(metrics["f1"])
            elif save_metric == "recall":
                score = float(metrics["recall"])
            elif save_metric == "tuned_recall":
                score = float(tuned["recall"]) if tuned is not None else float(metrics["recall"])
            elif save_metric == "roc_auc":
                score = float(metrics.get("roc_auc", 0.0))
            elif save_metric == "avg_precision":
                score = float(metrics.get("avg_precision", 0.0))
            else:
                raise ValueError(f"Unknown save_metric: {save_metric}")

            if score > best_score:
                best_score = score
                best_f1 = float(metrics["f1"])
                torch.save(
                    {
                        "state_dict": model.state_dict(),
                        "config": asdict(cfg),
                        "isotope": isotope_name,
                        "target_index": int(target_index),
                    },
                    best_path,
                )

                meta["best_epoch"] = int(epoch)
                meta["best_f1"] = float(best_f1)
                meta["best_score"] = float(best_score)
                meta["best_metrics"] = metrics
                if tuned is not None:
                    meta["best_tuned_threshold"] = float(tuned["threshold"])
                    meta["best_tuned_metrics"] = tuned
                with open(meta_path, "w", encoding="utf-8") as f:
                    json.dump(meta, f, indent=2)

    print(f"Best F1: {best_f1:.4f}")
    print(f"Saved: {best_path}")
    logger.info("Total runtime: %.2fs", time.time() - overall_start)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a tiny binary per-isotope 2D CNN")
    parser.add_argument("--data-dir", type=str, default="O:/master_data_collection/isotopev4")
    parser.add_argument("--out-dir", type=str, default="models/binary_isotope")
    parser.add_argument("--isotope", type=str, default="U-238", help="Target isotope name, e.g. U-238")

    parser.add_argument("--time-intervals", type=int, default=300)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-5)

    parser.add_argument("--num-workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)

    parser.add_argument("--max-train", type=int, default=50000, help="Limit train samples for a faster test")
    parser.add_argument("--max-val", type=int, default=10000, help="Limit val samples for a faster test")
    parser.add_argument(
        "--presence-index",
        type=str,
        default="",
        help="Optional path to precomputed isotope->indices index (.npz/.json) for fast sampling",
    )

    parser.add_argument("--threshold", type=float, default=0.5, help="Decision threshold for metrics")
    parser.add_argument(
        "--tune-threshold",
        action="store_true",
        help="(Optional) Sweep thresholds on val. Usually not needed - just use probability output.",
    )
    parser.add_argument("--min-precision", type=float, default=0.50, help="Only used if --tune-threshold is set")

    parser.add_argument("--loss", type=str, default="bce", choices=["bce", "focal"])
    parser.add_argument("--focal-alpha", type=float, default=0.25)
    parser.add_argument("--focal-gamma", type=float, default=2.0)
    parser.add_argument(
        "--pos-weight-mult",
        type=float,
        default=1.0,
        help="Multiplier applied to neg/pos pos_weight when using BCE",
    )

    # Speed knobs
    parser.add_argument(
        "--steps-per-epoch",
        type=int,
        default=50,
        help="Cap training batches per epoch (<=0 means full pass)",
    )
    parser.add_argument(
        "--val-batches",
        type=int,
        default=20,
        help="Cap validation batches (<=0 means full validation)",
    )
    parser.add_argument(
        "--eval-every",
        type=int,
        default=1,
        help="Run validation every N epochs (higher = faster)",
    )
    parser.add_argument(
        "--threshold-grid-step",
        type=float,
        default=0.01,
        help="Threshold sweep step size (smaller = finer, slower)",
    )

    parser.add_argument(
        "--log-level",
        type=str,
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Console logging verbosity",
    )
    parser.add_argument(
        "--log-every",
        type=int,
        default=10,
        help="Log per-batch timings every N training batches (0 disables)",
    )
    parser.add_argument(
        "--profile-dataloader",
        action="store_true",
        help="Print extra hints and per-batch timing logs",
    )
    parser.add_argument(
        "--no-progress",
        action="store_true",
        help="Disable tqdm progress bars",
    )

    parser.add_argument(
        "--save-metric",
        type=str,
        default="roc_auc",
        choices=["f1", "recall", "tuned_recall", "roc_auc", "avg_precision"],
        help="Metric to select best checkpoint (roc_auc/avg_precision are threshold-independent)",
    )

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, str(args.log_level).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(message)s",
    )

    # Allow disabling the limits
    max_train = None if args.max_train <= 0 else int(args.max_train)
    max_val = None if args.max_val <= 0 else int(args.max_val)

    presence_index_path = Path(args.presence_index) if args.presence_index else None

    train_one(
        isotope_name=args.isotope,
        data_dir=Path(args.data_dir),
        out_dir=Path(args.out_dir),
        target_time_intervals=args.time_intervals,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        weight_decay=args.weight_decay,
        num_workers=args.num_workers,
        seed=args.seed,
        max_train=max_train,
        max_val=max_val,
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


if __name__ == "__main__":
    # Avoid noisy OpenMP warnings on Windows in some envs
    os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
    main()
