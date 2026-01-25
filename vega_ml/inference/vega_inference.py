"""
Vega Inference Script

Load a trained Vega model and run inference on gamma spectra to identify
isotopes and estimate their activities.
"""

import sys
import json
import numpy as np
import torch
from pathlib import Path
from typing import Dict, List, Optional, Union
from dataclasses import dataclass, asdict

# Add project root to path
PROJECT_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from training.vega.model import VegaModel, VegaConfig
from training.vega.isotope_index import IsotopeIndex


@dataclass
class IsotopePrediction:
    """Prediction for a single isotope."""
    name: str
    probability: float
    activity_bq: float
    present: bool


@dataclass
class SpectrumPrediction:
    """Full prediction results for a spectrum."""
    isotopes: List[IsotopePrediction]
    num_present: int
    confidence: float
    threshold_used: float
    
    def to_dict(self) -> Dict:
        """Convert to dictionary."""
        return {
            'isotopes': [
                {
                    'name': iso.name,
                    'probability': round(iso.probability, 4),
                    'activity_bq': round(iso.activity_bq, 2),
                    'present': iso.present
                }
                for iso in self.isotopes
            ],
            'num_isotopes_detected': self.num_present,
            'confidence': round(self.confidence, 4),
            'threshold': self.threshold_used
        }
    
    def get_present_isotopes(self) -> List[IsotopePrediction]:
        """Get only isotopes predicted as present."""
        return [iso for iso in self.isotopes if iso.present]
    
    def summary(self) -> str:
        """Get a human-readable summary."""
        present = self.get_present_isotopes()
        if not present:
            return "No isotopes detected above threshold"
        
        lines = [f"Detected {len(present)} isotope(s):"]
        for iso in sorted(present, key=lambda x: -x.probability):
            lines.append(
                f"  - {iso.name}: {iso.probability*100:.1f}% confidence, "
                f"{iso.activity_bq:.1f} Bq"
            )
        return "\n".join(lines)


class VegaInference:
    """
    Inference engine for the Vega model.
    
    Loads a trained model and provides methods for running predictions
    on gamma spectra.
    """
    
    def __init__(
        self,
        model_path: Union[str, Path],
        isotope_index_path: Optional[Union[str, Path]] = None,
        device: Optional[torch.device] = None
    ):
        """
        Initialize the inference engine.
        
        Args:
            model_path: Path to the saved model checkpoint
            isotope_index_path: Path to isotope index file. If None, will try
                               to find it in the same directory as the model.
            device: Device to run inference on. If None, uses CUDA if available.
        """
        self.model_path = Path(model_path)
        
        # Determine device with CUDA compatibility test
        if device is not None:
            self.device = device
        elif torch.cuda.is_available():
            # Test if CUDA actually works (RTX 5090/Blackwell may not be compatible)
            try:
                test_tensor = torch.zeros(1, device='cuda')
                _ = test_tensor + 1
                self.device = torch.device('cuda')
                print("Using CUDA for inference")
            except RuntimeError as e:
                if "no kernel image is available" in str(e):
                    print(f"CUDA device detected but not compatible (likely Blackwell arch)")
                    print("Falling back to CPU for inference")
                    self.device = torch.device('cpu')
                else:
                    raise
        else:
            self.device = torch.device('cpu')
            print("Using CPU for inference")
        
        # Load checkpoint
        print(f"Loading model from: {self.model_path}")
        self.checkpoint = torch.load(self.model_path, map_location=self.device)
        
        # Load model config
        model_config_dict = self.checkpoint['model_config']
        self.model_config = VegaConfig(**model_config_dict)
        
        # Create and load model
        self.model = VegaModel(self.model_config)
        self.model.load_state_dict(self.checkpoint['model_state_dict'])
        self.model = self.model.to(self.device)
        self.model.eval()
        
        # Load isotope index
        if isotope_index_path is None:
            # Try to find in same directory
            isotope_index_path = self.model_path.parent / "vega_isotope_index.txt"
        
        if Path(isotope_index_path).exists():
            self.isotope_index = IsotopeIndex.load(Path(isotope_index_path))
        else:
            # Use default
            from training.vega.isotope_index import get_default_isotope_index
            self.isotope_index = get_default_isotope_index()
            print("Warning: Using default isotope index")
        
        print(f"Model loaded successfully!")
        print(f"Device: {self.device}")
        print(f"Isotopes: {self.isotope_index.num_isotopes}")
    
    def preprocess_spectrum(
        self,
        spectrum: np.ndarray,
        normalize: bool = True
    ) -> torch.Tensor:
        """
        Preprocess a spectrum for inference.
        
        Args:
            spectrum: Input spectrum array. Can be:
                     - 1D: (channels,) - single spectrum
                     - 2D: (time, channels) - will be averaged over time
            normalize: Whether to normalize to [0, 1]
            
        Returns:
            Preprocessed tensor ready for model
        """
        # Handle 2D spectra
        if spectrum.ndim == 2:
            spectrum = spectrum.mean(axis=0)
        
        # Normalize
        if normalize and spectrum.max() > 0:
            spectrum = spectrum / spectrum.max()
        
        # Convert to tensor
        tensor = torch.tensor(spectrum, dtype=torch.float32)
        
        # Add batch dimension
        tensor = tensor.unsqueeze(0)
        
        return tensor.to(self.device)
    
    @torch.no_grad()
    def predict(
        self,
        spectrum: Union[np.ndarray, torch.Tensor],
        threshold: float = 0.5,
        return_all: bool = False
    ) -> SpectrumPrediction:
        """
        Run inference on a spectrum.
        
        Args:
            spectrum: Input spectrum (numpy array or tensor)
            threshold: Probability threshold for considering an isotope present
            return_all: If True, include all isotopes in output. If False,
                       only include those above threshold.
                       
        Returns:
            SpectrumPrediction with isotope predictions
        """
        # Preprocess if numpy
        if isinstance(spectrum, np.ndarray):
            spectrum = self.preprocess_spectrum(spectrum)
        
        # Run model (outputs logits)
        logits, activities = self.model(spectrum)
        
        # Apply sigmoid to get probabilities
        probs = torch.sigmoid(logits)
        
        # Convert to numpy
        probs = probs.cpu().numpy()[0]
        activities = activities.cpu().numpy()[0]
        
        # Scale activities
        activities = activities * self.model_config.max_activity_bq
        
        # Create predictions
        isotopes = []
        for i in range(len(probs)):
            prob = float(probs[i])
            activity = float(activities[i])
            present = prob >= threshold
            
            if return_all or present:
                isotopes.append(IsotopePrediction(
                    name=self.isotope_index.index_to_name(i),
                    probability=prob,
                    activity_bq=activity if present else 0.0,
                    present=present
                ))
        
        # Calculate overall confidence (average of top predictions)
        present_isotopes = [iso for iso in isotopes if iso.present]
        if present_isotopes:
            confidence = np.mean([iso.probability for iso in present_isotopes])
        else:
            confidence = 1.0 - probs.max()  # Confidence in "background only"
        
        return SpectrumPrediction(
            isotopes=isotopes,
            num_present=len(present_isotopes),
            confidence=float(confidence),
            threshold_used=threshold
        )
    
    def predict_batch(
        self,
        spectra: List[np.ndarray],
        threshold: float = 0.5
    ) -> List[SpectrumPrediction]:
        """
        Run inference on multiple spectra.
        
        Args:
            spectra: List of spectrum arrays
            threshold: Probability threshold
            
        Returns:
            List of predictions
        """
        return [self.predict(s, threshold) for s in spectra]
    
    def predict_from_file(
        self,
        file_path: Union[str, Path],
        threshold: float = 0.5
    ) -> SpectrumPrediction:
        """
        Load a spectrum from a numpy file and run inference.
        
        Args:
            file_path: Path to .npy file
            threshold: Probability threshold
            
        Returns:
            SpectrumPrediction
        """
        spectrum = np.load(file_path)
        return self.predict(spectrum, threshold)


