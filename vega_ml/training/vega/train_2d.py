"""
Training Script for Vega 2D Model

Uses 2D convolutions to process gamma spectra with temporal information.
"""

import argparse
import json
import time
from pathlib import Path
from dataclasses import dataclass, asdict
from typing import Optional, Tuple, Dict, List

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.cuda.amp import GradScaler, autocast

from .model_2d import Vega2DModel, Vega2DConfig, count_parameters
from .dataset_2d import create_data_loaders_2d, SpectrumDataset2D
from .isotope_index import get_default_isotope_index


@dataclass
class TrainingConfig2D:
    """Training configuration for 2D model."""
    
    # Data
    data_dir: str = "O:/master_data_collection/isotopev2"
    model_dir: str = "models"
    
    # Model
    target_time_intervals: int = 60
    
    # Training
    epochs: int = 50
    batch_size: int = 32
    learning_rate: float = 1e-3
    weight_decay: float = 1e-5
    
    # Loss weights
    classification_weight: float = 1.0
    regression_weight: float = 0.1
    
    # Mixed precision
    use_amp: bool = True
    
    # Early stopping
    early_stopping_patience: int = 10
    
    # Learning rate scheduler
    lr_scheduler_patience: int = 5
    lr_scheduler_factor: float = 0.5
    
    # Data loading
    num_workers: int = 4


