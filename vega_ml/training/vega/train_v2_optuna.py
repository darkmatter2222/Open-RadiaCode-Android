"""
Vega Training v2 - Optuna Hyperparameter Optimization

Uses Optuna to search for optimal hyperparameters to maximize model performance,
with a focus on improving recall for isotope detection.

Key optimizations:
1. Model architecture (CNN channels, FC dims, kernel sizes)
2. Training hyperparameters (LR, batch size, weight decay, dropout)
3. Loss function weights (classification vs regression balance)
4. Classification threshold optimization
5. Focal loss for handling class imbalance
"""

import os
import sys
import json
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple, List
from dataclasses import dataclass, asdict, field

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.optim import Adam, AdamW
from torch.optim.lr_scheduler import ReduceLROnPlateau, CosineAnnealingWarmRestarts
import numpy as np

import optuna
from optuna.trial import Trial
from optuna.pruners import MedianPruner, HyperbandPruner
from optuna.samplers import TPESampler

# Sklearn metrics
from sklearn.metrics import (
    roc_auc_score,
    f1_score,
    precision_score,
    recall_score,
    hamming_loss
)

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.model import VegaModel, VegaConfig
from training.vega.dataset import create_data_loaders, SpectrumDataset
from training.vega.isotope_index import IsotopeIndex, get_default_isotope_index


