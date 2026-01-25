#!/usr/bin/env python
"""
Run Vega Training

Simple script to train the Vega model on synthetic gamma spectra.
Designed for both quick test runs and full-scale training.
"""

import sys
import argparse
from pathlib import Path

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.train import train_vega, TrainingConfig
from training.vega.model import VegaConfig


def main():
    parser = argparse.ArgumentParser(
        description="Train Vega model for isotope identification"
    )
    
    # Data paths
    parser.add_argument(
        "--data-dir", "-d",
        type=str,
        default="O:/master_data_collection/isotopev2",
        help="Path to synthetic data directory"
    )
    parser.add_argument(
        "--model-dir", "-m",
        type=str,
        default="models",
        help="Directory to save trained models"
    )
    
    # Training parameters
    parser.add_argument(
        "--epochs", "-e",
        type=int,
        default=100,
        help="Maximum number of training epochs"
    )
    parser.add_argument(
        "--batch-size", "-b",
        type=int,
        default=64,
        help="Batch size for training (default: 64 for better GPU utilization)"
    )
    parser.add_argument(
        "--learning-rate", "-lr",
        type=float,
        default=1e-3,
        help="Initial learning rate"
    )
    
    # Quick test mode
    parser.add_argument(
        "--test",
        action="store_true",
        help="Quick test mode with reduced epochs"
    )
    
    # Mixed precision
    parser.add_argument(
        "--no-amp",
        action="store_true",
        help="Disable automatic mixed precision training"
    )
    
    # Data loading parallelism
    parser.add_argument(
        "--workers", "-w",
        type=int,
        default=8,
        help="Number of data loading workers (default: 8 for parallel I/O)"
    )
    
    args = parser.parse_args()
    
    # Create training config
    config = TrainingConfig(
        data_dir=args.data_dir,
        model_dir=args.model_dir,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        num_epochs=args.epochs if not args.test else 5,
        patience=10 if not args.test else 3,
        use_amp=not args.no_amp,
        num_workers=args.workers
    )
    
    # Create model config
    model_config = VegaConfig()
    
    print("\n" + "=" * 60)
    print("VEGA TRAINING")
    print("=" * 60)
    print(f"Data directory: {args.data_dir}")
    print(f"Model directory: {args.model_dir}")
    print(f"Epochs: {config.num_epochs}")
    print(f"Batch size: {config.batch_size}")
    print(f"Learning rate: {config.learning_rate}")
    print(f"Mixed precision: {config.use_amp}")
    print(f"Data workers: {config.num_workers}")
    if args.test:
        print("MODE: Quick test run")
    print("=" * 60 + "\n")
    
    # Run training
    model, results = train_vega(config=config, model_config=model_config)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