def train_epoch(
    model: nn.Module,
    train_loader,
    optimizer: optim.Optimizer,
    criterion_cls: nn.Module,
    criterion_reg: nn.Module,
    device: torch.device,
    scaler: Optional[GradScaler],
    config: TrainingConfig2D
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
                loss = config.classification_weight * cls_loss + config.regression_weight * reg_loss
            
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
        else:
            logits, pred_activities = model(spectra)
            cls_loss = criterion_cls(logits, presence)
            reg_loss = criterion_reg(pred_activities, activities)
            loss = config.classification_weight * cls_loss + config.regression_weight * reg_loss
            
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


@torch.no_grad()
def validate(
    model: nn.Module,
    val_loader,
    criterion_cls: nn.Module,
    criterion_reg: nn.Module,
    device: torch.device,
    config: TrainingConfig2D,
    threshold: float = 0.5
) -> Dict[str, float]:
    """Validate the model."""
    model.eval()
    
    total_loss = 0.0
    total_cls_loss = 0.0
    total_reg_loss = 0.0
    num_batches = 0
    
    all_preds = []
    all_labels = []
    
    for batch in val_loader:
        spectra = batch['spectrum'].to(device)
        presence = batch['presence_labels'].to(device)
        activities = batch['activity_labels'].to(device)
        
        logits, pred_activities = model(spectra)
        cls_loss = criterion_cls(logits, presence)
        reg_loss = criterion_reg(pred_activities, activities)
        loss = config.classification_weight * cls_loss + config.regression_weight * reg_loss
        
        total_loss += loss.item()
        total_cls_loss += cls_loss.item()
        total_reg_loss += reg_loss.item()
        num_batches += 1
        
        # Collect predictions for metrics
        probs = torch.sigmoid(logits)
        preds = (probs >= threshold).float()
        all_preds.append(preds.cpu())
        all_labels.append(presence.cpu())
    
    # Calculate metrics
    all_preds = torch.cat(all_preds, dim=0)
    all_labels = torch.cat(all_labels, dim=0)
    
    # Per-sample accuracy (all isotopes correct)
    exact_match = (all_preds == all_labels).all(dim=1).float().mean().item()
    
    # Per-isotope metrics
    tp = ((all_preds == 1) & (all_labels == 1)).sum().item()
    fp = ((all_preds == 1) & (all_labels == 0)).sum().item()
    fn = ((all_preds == 0) & (all_labels == 1)).sum().item()
    tn = ((all_preds == 0) & (all_labels == 0)).sum().item()
    
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    
    return {
        'loss': total_loss / num_batches,
        'cls_loss': total_cls_loss / num_batches,
        'reg_loss': total_reg_loss / num_batches,
        'exact_match': exact_match,
        'precision': precision,
        'recall': recall,
        'f1': f1
    }


def train_vega_2d(
    config: TrainingConfig2D = None,
    model_config: Vega2DConfig = None
) -> Tuple[Vega2DModel, Dict]:
    """
    Train the Vega 2D model.
    """
    config = config or TrainingConfig2D()
    model_config = model_config or Vega2DConfig(num_time_intervals=config.target_time_intervals)
    
    # Setup device
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"Using device: {device}")
    
    if device.type == 'cuda':
        print(f"  GPU: {torch.cuda.get_device_name()}")
        print(f"  Memory: {torch.cuda.get_device_properties(0).total_memory / 1e9:.1f} GB")
    
    # Create model
    model = Vega2DModel(model_config).to(device)
    print(f"\nModel: Vega 2D")
    print(f"  Input: ({model_config.num_time_intervals}, {model_config.num_channels})")
    print(f"  Conv channels: {model_config.conv_channels}")
    print(f"  FC dims: {model_config.fc_hidden_dims}")
    print(f"  Parameters: {count_parameters(model):,}")
    
    # Create data loaders
    print(f"\nLoading data from: {config.data_dir}")
    isotope_index = get_default_isotope_index()
    
    train_loader, val_loader, test_loader = create_data_loaders_2d(
        data_dir=Path(config.data_dir),
        batch_size=config.batch_size,
        target_time_intervals=config.target_time_intervals,
        isotope_index=isotope_index,
        num_workers=config.num_workers
    )
    
    # Loss functions
    criterion_cls = nn.BCEWithLogitsLoss()
    criterion_reg = nn.HuberLoss()
    
    # Optimizer
    optimizer = optim.AdamW(
        model.parameters(),
        lr=config.learning_rate,
        weight_decay=config.weight_decay
    )
    
    # Learning rate scheduler
    scheduler = optim.lr_scheduler.ReduceLROnPlateau(
        optimizer,
        mode='min',
        factor=config.lr_scheduler_factor,
        patience=config.lr_scheduler_patience
    )
    
    # Mixed precision scaler
    scaler = GradScaler() if config.use_amp and device.type == 'cuda' else None
    
    # Training history
    history = {
        'train_loss': [], 'val_loss': [],
        'train_cls_loss': [], 'val_cls_loss': [],
        'train_reg_loss': [], 'val_reg_loss': [],
        'val_exact_match': [], 'val_precision': [], 'val_recall': [], 'val_f1': [],
        'lr': []
    }
    
    # Early stopping
    best_val_loss = float('inf')
    patience_counter = 0
    
    # Model directory
    model_dir = Path(config.model_dir)
    model_dir.mkdir(exist_ok=True)
    
    print(f"\nStarting training for {config.epochs} epochs...")
    print(f"  Batch size: {config.batch_size}")
    print(f"  Learning rate: {config.learning_rate}")
    print(f"  AMP: {scaler is not None}")
    print()
    
    start_time = time.time()
    
    for epoch in range(config.epochs):
        epoch_start = time.time()
        
        # Train
        train_metrics = train_epoch(
            model, train_loader, optimizer,
            criterion_cls, criterion_reg,
            device, scaler, config
        )
        
        # Validate
        val_metrics = validate(
            model, val_loader,
            criterion_cls, criterion_reg,
            device, config
        )
        
        # Update scheduler
        scheduler.step(val_metrics['loss'])
        current_lr = optimizer.param_groups[0]['lr']
        
        # Record history
        history['train_loss'].append(train_metrics['loss'])
        history['val_loss'].append(val_metrics['loss'])
        history['train_cls_loss'].append(train_metrics['cls_loss'])
        history['val_cls_loss'].append(val_metrics['cls_loss'])
        history['train_reg_loss'].append(train_metrics['reg_loss'])
        history['val_reg_loss'].append(val_metrics['reg_loss'])
        history['val_exact_match'].append(val_metrics['exact_match'])
        history['val_precision'].append(val_metrics['precision'])
        history['val_recall'].append(val_metrics['recall'])
        history['val_f1'].append(val_metrics['f1'])
        history['lr'].append(current_lr)
        
        epoch_time = time.time() - epoch_start
        
        # Print progress
        print(f"Epoch {epoch+1:3d}/{config.epochs} ({epoch_time:.1f}s) | "
              f"Train Loss: {train_metrics['loss']:.4f} | "
              f"Val Loss: {val_metrics['loss']:.4f} | "
              f"F1: {val_metrics['f1']:.4f} | "
              f"Recall: {val_metrics['recall']:.4f} | "
              f"LR: {current_lr:.2e}")
        
        # Save best model
        if val_metrics['loss'] < best_val_loss:
            best_val_loss = val_metrics['loss']
            patience_counter = 0
            
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': optimizer.state_dict(),
                'model_config': asdict(model_config),
                'training_config': asdict(config),
                'val_metrics': val_metrics,
                'history': history
            }, model_dir / 'vega_2d_best.pt')
            print(f"  ✓ Saved best model (val_loss: {best_val_loss:.4f})")
        else:
            patience_counter += 1
        
        # Early stopping
        if patience_counter >= config.early_stopping_patience:
            print(f"\nEarly stopping at epoch {epoch+1}")
            break
    
    total_time = time.time() - start_time
    print(f"\nTraining complete in {total_time/60:.1f} minutes")
    print(f"Best validation loss: {best_val_loss:.4f}")
    
    # Save final model
    torch.save({
        'epoch': epoch,
        'model_state_dict': model.state_dict(),
        'model_config': asdict(model_config),
        'training_config': asdict(config),
        'history': history
    }, model_dir / 'vega_2d_final.pt')
    
    # Save history
    with open(model_dir / 'vega_2d_history.json', 'w') as f:
        json.dump(history, f, indent=2)
    
    # Test set evaluation
    print("\nEvaluating on test set...")
    test_metrics = validate(
        model, test_loader,
        criterion_cls, criterion_reg,
        device, config
    )
    print(f"  Test Loss: {test_metrics['loss']:.4f}")
    print(f"  Test F1: {test_metrics['f1']:.4f}")
    print(f"  Test Recall: {test_metrics['recall']:.4f}")
    print(f"  Test Precision: {test_metrics['precision']:.4f}")
    print(f"  Test Exact Match: {test_metrics['exact_match']:.4f}")
    
    return model, history


