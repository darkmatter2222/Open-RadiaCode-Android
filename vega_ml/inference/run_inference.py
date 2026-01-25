#!/usr/bin/env python
"""
Run Vega Inference

Simple script to run inference with a trained Vega model.
"""

import sys
import argparse
from pathlib import Path

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from inference.vega_inference import run_inference_demo, VegaInference


def main():
    parser = argparse.ArgumentParser(
        description="Run inference with trained Vega model"
    )
    
    parser.add_argument(
        "--model", "-m",
        type=str,
        default="models/vega_best.pt",
        help="Path to model checkpoint"
    )
    parser.add_argument(
        "--data", "-d",
        type=str,
        default="O:/master_data_collection/isotopev2",
        help="Path to data directory with spectra"
    )
    parser.add_argument(
        "--threshold", "-t",
        type=float,
        default=0.5,
        help="Detection threshold (0-1)"
    )
    parser.add_argument(
        "--spectrum", "-s",
        type=str,
        default=None,
        help="Path to a specific spectrum file to analyze"
    )
    
    args = parser.parse_args()
    
    # Make paths absolute
    model_path = Path(args.model)
    if not model_path.is_absolute():
        model_path = PROJECT_ROOT / model_path
    
    if args.spectrum:
        # Single spectrum inference
        spectrum_path = Path(args.spectrum)
        if not spectrum_path.is_absolute():
            spectrum_path = PROJECT_ROOT / spectrum_path
        
        print(f"\nLoading model from: {model_path}")
        inference = VegaInference(str(model_path))
        
        print(f"\nAnalyzing spectrum: {spectrum_path}")
        prediction = inference.predict_from_file(
            spectrum_path, 
            threshold=args.threshold
        )
        
        print("\n" + "=" * 60)
        print("PREDICTION RESULTS")
        print("=" * 60)
        print(prediction.summary())
        print("=" * 60)
        
    else:
        # Demo mode - analyze all spectra in data directory
        data_path = Path(args.data)
        if not data_path.is_absolute():
            data_path = PROJECT_ROOT / data_path
        
        run_inference_demo(
            str(model_path),
            str(data_path),
            threshold=args.threshold
        )
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