def run_inference_demo(
    model_path: str,
    data_dir: str,
    threshold: float = 0.5
):
    """
    Demo function to run inference on test data.
    
    Args:
        model_path: Path to model checkpoint
        data_dir: Path to data directory with spectra
        threshold: Detection threshold
    """
    # Initialize inference engine
    inference = VegaInference(model_path)
    
    # Find spectra files
    data_path = Path(data_dir)
    spectra_dir = data_path / "spectra"
    
    if not spectra_dir.exists():
        print(f"Spectra directory not found: {spectra_dir}")
        return
    
    # Load labels for comparison
    labels_path = data_path / "labels.json"
    with open(labels_path, 'r') as f:
        labels = json.load(f)
    
    print("\n" + "=" * 70)
    print("VEGA INFERENCE DEMO")
    print("=" * 70)
    
    # Process each spectrum
    npy_files = list(spectra_dir.glob("*.npy"))
    print(f"\nFound {len(npy_files)} spectra to process\n")
    
    for npy_file in npy_files:
        # Extract sample ID from filename
        sample_id = npy_file.stem.replace("spectrum_", "")
        
        # Get ground truth
        if sample_id in labels['samples']:
            ground_truth = labels['samples'][sample_id]
            true_isotopes = ground_truth['isotopes']
            true_activities = ground_truth.get('source_activities_bq', {})
        else:
            true_isotopes = []
            true_activities = {}
        
        # Run prediction
        prediction = inference.predict_from_file(npy_file, threshold=threshold)
        
        # Display results
        print("-" * 70)
        print(f"Sample: {sample_id}")
        print(f"Ground Truth Isotopes: {true_isotopes if true_isotopes else 'Background only'}")
        if true_activities:
            activities_str = ", ".join(
                f"{k}: {v:.1f} Bq" for k, v in true_activities.items()
            )
            print(f"Ground Truth Activities: {activities_str}")
        
        print(f"\nPrediction:")
        print(prediction.summary())
        
        # Compare
        predicted_names = {iso.name for iso in prediction.get_present_isotopes()}
        true_names = set(true_isotopes)
        
        correct = predicted_names & true_names
        missed = true_names - predicted_names
        false_positives = predicted_names - true_names
        
        if correct:
            print(f"\n✓ Correctly identified: {correct}")
        if missed:
            print(f"✗ Missed: {missed}")
        if false_positives:
            print(f"! False positives: {false_positives}")
        
        print()
    
    print("=" * 70)
    print("Inference complete!")
    print("=" * 70)


if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Run Vega model inference")
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
        help="Path to data directory"
    )
    parser.add_argument(
        "--threshold", "-t",
        type=float,
        default=0.5,
        help="Detection threshold (0-1)"
    )
    
    args = parser.parse_args()
    
    # Make paths absolute if needed
    project_root = Path(__file__).parent.parent
    model_path = args.model if Path(args.model).is_absolute() else project_root / args.model
    data_path = args.data if Path(args.data).is_absolute() else project_root / args.data
    
    run_inference_demo(str(model_path), str(data_path), args.threshold)