def main():
    parser = argparse.ArgumentParser(description='Train Vega 2D Model')
    parser.add_argument('--data-dir', type=str, default='O:/master_data_collection/isotopev2',
                        help='Path to training data')
    parser.add_argument('--model-dir', type=str, default='models',
                        help='Path to save models')
    parser.add_argument('--epochs', type=int, default=50,
                        help='Number of epochs')
    parser.add_argument('--batch-size', type=int, default=32,
                        help='Batch size')
    parser.add_argument('--lr', type=float, default=1e-3,
                        help='Learning rate')
    parser.add_argument('--time-intervals', type=int, default=60,
                        help='Target time intervals (pad/truncate)')
    parser.add_argument('--no-amp', action='store_true',
                        help='Disable mixed precision training')
    parser.add_argument('--workers', type=int, default=4,
                        help='Data loading workers')
    
    args = parser.parse_args()
    
    config = TrainingConfig2D(
        data_dir=args.data_dir,
        model_dir=args.model_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.lr,
        target_time_intervals=args.time_intervals,
        use_amp=not args.no_amp,
        num_workers=args.workers
    )
    
    model_config = Vega2DConfig(
        num_time_intervals=args.time_intervals
    )
    
    train_vega_2d(config, model_config)


if __name__ == '__main__':
    main()
