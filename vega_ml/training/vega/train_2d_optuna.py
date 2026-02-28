"""
Vega 2D Optuna Hyperparameter Optimization

Optimizes the 300-second 2D model with comprehensive per-isotope metrics.
Uses Optuna with TPE sampler and Hyperband pruner for efficiency.

Key features:
- Per-isotope accuracy tracking for every trial
- Prioritizes recall for key isotopes (U-238, Ra-226, Pb-214, Bi-214, etc.)
- 25 epochs max per trial with early stopping
- Saves detailed metrics to JSON for analysis
"""

import os
import sys
import json
import time
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple, List, Any
from dataclasses import dataclass, asdict, field

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.optim import Adam, AdamW
from torch.optim.lr_scheduler import ReduceLROnPlateau, CosineAnnealingWarmRestarts
from torch.cuda.amp import GradScaler, autocast

import optuna
from optuna.trial import Trial
from optuna.pruners import HyperbandPruner, MedianPruner
from optuna.samplers import TPESampler

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.model_2d import Vega2DModel, Vega2DConfig, count_parameters
from training.vega.dataset_2d import create_data_loaders_2d
from training.vega.isotope_index import IsotopeIndex, get_default_isotope_index

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


# ============================================================================
# Focal Loss for Class Imbalance
# ============================================================================