class FocalLoss(nn.Module):
    """
    Focal Loss for handling class imbalance in multi-label classification.
    
    Reduces the relative loss for well-classified examples (high probability),
    putting more focus on hard, misclassified examples.
    
    FL(p_t) = -α_t * (1 - p_t)^γ * log(p_t)
    
    Args:
        alpha: Weighting factor for positive examples (default: 0.25)
        gamma: Focusing parameter - higher = more focus on hard examples (default: 2.0)
    """
    
    def __init__(self, alpha: float = 0.25, gamma: float = 2.0, reduction: str = 'mean'):
        super().__init__()
        self.alpha = alpha
        self.gamma = gamma
        self.reduction = reduction
    
    def forward(self, inputs: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        # inputs are logits, targets are binary labels
        BCE_loss = F.binary_cross_entropy_with_logits(inputs, targets, reduction='none')
        
        # Get probabilities
        probs = torch.sigmoid(inputs)
        p_t = probs * targets + (1 - probs) * (1 - targets)
        
        # Apply focal weighting
        alpha_t = self.alpha * targets + (1 - self.alpha) * (1 - targets)
        focal_weight = alpha_t * (1 - p_t) ** self.gamma
        
        focal_loss = focal_weight * BCE_loss
        
        if self.reduction == 'mean':
            return focal_loss.mean()
        elif self.reduction == 'sum':
            return focal_loss.sum()
        return focal_loss


class VegaLossV2(nn.Module):
    """
    Enhanced loss function with Focal Loss option and tunable weights.
    """
    
    def __init__(
        self,
        classification_weight: float = 1.0,
        regression_weight: float = 0.1,
        use_focal_loss: bool = True,
        focal_alpha: float = 0.25,
        focal_gamma: float = 2.0,
        pos_weight: Optional[torch.Tensor] = None
    ):
        super().__init__()
        self.classification_weight = classification_weight
        self.regression_weight = regression_weight
        self.use_focal_loss = use_focal_loss
        
        if use_focal_loss:
            self.cls_loss = FocalLoss(alpha=focal_alpha, gamma=focal_gamma)
        else:
            self.cls_loss = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
        
        self.reg_loss = nn.HuberLoss(delta=0.1)
    
    def forward(
        self,
        pred_logits: torch.Tensor,
        pred_activities: torch.Tensor,
        true_presence: torch.Tensor,
        true_activities: torch.Tensor
    ) -> Tuple[torch.Tensor, Dict[str, float]]:
        # Classification loss
        cls_loss = self.cls_loss(pred_logits, true_presence)
        
        # Regression loss (only for present isotopes)
        mask = true_presence > 0.5
        if mask.any():
            reg_loss = self.reg_loss(
                pred_activities[mask],
                true_activities[mask]
            )
        else:
            reg_loss = torch.tensor(0.0, device=pred_logits.device)
        
        # Combined loss
        total_loss = (
            self.classification_weight * cls_loss +
            self.regression_weight * reg_loss
        )
        
        return total_loss, {
            'total': total_loss.item(),
            'classification': cls_loss.item(),
            'regression': reg_loss.item() if mask.any() else 0.0
        }


@dataclass
class OptunaConfig:
    """Configuration for Optuna hyperparameter optimization."""
    
    # Data
    data_dir: str = "O:/master_data_collection/isotopev2"
    model_dir: str = "models/optuna"
    study_name: str = "vega_v2_optimization"
    
    # Optuna settings
    n_trials: int = 50
    timeout_hours: float = 24.0
    n_startup_trials: int = 10  # Random sampling before TPE
    
    # Training settings for each trial
    max_epochs: int = 30  # Shorter epochs for faster trials
    patience: int = 5  # Early stopping patience
    
    # Data splits
    train_split: float = 0.8
    val_split: float = 0.1
    test_split: float = 0.1
    
    # Fixed settings
    num_workers: int = 8
    prefetch_factor: int = 4
    persistent_workers: bool = True
    use_amp: bool = True
    
    # Optimization objective
    optimize_metric: str = "val_recall"  # Focus on recall
    
    # Reproducibility
    seed: int = 42


def suggest_hyperparameters(trial: Trial) -> Dict:
    """
    Suggest hyperparameters for a trial using Optuna.
    
    Returns a dictionary with all hyperparameters to try.
    """
    params = {}
    
    # ========== Model Architecture ==========
    # CNN backbone
    n_conv_layers = trial.suggest_int("n_conv_layers", 2, 4)
    conv_channels = []
    for i in range(n_conv_layers):
        ch = trial.suggest_categorical(f"conv_ch_{i}", [32, 64, 128, 256, 512])
        conv_channels.append(ch)
    params["conv_channels"] = conv_channels
    
    params["conv_kernel_size"] = trial.suggest_categorical("conv_kernel_size", [3, 5, 7, 9, 11])
    params["pool_size"] = trial.suggest_categorical("pool_size", [2, 3, 4])
    
    # FC layers
    n_fc_layers = trial.suggest_int("n_fc_layers", 1, 3)
    fc_dims = []
    for i in range(n_fc_layers):
        dim = trial.suggest_categorical(f"fc_dim_{i}", [128, 256, 512, 1024])
        fc_dims.append(dim)
    params["fc_hidden_dims"] = fc_dims
    
    # Regularization
    params["dropout_rate"] = trial.suggest_float("dropout_rate", 0.1, 0.5)
    params["spatial_dropout_rate"] = trial.suggest_float("spatial_dropout_rate", 0.05, 0.3)
    params["leaky_relu_slope"] = trial.suggest_float("leaky_relu_slope", 0.01, 0.2)
    
    # ========== Training Hyperparameters ==========
    params["batch_size"] = trial.suggest_categorical("batch_size", [128, 256, 512, 1024])
    params["learning_rate"] = trial.suggest_float("learning_rate", 1e-5, 1e-2, log=True)
    params["weight_decay"] = trial.suggest_float("weight_decay", 1e-6, 1e-3, log=True)
    
    # Optimizer
    params["optimizer"] = trial.suggest_categorical("optimizer", ["adam", "adamw"])
    
    # Learning rate scheduler
    params["scheduler"] = trial.suggest_categorical("scheduler", ["plateau", "cosine"])
    if params["scheduler"] == "plateau":
        params["lr_factor"] = trial.suggest_float("lr_factor", 0.1, 0.5)
        params["lr_patience"] = trial.suggest_int("lr_patience", 3, 10)
    else:
        params["cosine_t_0"] = trial.suggest_int("cosine_t_0", 5, 15)
        params["cosine_t_mult"] = trial.suggest_int("cosine_t_mult", 1, 2)
    
    # ========== Loss Function ==========
    params["use_focal_loss"] = trial.suggest_categorical("use_focal_loss", [True, False])
    if params["use_focal_loss"]:
        params["focal_alpha"] = trial.suggest_float("focal_alpha", 0.1, 0.5)
        params["focal_gamma"] = trial.suggest_float("focal_gamma", 1.0, 3.0)
    
    params["classification_weight"] = trial.suggest_float("classification_weight", 0.5, 2.0)
    params["regression_weight"] = trial.suggest_float("regression_weight", 0.01, 0.5, log=True)
    
    # ========== Classification Threshold ==========
    params["threshold"] = trial.suggest_float("threshold", 0.3, 0.7)
    
    return params


def create_model_from_params(params: Dict, num_isotopes: int) -> VegaModel:
    """Create a VegaModel from hyperparameters."""
    config = VegaConfig(
        num_isotopes=num_isotopes,
        conv_channels=params["conv_channels"],
        conv_kernel_size=params["conv_kernel_size"],
        pool_size=params["pool_size"],
        fc_hidden_dims=params["fc_hidden_dims"],
        dropout_rate=params["dropout_rate"],
        spatial_dropout_rate=params["spatial_dropout_rate"],
        leaky_relu_slope=params["leaky_relu_slope"],
        classification_weight=params["classification_weight"],
        regression_weight=params["regression_weight"]
    )
    return VegaModel(config)


def train_single_trial(
    trial: Trial,
    params: Dict,
    train_loader,
    val_loader,
    device: torch.device,
    optuna_config: OptunaConfig
) -> float:
    """
    Train a single trial and return the objective metric.
    """
    # Create model
    num_isotopes = 82  # From isotope database
    model = create_model_from_params(params, num_isotopes)
    model = model.to(device)
    
    # Create loss function
    loss_fn = VegaLossV2(
        classification_weight=params["classification_weight"],
        regression_weight=params["regression_weight"],
        use_focal_loss=params.get("use_focal_loss", False),
        focal_alpha=params.get("focal_alpha", 0.25),
        focal_gamma=params.get("focal_gamma", 2.0)
    )
    
    # Create optimizer
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
    
    # Create scheduler
    if params["scheduler"] == "cosine":
        scheduler = CosineAnnealingWarmRestarts(
            optimizer,
            T_0=params.get("cosine_t_0", 10),
            T_mult=params.get("cosine_t_mult", 1)
        )
    else:
        scheduler = ReduceLROnPlateau(
            optimizer,
            mode='min',
            patience=params.get("lr_patience", 5),
            factor=params.get("lr_factor", 0.5)
        )
    
    # Mixed precision
    scaler = torch.amp.GradScaler('cuda') if optuna_config.use_amp and device.type == 'cuda' else None
    
    # Training loop
    best_metric = 0.0
    epochs_without_improvement = 0
    threshold = params["threshold"]
    
    for epoch in range(optuna_config.max_epochs):
        # Training
        model.train()
        for batch in train_loader:
            spectra = batch['spectrum'].to(device)
            presence = batch['presence_labels'].to(device)
            activities = batch['activity_labels'].to(device)
            
            optimizer.zero_grad()
            
            if scaler is not None:
                with torch.amp.autocast('cuda'):
                    pred_logits, pred_activities = model(spectra)
                    loss, _ = loss_fn(pred_logits, pred_activities, presence, activities)
                
                scaler.scale(loss).backward()
                scaler.step(optimizer)
                scaler.update()
            else:
                pred_logits, pred_activities = model(spectra)
                loss, _ = loss_fn(pred_logits, pred_activities, presence, activities)
                loss.backward()
                optimizer.step()
        
        # Validation
        val_metrics = validate_model(model, val_loader, device, threshold, loss_fn)
        
        # Update scheduler
        if params["scheduler"] == "cosine":
            scheduler.step()
        else:
            scheduler.step(val_metrics['val_loss'])
        
        # Get objective metric
        current_metric = val_metrics.get(optuna_config.optimize_metric, val_metrics['val_recall'])
        
        # Report to Optuna for pruning
        trial.report(current_metric, epoch)
        
        # Handle pruning
        if trial.should_prune():
            raise optuna.TrialPruned()
        
        # Track best
        if current_metric > best_metric:
            best_metric = current_metric
            epochs_without_improvement = 0
        else:
            epochs_without_improvement += 1
        
        # Early stopping
        if epochs_without_improvement >= optuna_config.patience:
            break
    
    return best_metric


@torch.no_grad()
def validate_model(
    model: VegaModel,
    val_loader,
    device: torch.device,
    threshold: float,
    loss_fn: nn.Module
) -> Dict[str, float]:
    """Validate model and return comprehensive metrics."""
    model.eval()
    
    all_probs = []
    all_preds = []
    all_labels = []
    total_loss = 0.0
    num_batches = 0
    
    for batch in val_loader:
        spectra = batch['spectrum'].to(device)
        presence = batch['presence_labels'].to(device)
        activities = batch['activity_labels'].to(device)
        
        pred_logits, pred_activities = model(spectra)
        loss, _ = loss_fn(pred_logits, pred_activities, presence, activities)
        total_loss += loss.item()
        num_batches += 1
        
        # Get predictions
        probs = torch.sigmoid(pred_logits)
        preds = (probs >= threshold).float()
        
        all_probs.append(probs.cpu().numpy())
        all_preds.append(preds.cpu().numpy())
        all_labels.append(presence.cpu().numpy())
    
    # Concatenate
    all_probs = np.vstack(all_probs)
    all_preds = np.vstack(all_preds)
    all_labels = np.vstack(all_labels)
    
    # Calculate metrics
    metrics = {
        'val_loss': total_loss / num_batches,
        'val_accuracy': (all_preds == all_labels).mean()
    }
    
    # Per-sample exact match
    exact_matches = (all_preds == all_labels).all(axis=1).mean()
    metrics['val_exact_match'] = exact_matches
    
    # Sklearn metrics (handle edge cases)
    try:
        # Only for columns with both classes present
        valid_cols = (all_labels.sum(axis=0) > 0) & (all_labels.sum(axis=0) < len(all_labels))
        if valid_cols.any():
            metrics['val_auc_macro'] = roc_auc_score(
                all_labels[:, valid_cols],
                all_probs[:, valid_cols],
                average='macro'
            )
    except Exception:
        metrics['val_auc_macro'] = 0.5
    
    # Flatten for F1, precision, recall
    all_preds_flat = all_preds.flatten()
    all_labels_flat = all_labels.flatten()
    
    metrics['val_f1_macro'] = f1_score(all_labels_flat, all_preds_flat, average='macro', zero_division=0)
    metrics['val_precision'] = precision_score(all_labels_flat, all_preds_flat, average='macro', zero_division=0)
    metrics['val_recall'] = recall_score(all_labels_flat, all_preds_flat, average='macro', zero_division=0)
    metrics['val_hamming'] = hamming_loss(all_labels, all_preds)
    
    return metrics


def objective(trial: Trial, optuna_config: OptunaConfig) -> float:
    """
    Optuna objective function.
    
    Returns the metric to maximize (recall by default).
    """
    # Suggest hyperparameters
    params = suggest_hyperparameters(trial)
    
    # Log parameters
    print(f"\n{'='*60}")
    print(f"Trial {trial.number}")
    print(f"{'='*60}")
    for k, v in params.items():
        print(f"  {k}: {v}")
    
    # Setup device
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    
    # Get isotope index
    isotope_index = get_default_isotope_index()
    
    # Create data loaders with trial's batch size
    train_loader, val_loader, _ = create_data_loaders(
        data_dir=Path(optuna_config.data_dir),
        batch_size=params["batch_size"],
        train_split=optuna_config.train_split,
        val_split=optuna_config.val_split,
        test_split=optuna_config.test_split,
        num_workers=optuna_config.num_workers,
        prefetch_factor=optuna_config.prefetch_factor,
        persistent_workers=optuna_config.persistent_workers,
        isotope_index=isotope_index,
        seed=optuna_config.seed
    )
    
    try:
        metric = train_single_trial(
            trial, params, train_loader, val_loader, device, optuna_config
        )
        print(f"Trial {trial.number} completed with {optuna_config.optimize_metric}: {metric:.4f}")
        return metric
    except Exception as e:
        print(f"Trial {trial.number} failed: {e}")
        raise


def run_optimization(config: OptunaConfig) -> optuna.Study:
    """
    Run the full Optuna optimization study.
    """
    # Create model directory
    model_dir = Path(config.model_dir)
    model_dir.mkdir(parents=True, exist_ok=True)
    
    # Create study with TPE sampler and Hyperband pruner
    sampler = TPESampler(
        n_startup_trials=config.n_startup_trials,
        seed=config.seed
    )
    pruner = HyperbandPruner(
        min_resource=3,
        max_resource=config.max_epochs,
        reduction_factor=3
    )
    
    # Create or load study
    storage = f"sqlite:///{model_dir / config.study_name}.db"
    study = optuna.create_study(
        study_name=config.study_name,
        storage=storage,
        load_if_exists=True,
        direction="maximize",  # Maximize recall
        sampler=sampler,
        pruner=pruner
    )
    
    print("\n" + "=" * 60)
    print("VEGA V2 - OPTUNA HYPERPARAMETER OPTIMIZATION")
    print("=" * 60)
    print(f"Study name: {config.study_name}")
    print(f"Optimization metric: {config.optimize_metric}")
    print(f"Number of trials: {config.n_trials}")
    print(f"Timeout: {config.timeout_hours} hours")
    print(f"Data directory: {config.data_dir}")
    print("=" * 60 + "\n")
    
    # Run optimization
    study.optimize(
        lambda trial: objective(trial, config),
        n_trials=config.n_trials,
        timeout=config.timeout_hours * 3600,
        show_progress_bar=True,
        gc_after_trial=True
    )
    
    # Print results
    print("\n" + "=" * 60)
    print("OPTIMIZATION COMPLETE")
    print("=" * 60)
    print(f"Best trial: {study.best_trial.number}")
    print(f"Best {config.optimize_metric}: {study.best_value:.4f}")
    print("\nBest hyperparameters:")
    for k, v in study.best_params.items():
        print(f"  {k}: {v}")
    
    # Save best parameters
    best_params_path = model_dir / "best_params.json"
    with open(best_params_path, 'w') as f:
        json.dump({
            'best_value': study.best_value,
            'best_params': study.best_params,
            'study_name': config.study_name,
            'optimize_metric': config.optimize_metric
        }, f, indent=2)
    print(f"\nBest parameters saved to: {best_params_path}")
    
    return study


def train_best_model(
    study: optuna.Study,
    config: OptunaConfig,
    full_epochs: int = 100
) -> Tuple[VegaModel, Dict]:
    """
    Train the best model from the study with full epochs.
    """
    print("\n" + "=" * 60)
    print("TRAINING BEST MODEL")
    print("=" * 60)
    
    best_params = study.best_params
    
    # Reconstruct full params dict from best_params
    params = {}
    
    # CNN layers
    n_conv_layers = best_params.get("n_conv_layers", 3)
    conv_channels = [best_params.get(f"conv_ch_{i}", 128) for i in range(n_conv_layers)]
    params["conv_channels"] = conv_channels
    params["conv_kernel_size"] = best_params.get("conv_kernel_size", 7)
    params["pool_size"] = best_params.get("pool_size", 2)
    
    # FC layers
    n_fc_layers = best_params.get("n_fc_layers", 2)
    fc_dims = [best_params.get(f"fc_dim_{i}", 256) for i in range(n_fc_layers)]
    params["fc_hidden_dims"] = fc_dims
    
    # Other params
    for key in ["dropout_rate", "spatial_dropout_rate", "leaky_relu_slope",
                "batch_size", "learning_rate", "weight_decay", "optimizer",
                "scheduler", "lr_factor", "lr_patience", "cosine_t_0", "cosine_t_mult",
                "use_focal_loss", "focal_alpha", "focal_gamma",
                "classification_weight", "regression_weight", "threshold"]:
        if key in best_params:
            params[key] = best_params[key]
    
    # Set defaults for missing params
    params.setdefault("dropout_rate", 0.3)
    params.setdefault("spatial_dropout_rate", 0.1)
    params.setdefault("leaky_relu_slope", 0.1)
    params.setdefault("batch_size", 512)
    params.setdefault("learning_rate", 1e-3)
    params.setdefault("weight_decay", 1e-4)
    params.setdefault("optimizer", "adamw")
    params.setdefault("scheduler", "plateau")
    params.setdefault("classification_weight", 1.0)
    params.setdefault("regression_weight", 0.1)
    params.setdefault("threshold", 0.5)
    params.setdefault("use_focal_loss", True)
    params.setdefault("focal_alpha", 0.25)
    params.setdefault("focal_gamma", 2.0)
    
    print("Training with parameters:")
    for k, v in params.items():
        print(f"  {k}: {v}")
    
    # Setup
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    isotope_index = get_default_isotope_index()
    model_dir = Path(config.model_dir)
    
    # Create data loaders
    train_loader, val_loader, test_loader = create_data_loaders(
        data_dir=Path(config.data_dir),
        batch_size=params["batch_size"],
        train_split=config.train_split,
        val_split=config.val_split,
        test_split=config.test_split,
        num_workers=config.num_workers,
        prefetch_factor=config.prefetch_factor,
        persistent_workers=config.persistent_workers,
        isotope_index=isotope_index,
        seed=config.seed
    )
    
    # Create model
    model = create_model_from_params(params, isotope_index.num_isotopes)
    model = model.to(device)
    
    # Create loss
    loss_fn = VegaLossV2(
        classification_weight=params["classification_weight"],
        regression_weight=params["regression_weight"],
        use_focal_loss=params.get("use_focal_loss", False),
        focal_alpha=params.get("focal_alpha", 0.25),
        focal_gamma=params.get("focal_gamma", 2.0)
    )
    
    # Optimizer
    if params["optimizer"] == "adamw":
        optimizer = AdamW(model.parameters(), lr=params["learning_rate"], weight_decay=params["weight_decay"])
    else:
        optimizer = Adam(model.parameters(), lr=params["learning_rate"], weight_decay=params["weight_decay"])
    
    # Scheduler
    if params.get("scheduler") == "cosine":
        scheduler = CosineAnnealingWarmRestarts(
            optimizer, T_0=params.get("cosine_t_0", 10), T_mult=params.get("cosine_t_mult", 1)
        )
    else:
        scheduler = ReduceLROnPlateau(
            optimizer, mode='min', patience=params.get("lr_patience", 5), factor=params.get("lr_factor", 0.5)
        )
    
    # Mixed precision
    scaler = torch.amp.GradScaler('cuda') if config.use_amp and device.type == 'cuda' else None
    
    # Training
    best_recall = 0.0
    threshold = params["threshold"]
    history = []
    
    for epoch in range(full_epochs):
        # Train
        model.train()
        train_loss = 0.0
        num_batches = 0
        
        for batch in train_loader:
            spectra = batch['spectrum'].to(device)
            presence = batch['presence_labels'].to(device)
            activities = batch['activity_labels'].to(device)
            
            optimizer.zero_grad()
            
            if scaler is not None:
                with torch.amp.autocast('cuda'):
                    pred_logits, pred_activities = model(spectra)
                    loss, _ = loss_fn(pred_logits, pred_activities, presence, activities)
                scaler.scale(loss).backward()
                scaler.step(optimizer)
                scaler.update()
            else:
                pred_logits, pred_activities = model(spectra)
                loss, _ = loss_fn(pred_logits, pred_activities, presence, activities)
                loss.backward()
                optimizer.step()
            
            train_loss += loss.item()
            num_batches += 1
        
        train_loss /= num_batches
        
        # Validate
        val_metrics = validate_model(model, val_loader, device, threshold, loss_fn)
        
        # Scheduler step
        if params.get("scheduler") == "cosine":
            scheduler.step()
        else:
            scheduler.step(val_metrics['val_loss'])
        
        # Log
        lr = optimizer.param_groups[0]['lr']
        print(f"Epoch {epoch+1:3d}/{full_epochs} | Train Loss: {train_loss:.4f} | "
              f"Val Loss: {val_metrics['val_loss']:.4f} | Recall: {val_metrics['val_recall']:.4f} | "
              f"F1: {val_metrics['val_f1_macro']:.4f} | Exact: {val_metrics['val_exact_match']:.4f} | LR: {lr:.2e}")
        
        # Save history
        history.append({
            'epoch': epoch,
            'train_loss': train_loss,
            **val_metrics,
            'lr': lr
        })
        
        # Save best model
        if val_metrics['val_recall'] > best_recall:
            best_recall = val_metrics['val_recall']
            checkpoint = {
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': optimizer.state_dict(),
                'best_recall': best_recall,
                'params': params,
                'val_metrics': val_metrics
            }
            torch.save(checkpoint, model_dir / "vega_v2_best.pt")
            print(f"  └── New best! Saved model with recall: {best_recall:.4f}")
    
    # Save final model
    torch.save({
        'model_state_dict': model.state_dict(),
        'params': params,
        'history': history
    }, model_dir / "vega_v2_final.pt")
    
    # Save history
    with open(model_dir / "vega_v2_history.json", 'w') as f:
        json.dump(history, f, indent=2)
    
    # Test evaluation
    print("\n" + "=" * 60)
    print("TEST SET EVALUATION")
    print("=" * 60)
    test_metrics = validate_model(model, test_loader, device, threshold, loss_fn)
    for k, v in test_metrics.items():
        print(f"  {k}: {v:.4f}")
    
    return model, {
        'best_recall': best_recall,
        'test_metrics': test_metrics,
        'history': history,
        'params': params
    }


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description="Vega V2 - Optuna Hyperparameter Optimization")
    
    parser.add_argument("--data-dir", type=str, default="O:/master_data_collection/isotopev2",
                        help="Data directory")
    parser.add_argument("--model-dir", type=str, default="models/optuna",
                        help="Model output directory")
    parser.add_argument("--study-name", type=str, default="vega_v2_optimization",
                        help="Optuna study name")
    parser.add_argument("--n-trials", type=int, default=50,
                        help="Number of Optuna trials")
    parser.add_argument("--timeout", type=float, default=24.0,
                        help="Timeout in hours")
    parser.add_argument("--max-epochs", type=int, default=30,
                        help="Max epochs per trial")
    parser.add_argument("--optimize-metric", type=str, default="val_recall",
                        choices=["val_recall", "val_f1_macro", "val_auc_macro", "val_exact_match"],
                        help="Metric to optimize")
    parser.add_argument("--train-best", action="store_true",
                        help="Train best model with full epochs after optimization")
    parser.add_argument("--full-epochs", type=int, default=100,
                        help="Epochs for training best model")
    parser.add_argument("--workers", type=int, default=8,
                        help="Number of data loading workers")
    
    args = parser.parse_args()
    
    # Create config
    config = OptunaConfig(
        data_dir=args.data_dir,
        model_dir=args.model_dir,
        study_name=args.study_name,
        n_trials=args.n_trials,
        timeout_hours=args.timeout,
        max_epochs=args.max_epochs,
        optimize_metric=args.optimize_metric,
        num_workers=args.workers
    )
    
    # Run optimization
    study = run_optimization(config)
    
    # Optionally train best model
    if args.train_best:
        train_best_model(study, config, full_epochs=args.full_epochs)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