class FocalLoss(nn.Module):
    """Focal Loss for handling class imbalance."""
    
    def __init__(self, alpha: float = 0.25, gamma: float = 2.0, reduction: str = 'mean'):
        super().__init__()
        self.alpha = alpha
        self.gamma = gamma
        self.reduction = reduction
    
    def forward(self, inputs: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        BCE_loss = F.binary_cross_entropy_with_logits(inputs, targets, reduction='none')
        probs = torch.sigmoid(inputs)
        p_t = probs * targets + (1 - probs) * (1 - targets)
        alpha_t = self.alpha * targets + (1 - self.alpha) * (1 - targets)
        focal_weight = alpha_t * (1 - p_t) ** self.gamma
        focal_loss = focal_weight * BCE_loss
        
        if self.reduction == 'mean':
            return focal_loss.mean()
        elif self.reduction == 'sum':
            return focal_loss.sum()
        return focal_loss


# ============================================================================
# Configuration
# ============================================================================

@dataclass
class OptunaConfig2D:
    """Configuration for Optuna hyperparameter optimization."""
    
    # Data
    data_dir: str = "O:/master_data_collection/isotopev4"
    model_dir: str = "models/optuna_2d"
    study_name: str = "vega_2d_optimization"
    
    # Optuna settings
    n_trials: int = 100
    timeout_hours: float = 48.0
    n_startup_trials: int = 10
    
    # Training settings per trial
    max_epochs: int = 25
    patience: int = 6  # Early stopping
    
    # Fixed settings
    target_time_intervals: int = 300
    num_channels: int = 1023
    num_isotopes: int = 82  # Full isotope database
    num_workers: int = 4
    use_amp: bool = True
    
    # Objective - weighted combination
    primary_metric: str = "macro_f1"
    secondary_metric: str = "key_isotope_recall"
    
    # Key isotopes to prioritize (daughters and parents we care about)
    key_isotopes: List[str] = field(default_factory=lambda: [
        'U-238', 'Ra-226', 'Pb-214', 'Bi-214',  # Uranium chain
        'Th-232', 'Ac-228', 'Pb-212', 'Bi-212', 'Tl-208',  # Thorium chain
        'Cs-137', 'Co-60', 'Am-241',  # Common industrial/medical
        'K-40',  # Background
    ])
    
    # Reproducibility
    seed: int = 42


# ============================================================================
# Hyperparameter Suggestions
# ============================================================================

def suggest_hyperparameters(trial: Trial) -> Dict[str, Any]:
    """Suggest hyperparameters for a trial - simplified for 3-layer CNN."""
    params = {}
    
    # ========== Model Architecture (Micro 3-layer CNN) ==========
    # Channel sizes - very small
    params["conv1_channels"] = trial.suggest_categorical("conv1_channels", [4, 8])
    params["conv2_channels"] = trial.suggest_categorical("conv2_channels", [8, 16])
    params["conv3_channels"] = trial.suggest_categorical("conv3_channels", [16, 32])
    
    # Kernel size - fixed small
    params["kernel_time"] = 3
    params["kernel_energy"] = 3
    
    # Pooling - very aggressive
    pool_choice = trial.suggest_categorical("pool_size", ["5x5", "6x6", "5x8"])
    pool_map = {"5x5": (5, 5), "6x6": (6, 6), "5x8": (5, 8)}
    params["pool_size"] = pool_map[pool_choice]
    
    # Single FC layer - tiny
    params["fc_dim"] = trial.suggest_categorical("fc_dim", [16, 32, 64])
    
    # Regularization
    params["dropout_rate"] = trial.suggest_float("dropout_rate", 0.1, 0.5)
    
    # ========== Training ==========
    # Simple 3-layer CNN is ~230K params, can handle larger batches
    params["batch_size"] = trial.suggest_categorical("batch_size", [32, 64, 128])
    params["learning_rate"] = trial.suggest_float("learning_rate", 1e-4, 1e-2, log=True)
    params["weight_decay"] = trial.suggest_float("weight_decay", 1e-6, 1e-3, log=True)
    
    # Optimizer
    params["optimizer"] = trial.suggest_categorical("optimizer", ["adam", "adamw"])
    
    # Scheduler
    params["scheduler"] = trial.suggest_categorical("scheduler", ["plateau", "cosine"])
    if params["scheduler"] == "plateau":
        params["lr_factor"] = trial.suggest_float("lr_factor", 0.2, 0.5)
        params["lr_patience"] = trial.suggest_int("lr_patience", 2, 5)
    else:
        params["cosine_t_0"] = trial.suggest_int("cosine_t_0", 5, 10)
    
    # ========== Loss Function ==========
    params["use_focal_loss"] = trial.suggest_categorical("use_focal_loss", [True, False])
    if params["use_focal_loss"]:
        params["focal_alpha"] = trial.suggest_float("focal_alpha", 0.15, 0.5)
        params["focal_gamma"] = trial.suggest_float("focal_gamma", 1.0, 3.0)
    
    params["classification_weight"] = trial.suggest_float("classification_weight", 0.5, 2.0)
    params["regression_weight"] = trial.suggest_float("regression_weight", 0.01, 0.3, log=True)
    
    # ========== Inference ==========
    params["threshold"] = trial.suggest_float("threshold", 0.3, 0.6)
    
    return params


def create_model_from_params(params: Dict, config: OptunaConfig2D) -> Vega2DModel:
    """Create Vega2DModel from suggested parameters."""
    model_config = Vega2DConfig(
        num_channels=config.num_channels,
        num_time_intervals=config.target_time_intervals,
        num_isotopes=config.num_isotopes,
        conv1_channels=params["conv1_channels"],
        conv2_channels=params["conv2_channels"],
        conv3_channels=params["conv3_channels"],
        kernel_size=(params["kernel_time"], params["kernel_energy"]),
        pool_size=params["pool_size"],
        fc_dim=params["fc_dim"],
        dropout_rate=params["dropout_rate"],
    )
    return Vega2DModel(model_config)


# ============================================================================
# Per-Isotope Metrics
# ============================================================================

@torch.no_grad()
def compute_per_isotope_metrics(
    model: nn.Module,
    data_loader,
    device: torch.device,
    threshold: float,
    isotope_names: List[str],
    key_isotopes: List[str]
) -> Dict[str, Any]:
    """
    Compute comprehensive per-isotope metrics.
    
    Returns:
        Dict with:
        - per_isotope: {isotope_name: {precision, recall, f1, support}}
        - macro_precision, macro_recall, macro_f1
        - key_isotope_precision, key_isotope_recall, key_isotope_f1
        - exact_match_accuracy
    """
    model.eval()
    
    all_preds = []
    all_labels = []
    total_loss = 0.0
    num_batches = 0
    
    criterion = nn.BCEWithLogitsLoss()
    
    for batch in data_loader:
        spectra = batch['spectrum'].to(device)
        presence = batch['presence_labels'].to(device)
        
        logits, _ = model(spectra)
        loss = criterion(logits, presence)
        total_loss += loss.item()
        num_batches += 1
        
        probs = torch.sigmoid(logits)
        preds = (probs >= threshold).float()
        all_preds.append(preds.cpu())
        all_labels.append(presence.cpu())
    
    all_preds = torch.cat(all_preds, dim=0).numpy()
    all_labels = torch.cat(all_labels, dim=0).numpy()
    
    num_samples, num_isotopes = all_labels.shape
    
    # Per-isotope metrics
    per_isotope = {}
    precisions = []
    recalls = []
    f1s = []
    
    key_precisions = []
    key_recalls = []
    key_f1s = []
    
    for i, iso_name in enumerate(isotope_names):
        y_true = all_labels[:, i]
        y_pred = all_preds[:, i]
        
        tp = ((y_pred == 1) & (y_true == 1)).sum()
        fp = ((y_pred == 1) & (y_true == 0)).sum()
        fn = ((y_pred == 0) & (y_true == 1)).sum()
        tn = ((y_pred == 0) & (y_true == 0)).sum()
        
        support = int(y_true.sum())
        precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
        
        per_isotope[iso_name] = {
            'precision': float(precision),
            'recall': float(recall),
            'f1': float(f1),
            'support': support,
            'tp': int(tp),
            'fp': int(fp),
            'fn': int(fn),
            'tn': int(tn)
        }
        
        # Only include in macro average if there's support
        if support > 0:
            precisions.append(precision)
            recalls.append(recall)
            f1s.append(f1)
        
        # Key isotopes
        if iso_name in key_isotopes and support > 0:
            key_precisions.append(precision)
            key_recalls.append(recall)
            key_f1s.append(f1)
    
    # Macro averages
    macro_precision = np.mean(precisions) if precisions else 0.0
    macro_recall = np.mean(recalls) if recalls else 0.0
    macro_f1 = np.mean(f1s) if f1s else 0.0
    
    # Key isotope averages
    key_precision = np.mean(key_precisions) if key_precisions else 0.0
    key_recall = np.mean(key_recalls) if key_recalls else 0.0
    key_f1 = np.mean(key_f1s) if key_f1s else 0.0
    
    # Exact match
    exact_match = (all_preds == all_labels).all(axis=1).mean()
    
    # Micro averages
    total_tp = sum(m['tp'] for m in per_isotope.values())
    total_fp = sum(m['fp'] for m in per_isotope.values())
    total_fn = sum(m['fn'] for m in per_isotope.values())
    micro_precision = total_tp / (total_tp + total_fp) if (total_tp + total_fp) > 0 else 0.0
    micro_recall = total_tp / (total_tp + total_fn) if (total_tp + total_fn) > 0 else 0.0
    micro_f1 = 2 * micro_precision * micro_recall / (micro_precision + micro_recall) if (micro_precision + micro_recall) > 0 else 0.0
    
    return {
        'per_isotope': per_isotope,
        'macro_precision': float(macro_precision),
        'macro_recall': float(macro_recall),
        'macro_f1': float(macro_f1),
        'micro_precision': float(micro_precision),
        'micro_recall': float(micro_recall),
        'micro_f1': float(micro_f1),
        'key_isotope_precision': float(key_precision),
        'key_isotope_recall': float(key_recall),
        'key_isotope_f1': float(key_f1),
        'exact_match': float(exact_match),
        'val_loss': total_loss / num_batches if num_batches > 0 else float('inf')
    }


# ============================================================================
# Training Functions
# ============================================================================

def train_epoch(
    model: nn.Module,
    train_loader,
    optimizer,
    criterion_cls: nn.Module,
    criterion_reg: nn.Module,
    device: torch.device,
    scaler: Optional[GradScaler],
    cls_weight: float,
    reg_weight: float
) -> Dict[str, float]:
    """Train for one epoch."""
    model.train()
    
    total_loss = 0.0
    total_cls_loss = 0.0
    total_reg_loss = 0.0
    num_batches = 0
    
    for batch in train_loader:
        spectra = batch['spectrum'].to(device)
        presence = batch['presence_labels'].to(device)
        activities = batch['activity_labels'].to(device)
        
        optimizer.zero_grad()
        
        if scaler is not None:
            with autocast():
                logits, pred_activities = model(spectra)
                cls_loss = criterion_cls(logits, presence)
                reg_loss = criterion_reg(pred_activities, activities)
                loss = cls_weight * cls_loss + reg_weight * reg_loss
            
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        else:
            logits, pred_activities = model(spectra)
            cls_loss = criterion_cls(logits, presence)
            reg_loss = criterion_reg(pred_activities, activities)
            loss = cls_weight * cls_loss + reg_weight * reg_loss
            
            loss.backward()
            optimizer.step()
        
        total_loss += loss.item()
        total_cls_loss += cls_loss.item()
        total_reg_loss += reg_loss.item()
        num_batches += 1
    
    return {
        'loss': total_loss / num_batches,
        'cls_loss': total_cls_loss / num_batches,
        'reg_loss': total_reg_loss / num_batches
    }


def train_trial(
    trial: Trial,
    params: Dict,
    train_loader,
    val_loader,
    device: torch.device,
    config: OptunaConfig2D,
    isotope_names: List[str]
) -> Tuple[float, Dict]:
    """
    Train a single Optuna trial.
    
    Returns:
        Tuple of (objective_value, full_metrics_dict)
    """
    # Create model
    model = create_model_from_params(params, config)
    model = model.to(device)
    
    n_params = count_parameters(model)
    logger.info(f"Trial {trial.number}: {n_params:,} parameters")
    
    # Loss functions
    if params.get("use_focal_loss", False):
        criterion_cls = FocalLoss(
            alpha=params.get("focal_alpha", 0.25),
            gamma=params.get("focal_gamma", 2.0)
        )
    else:
        criterion_cls = nn.BCEWithLogitsLoss()
    criterion_reg = nn.HuberLoss()
    
    # Optimizer
    if params["optimizer"] == "adamw":
        optimizer = AdamW(
            model.parameters(),
            lr=params["learning_rate"],
            weight_decay=params["weight_decay"]
        )
    else:
        optimizer = Adam(
            model.parameters(),
            lr=params["learning_rate"],
            weight_decay=params["weight_decay"]
        )
    
    # Scheduler
    if params["scheduler"] == "cosine":
        scheduler = CosineAnnealingWarmRestarts(
            optimizer,
            T_0=params.get("cosine_t_0", 5),
            T_mult=1
        )
    else:
        scheduler = ReduceLROnPlateau(
            optimizer,
            mode='max',
            patience=params.get("lr_patience", 3),
            factor=params.get("lr_factor", 0.5)
        )
    
    # Mixed precision
    scaler = GradScaler() if config.use_amp and device.type == 'cuda' else None
    
    # Training loop
    best_objective = 0.0
    best_metrics = None
    epochs_without_improvement = 0
    
    cls_weight = params["classification_weight"]
    reg_weight = params["regression_weight"]
    threshold = params["threshold"]
    
    for epoch in range(config.max_epochs):
        # Train
        train_metrics = train_epoch(
            model, train_loader, optimizer,
            criterion_cls, criterion_reg,
            device, scaler, cls_weight, reg_weight
        )
        
        # Validate with per-isotope metrics
        val_metrics = compute_per_isotope_metrics(
            model, val_loader, device, threshold,
            isotope_names, config.key_isotopes
        )
        
        # Update scheduler
        if params["scheduler"] == "cosine":
            scheduler.step()
        else:
            scheduler.step(val_metrics['macro_f1'])
        
        # Compute objective (weighted combination)
        primary = val_metrics[config.primary_metric]
        secondary = val_metrics[config.secondary_metric]
        objective = 0.7 * primary + 0.3 * secondary
        
        # Report to Optuna
        trial.report(objective, epoch)
        
        if trial.should_prune():
            raise optuna.TrialPruned()
        
        # Track best
        if objective > best_objective:
            best_objective = objective
            best_metrics = {
                'epoch': epoch,
                'train_loss': train_metrics['loss'],
                **val_metrics,
                'params': params
            }
            epochs_without_improvement = 0
        else:
            epochs_without_improvement += 1
        
        # Early stopping
        if epochs_without_improvement >= config.patience:
            logger.info(f"Trial {trial.number}: Early stopping at epoch {epoch}")
            break
        
        # Progress log every 5 epochs
        if epoch % 5 == 0:
            logger.info(
                f"Trial {trial.number} Epoch {epoch}: "
                f"macro_f1={val_metrics['macro_f1']:.4f}, "
                f"key_recall={val_metrics['key_isotope_recall']:.4f}"
            )
    
    return best_objective, best_metrics


# ============================================================================
# Main Optimization
# ============================================================================

def create_objective(
    train_dataset,
    val_dataset,
    device: torch.device,
    config: OptunaConfig2D,
    isotope_names: List[str],
    results_dir: Path
):
    """Create the Optuna objective function."""
    from training.vega.dataset_2d import collate_fn_2d
    
    def objective(trial: Trial) -> float:
        params = suggest_hyperparameters(trial)
        
        # Create data loaders with trial-specific batch size
        batch_size = params["batch_size"]
        
        train_loader = torch.utils.data.DataLoader(
            train_dataset,
            batch_size=batch_size,
            shuffle=True,
            num_workers=8,
            collate_fn=collate_fn_2d,
            pin_memory=True,
            prefetch_factor=2,
            persistent_workers=True
        )
        
        val_loader = torch.utils.data.DataLoader(
            val_dataset,
            batch_size=batch_size,
            shuffle=False,
            num_workers=8,
            collate_fn=collate_fn_2d,
            pin_memory=True,
            prefetch_factor=2,
            persistent_workers=True
        )
        
        try:
            best_value, best_metrics = train_trial(
                trial, params, train_loader, val_loader,
                device, config, isotope_names
            )
            
            # Save detailed metrics
            trial_file = results_dir / f"trial_{trial.number:04d}.json"
            with open(trial_file, 'w') as f:
                json.dump({
                    'trial_number': trial.number,
                    'objective_value': best_value,
                    'metrics': best_metrics
                }, f, indent=2)
            
            return best_value
            
        except Exception as e:
            logger.error(f"Trial {trial.number} failed: {e}")
            raise optuna.TrialPruned()
        
        finally:
            # Clean up GPU memory between trials
            import gc
            del train_loader, val_loader
            gc.collect()
            torch.cuda.empty_cache()
    
    return objective


def run_optimization(config: OptunaConfig2D = None):
    """Run the full Optuna optimization."""
    config = config or OptunaConfig2D()
    
    # Setup
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    logger.info(f"Using device: {device}")
    if device.type == 'cuda':
        logger.info(f"GPU: {torch.cuda.get_device_name()}")
    
    # Create directories
    model_dir = Path(config.model_dir)
    model_dir.mkdir(parents=True, exist_ok=True)
    results_dir = model_dir / "trial_results"
    results_dir.mkdir(exist_ok=True)
    
    # Load data - create datasets, not loaders (loaders created per-trial for batch size)
    logger.info(f"Loading data from: {config.data_dir}")
    isotope_index = get_default_isotope_index()
    isotope_names = isotope_index.isotope_names
    
    from training.vega.dataset_2d import SpectrumDataset2D
    from torch.utils.data import random_split
    
    # Create full dataset
    full_dataset = SpectrumDataset2D(
        data_dir=Path(config.data_dir),
        isotope_index=isotope_index,
        max_activity_bq=1000.0,
        target_time_intervals=config.target_time_intervals
    )
    
    # Split dataset
    total = len(full_dataset)
    train_size = int(total * 0.8)
    val_size = int(total * 0.1)
    test_size = total - train_size - val_size
    
    generator = torch.Generator().manual_seed(config.seed)
    train_dataset, val_dataset, test_dataset = random_split(
        full_dataset, [train_size, val_size, test_size], generator=generator
    )
    
    logger.info(f"Dataset: train={train_size}, val={val_size}, test={test_size}")
    
    # Create Optuna study
    sampler = TPESampler(seed=config.seed, n_startup_trials=config.n_startup_trials)
    pruner = HyperbandPruner(
        min_resource=3,
        max_resource=config.max_epochs,
        reduction_factor=3
    )
    
    study = optuna.create_study(
        study_name=config.study_name,
        direction="maximize",
        sampler=sampler,
        pruner=pruner,
        storage=f"sqlite:///{model_dir / 'optuna_study.db'}",
        load_if_exists=True
    )
    
    # Create objective
    objective = create_objective(
        train_dataset, val_dataset, device,
        config, isotope_names, results_dir
    )
    
    # Run optimization
    logger.info(f"Starting optimization: {config.n_trials} trials, {config.max_epochs} epochs max")
    logger.info(f"Key isotopes: {config.key_isotopes}")
    
    start_time = time.time()
    
    study.optimize(
        objective,
        n_trials=config.n_trials,
        timeout=config.timeout_hours * 3600,
        show_progress_bar=True,
        gc_after_trial=True
    )
    
    elapsed = time.time() - start_time
    logger.info(f"Optimization completed in {elapsed/3600:.1f} hours")
    
    # Report best trial
    best_trial = study.best_trial
    logger.info(f"\nBest trial: {best_trial.number}")
    logger.info(f"Best value: {best_trial.value:.4f}")
    logger.info(f"Best params:")
    for k, v in best_trial.params.items():
        logger.info(f"  {k}: {v}")
    
    # Save study summary
    summary = {
        'study_name': config.study_name,
        'n_trials': len(study.trials),
        'best_trial': best_trial.number,
        'best_value': best_trial.value,
        'best_params': best_trial.params,
        'elapsed_hours': elapsed / 3600,
        'config': asdict(config)
    }
    
    with open(model_dir / 'optimization_summary.json', 'w') as f:
        json.dump(summary, f, indent=2, default=str)
    
    return study


# ============================================================================
# Entry Point
# ============================================================================

if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Vega 2D Optuna Optimization")
    parser.add_argument("--data-dir", type=str, default="O:/master_data_collection/isotopev4",
                        help="Path to training data")
    parser.add_argument("--model-dir", type=str, default="models/optuna_2d",
                        help="Output directory for models and results")
    parser.add_argument("--n-trials", type=int, default=100,
                        help="Number of Optuna trials")
    parser.add_argument("--max-epochs", type=int, default=25,
                        help="Maximum epochs per trial")
    parser.add_argument("--timeout", type=float, default=48.0,
                        help="Timeout in hours")
    parser.add_argument("--study-name", type=str, default="vega_2d_optimization",
                        help="Optuna study name")
    
    args = parser.parse_args()
    
    config = OptunaConfig2D(
        data_dir=args.data_dir,
        model_dir=args.model_dir,
        study_name=args.study_name,
        n_trials=args.n_trials,
        max_epochs=args.max_epochs,
        timeout_hours=args.timeout
    )
    
    run_optimization(config)
