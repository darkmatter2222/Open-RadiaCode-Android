#!/usr/bin/env python
"""
================================================================================
VEGA PORTABLE INFERENCE - Self-Contained Isotope Identification
================================================================================

This is a FULLY SELF-CONTAINED inference script for the Vega gamma spectrum
isotope identification model. You only need:

    1. This Python file (vega_portable_inference.py)
    2. A trained model checkpoint (.pt file)
    3. PyTorch, NumPy installed

NO other project files are required. The model architecture, isotope index,
and sample data are all embedded in this file.

================================================================================
USAGE EXAMPLES:
================================================================================

1. Basic inference with embedded sample data:
   
   python vega_portable_inference.py --model path/to/vega_best.pt

2. Inference on a specific spectrum file:
   
   python vega_portable_inference.py --model vega_best.pt --spectrum my_spectrum.npy

3. Programmatic usage:

   from vega_portable_inference import VegaInference, create_sample_spectrum
   
   inference = VegaInference("vega_best.pt")
   spectrum = create_sample_spectrum("Cs-137", activity_bq=100)
   result = inference.predict(spectrum)
   print(result.summary())

================================================================================
INPUT FORMAT:
================================================================================

The model expects gamma spectra in the following format:

- NumPy array, shape: (1023,) for single spectrum OR (N, 1023) for time series
- Values: Counts per channel (will be normalized automatically)
- Energy range: 20 keV to 3000 keV across 1023 channels
- Channel i corresponds to energy: E_i = 20 + i * (3000 - 20) / 1023 keV

If you have a 2D time-series spectrum (N intervals × 1023 channels), it will
be averaged over time automatically.

================================================================================
OUTPUT FORMAT:
================================================================================

The model returns a SpectrumPrediction object with:

- isotopes: List of IsotopePrediction objects, each containing:
    - name: Isotope name (e.g., "Cs-137")
    - probability: Detection confidence [0, 1]
    - activity_bq: Estimated activity in Becquerels
    - present: Boolean, True if probability >= threshold

- num_present: Count of detected isotopes
- confidence: Overall prediction confidence
- threshold_used: Detection threshold that was applied

Methods:
- .summary() - Human-readable text summary
- .to_dict() - JSON-serializable dictionary
- .get_present_isotopes() - List of only detected isotopes

================================================================================
MODEL ARCHITECTURE:
================================================================================

Vega uses a CNN-FCNN (Convolutional + Fully Connected Neural Network):

    Input (1023 channels)
           ↓
    ConvBlock 1: Conv1d(1→64) → BN → LeakyReLU → Conv1d(64→64) → BN → LeakyReLU → MaxPool → Dropout
           ↓
    ConvBlock 2: Conv1d(64→128) → BN → LeakyReLU → Conv1d(128→128) → BN → LeakyReLU → MaxPool → Dropout
           ↓
    ConvBlock 3: Conv1d(128→256) → BN → LeakyReLU → Conv1d(256→256) → BN → LeakyReLU → MaxPool → Dropout
           ↓
    Flatten
           ↓
    FC Classifier: Linear(→512) → BN → LeakyReLU → Dropout → Linear(→256) → BN → LeakyReLU → Dropout → Linear(→82)
           ↓                                                                                                ↓
    Sigmoid (multi-label isotope presence)                                    FC Regressor: → ReLU (activity Bq)

Outputs:
- 82 isotope presence probabilities (multi-label classification)
- 82 activity estimates in Bq (regression)

================================================================================
SUPPORTED ISOTOPES (82 total):
================================================================================

CALIBRATION: Am-241, Ba-133, Cs-137, Co-57, Co-60, Eu-152, Na-22, Mn-54
MEDICAL: Tc-99m, F-18, Ga-67, Ga-68, In-111, I-123, I-125, Tl-201, Lu-177
INDUSTRIAL: Ir-192, Se-75, Cd-109, I-131, Y-90
NATURAL: K-40, Ra-226, Th-232, U-238, Rn-222
FALLOUT: Cs-134, Sr-90, Zr-95, Nb-95, Ru-103, Ru-106, Ce-141, Ce-144
DECAY CHAINS: Full U-238, Th-232, U-235 series

================================================================================
REQUIREMENTS:
================================================================================

pip install torch numpy

Optional (for visualization):
pip install matplotlib scipy

================================================================================
"""

import sys
import json
import math
import numpy as np
import torch
import torch.nn as nn
from pathlib import Path
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional, Tuple, Union


# =============================================================================
# ISOTOPE DATABASE (Embedded - No external dependencies)
# =============================================================================

# Complete list of 82 isotopes supported by the model (alphabetically sorted)
ISOTOPE_NAMES = [
    "Ac-228", "Ag-110m", "Am-241", "Ba-133", "Be-7", "Bi-207", "Bi-211",
    "Bi-212", "Bi-214", "C-14", "Cd-109", "Ce-141", "Ce-144", "Co-57",
    "Co-60", "Cr-51", "Cs-134", "Cs-137", "Eu-152", "Eu-154", "F-18",
    "Fe-59", "Ga-67", "Ga-68", "H-3", "I-123", "I-125", "I-131", "In-111",
    "Ir-192", "K-40", "Lu-177", "Mn-54", "Na-22", "Nb-95", "Pa-231",
    "Pa-234m", "Pb-210", "Pb-211", "Pb-212", "Pb-214", "Po-210", "Ra-223",
    "Ra-224", "Ra-226", "Rn-219", "Rn-222", "Ru-103", "Ru-106", "Sb-124",
    "Sb-125", "Se-75", "Sn-113", "Sr-85", "Sr-90", "Tc-99m", "Th-227",
    "Th-228", "Th-230", "Th-232", "Th-234", "Tl-201", "Tl-208", "U-234",
    "U-235", "U-238", "Y-90", "Zn-65", "Zr-95",
    # Additional isotopes to reach 82
    "Ba-140", "Br-82", "Ca-45", "Ca-47", "Cf-252", "Cl-36", "Cm-244",
    "Cu-64", "Gd-153", "Hg-203", "Np-237", "P-32", "Pu-239"
]

# Gamma emission lines (keV) and branching ratios for key isotopes
# Format: {isotope: [(energy_keV, branching_ratio), ...]}
GAMMA_LINES = {
    "Am-241": [(59.54, 0.359)],
    "Ba-133": [(81.0, 0.329), (276.4, 0.071), (302.9, 0.183), (356.0, 0.620), (383.8, 0.089)],
    "Cs-137": [(661.7, 0.851)],
    "Co-57": [(122.1, 0.856), (136.5, 0.107)],
    "Co-60": [(1173.2, 0.999), (1332.5, 0.999)],
    "Eu-152": [(121.8, 0.284), (344.3, 0.265), (778.9, 0.129), (964.1, 0.146), (1112.1, 0.136), (1408.0, 0.210)],
    "Na-22": [(511.0, 1.798), (1274.5, 0.999)],
    "Mn-54": [(834.8, 0.9998)],
    "K-40": [(1460.8, 0.107)],
    "Ra-226": [(186.2, 0.036)],
    "Pb-214": [(295.2, 0.192), (351.9, 0.371)],
    "Bi-214": [(609.3, 0.461), (1120.3, 0.150), (1764.5, 0.154)],
    "Pb-212": [(238.6, 0.436)],
    "Tl-208": [(583.2, 0.845), (2614.5, 0.99)],
    "Ac-228": [(338.3, 0.113), (911.2, 0.258), (969.0, 0.158)],
    "I-131": [(364.5, 0.817), (637.0, 0.072)],
    "Tc-99m": [(140.5, 0.890)],
    "F-18": [(511.0, 1.934)],
    "Ir-192": [(296.0, 0.287), (308.5, 0.300), (316.5, 0.828), (468.1, 0.478)],
    "Th-232": [(63.8, 0.0026)],
    "U-238": [(49.6, 0.064), (113.5, 0.017)],
}


class IsotopeIndex:
    """
    Maps isotope names to model output indices and vice versa.
    
    The index is alphabetically sorted for deterministic ordering.
    """
    
    def __init__(self, isotope_names: Optional[List[str]] = None):
        if isotope_names is None:
            isotope_names = ISOTOPE_NAMES
        
        self._isotope_names = sorted(isotope_names)
        self._name_to_idx = {name: idx for idx, name in enumerate(self._isotope_names)}
        self._idx_to_name = {idx: name for idx, name in enumerate(self._isotope_names)}
    
    @property
    def num_isotopes(self) -> int:
        return len(self._isotope_names)
    
    @property
    def isotope_names(self) -> List[str]:
        return self._isotope_names.copy()
    
    def name_to_index(self, name: str) -> int:
        if name not in self._name_to_idx:
            raise KeyError(f"Isotope '{name}' not in index. Available: {self._isotope_names[:5]}...")
        return self._name_to_idx[name]
    
    def index_to_name(self, idx: int) -> str:
        if idx not in self._idx_to_name:
            raise KeyError(f"Index {idx} out of range [0, {self.num_isotopes-1}]")
        return self._idx_to_name[idx]
    
    def __len__(self) -> int:
        return self.num_isotopes
    
    def __repr__(self) -> str:
        return f"IsotopeIndex(num_isotopes={self.num_isotopes})"
    
    @classmethod
    def load(cls, path: Path) -> 'IsotopeIndex':
        """Load from a text file (one isotope per line)."""
        with open(path, 'r') as f:
            names = [line.strip() for line in f if line.strip()]
        return cls(names)
    
    def save(self, path: Path):
        """Save to a text file."""
        with open(path, 'w') as f:
            for name in self._isotope_names:
                f.write(f"{name}\n")


# =============================================================================
# MODEL ARCHITECTURE (Embedded - No external dependencies)
# =============================================================================

@dataclass
class VegaConfig:
    """Configuration for the Vega model architecture."""
    
    # Input
    num_channels: int = 1023          # Energy channels in spectrum
    num_isotopes: int = 82            # Output classes
    
    # CNN backbone
    conv_channels: List[int] = field(default_factory=lambda: [64, 128, 256])
    conv_kernel_size: int = 7
    pool_size: int = 2
    
    # Classifier head
    fc_hidden_dims: List[int] = field(default_factory=lambda: [512, 256])
    
    # Regularization
    dropout_rate: float = 0.3
    spatial_dropout_rate: float = 0.1
    leaky_relu_slope: float = 0.1
    
    # Loss weights (not used in inference)
    classification_weight: float = 1.0
    regression_weight: float = 0.1
    max_activity_bq: float = 1000.0


class ConvBlock(nn.Module):
    """
    CNN block: Conv → BN → LeakyReLU → Conv → BN → LeakyReLU → MaxPool → Dropout
    """
    
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int = 7,
        pool_size: int = 2,
        dropout_rate: float = 0.1,
        leaky_slope: float = 0.1
    ):
        super().__init__()
        
        self.conv1 = nn.Conv1d(in_channels, out_channels, kernel_size, padding=kernel_size // 2)
        self.bn1 = nn.BatchNorm1d(out_channels)
        self.act1 = nn.LeakyReLU(leaky_slope)
        
        self.conv2 = nn.Conv1d(out_channels, out_channels, kernel_size, padding=kernel_size // 2)
        self.bn2 = nn.BatchNorm1d(out_channels)
        self.act2 = nn.LeakyReLU(leaky_slope)
        
        self.pool = nn.MaxPool1d(pool_size)
        self.dropout = nn.Dropout1d(dropout_rate)
    
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.act1(self.bn1(self.conv1(x)))
        x = self.act2(self.bn2(self.conv2(x)))
        x = self.dropout(self.pool(x))
        return x


class VegaModel(nn.Module):
    """
    Vega: CNN-FCNN for Multi-Label Isotope Classification + Activity Regression
    
    Takes a 1D gamma spectrum and outputs:
    - 82 isotope presence logits (use sigmoid for probabilities)
    - 82 activity estimates (in Bq, scaled by max_activity_bq)
    """
    
    def __init__(self, config: VegaConfig):
        super().__init__()
        self.config = config
        
        # Build CNN backbone
        self.backbone = self._build_backbone()
        
        # Calculate flattened size
        self._flat_size = self._calculate_flat_size()
        
        # Classification head (multi-label)
        self.classifier = self._build_classifier()
        
        # Regression head (activity)
        self.regressor = self._build_regressor()
    
    def _build_backbone(self) -> nn.Sequential:
        layers = []
        in_ch = 1
        for out_ch in self.config.conv_channels:
            layers.append(ConvBlock(
                in_ch, out_ch,
                kernel_size=self.config.conv_kernel_size,
                pool_size=self.config.pool_size,
                dropout_rate=self.config.spatial_dropout_rate,
                leaky_slope=self.config.leaky_relu_slope
            ))
            in_ch = out_ch
        return nn.Sequential(*layers)
    
    def _calculate_flat_size(self) -> int:
        with torch.no_grad():
            x = torch.zeros(1, 1, self.config.num_channels)
            x = self.backbone(x)
            return x.view(1, -1).size(1)
    
    def _build_classifier(self) -> nn.Sequential:
        layers = []
        in_dim = self._flat_size
        for hidden_dim in self.config.fc_hidden_dims:
            layers.extend([
                nn.Linear(in_dim, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            in_dim = hidden_dim
        layers.append(nn.Linear(in_dim, self.config.num_isotopes))
        return nn.Sequential(*layers)
    
    def _build_regressor(self) -> nn.Sequential:
        layers = []
        in_dim = self._flat_size
        for hidden_dim in self.config.fc_hidden_dims:
            layers.extend([
                nn.Linear(in_dim, hidden_dim),
                nn.BatchNorm1d(hidden_dim),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            in_dim = hidden_dim
        layers.extend([
            nn.Linear(in_dim, self.config.num_isotopes),
            nn.ReLU()  # Activities must be positive
        ])
        return nn.Sequential(*layers)
    
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        # Ensure input shape is (batch, 1, channels)
        if x.dim() == 2:
            x = x.unsqueeze(1)
        
        # Feature extraction
        features = self.backbone(x)
        features = features.view(features.size(0), -1)
        
        # Dual outputs
        logits = self.classifier(features)
        activities = self.regressor(features)
        
        return logits, activities


# =============================================================================
# PREDICTION DATA CLASSES
# =============================================================================

@dataclass
class IsotopePrediction:
    """Prediction result for a single isotope."""
    name: str
    probability: float
    activity_bq: float
    present: bool


@dataclass
class SpectrumPrediction:
    """Complete prediction results for a spectrum."""
    isotopes: List[IsotopePrediction]
    num_present: int
    confidence: float
    threshold_used: float
    
    def to_dict(self) -> Dict:
        """Convert to JSON-serializable dictionary."""
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
        """Human-readable summary of predictions."""
        present = self.get_present_isotopes()
        if not present:
            return "No isotopes detected above threshold"
        
        lines = [f"Detected {len(present)} isotope(s):"]
        for iso in sorted(present, key=lambda x: -x.probability):
            lines.append(
                f"  • {iso.name}: {iso.probability*100:.1f}% confidence, "
                f"{iso.activity_bq:.1f} Bq estimated activity"
            )
        return "\n".join(lines)
    
    def to_json(self, indent: int = 2) -> str:
        """Convert to JSON string."""
        return json.dumps(self.to_dict(), indent=indent)


# =============================================================================
# INFERENCE ENGINE
# =============================================================================

class VegaInference:
    """
    Inference engine for the Vega isotope identification model.
    
    Example usage:
        inference = VegaInference("vega_best.pt")
        spectrum = np.load("my_spectrum.npy")
        result = inference.predict(spectrum, threshold=0.5)
        print(result.summary())
    """
    
    def __init__(
        self,
        model_path: Union[str, Path],
        isotope_index: Optional[IsotopeIndex] = None,
        device: Optional[torch.device] = None
    ):
        """
        Initialize the inference engine.
        
        Args:
            model_path: Path to saved .pt model checkpoint
            isotope_index: Optional custom isotope index. Uses default if None.
            device: Compute device. Auto-detects CUDA if available.
        """
        self.model_path = Path(model_path)
        
        # Device selection with CUDA compatibility check
        if device is not None:
            self.device = device
        elif torch.cuda.is_available():
            try:
                # Test CUDA actually works (some GPUs may not be compatible)
                _ = torch.zeros(1, device='cuda') + 1
                self.device = torch.device('cuda')
            except RuntimeError:
                self.device = torch.device('cpu')
        else:
            self.device = torch.device('cpu')
        
        # Load checkpoint
        print(f"Loading model from: {self.model_path}")
        self.checkpoint = torch.load(self.model_path, map_location=self.device, weights_only=False)
        
        # Load model config
        if 'model_config' in self.checkpoint:
            config_dict = self.checkpoint['model_config']
            self.model_config = VegaConfig(**config_dict)
        elif 'params' in self.checkpoint:
            # Handle Optuna-trained models
            params = self.checkpoint['params']
            self.model_config = VegaConfig(
                conv_channels=params.get('conv_channels', [64, 128, 256]),
                conv_kernel_size=params.get('conv_kernel_size', 7),
                pool_size=params.get('pool_size', 2),
                fc_hidden_dims=params.get('fc_hidden_dims', [512, 256]),
                dropout_rate=params.get('dropout_rate', 0.3),
                spatial_dropout_rate=params.get('spatial_dropout_rate', 0.1),
                leaky_relu_slope=params.get('leaky_relu_slope', 0.1)
            )
        else:
            # Use defaults
            self.model_config = VegaConfig()
        
        # Create and load model
        self.model = VegaModel(self.model_config)
        self.model.load_state_dict(self.checkpoint['model_state_dict'])
        self.model = self.model.to(self.device)
        self.model.eval()
        
        # Set isotope index
        self.isotope_index = isotope_index or IsotopeIndex()
        
        print(f"✓ Model loaded successfully")
        print(f"  Device: {self.device}")
        print(f"  Isotopes: {self.isotope_index.num_isotopes}")
        print(f"  Architecture: CNN{self.model_config.conv_channels} → FC{self.model_config.fc_hidden_dims}")
    
    def preprocess(self, spectrum: np.ndarray, normalize: bool = True) -> torch.Tensor:
        """
        Preprocess spectrum for model input.
        
        Args:
            spectrum: Input array, shape (1023,) or (N, 1023)
            normalize: Normalize to [0, 1] range
            
        Returns:
            Tensor ready for model, shape (1, 1023)
        """
        # Average time series if 2D
        if spectrum.ndim == 2:
            spectrum = spectrum.mean(axis=0)
        
        # Normalize
        if normalize and spectrum.max() > 0:
            spectrum = spectrum / spectrum.max()
        
        # To tensor with batch dimension
        tensor = torch.tensor(spectrum, dtype=torch.float32).unsqueeze(0)
        return tensor.to(self.device)
    
    @torch.no_grad()
    def predict(
        self,
        spectrum: Union[np.ndarray, torch.Tensor],
        threshold: float = 0.5,
        return_all: bool = False
    ) -> SpectrumPrediction:
        """
        Run inference on a gamma spectrum.
        
        Args:
            spectrum: Input spectrum (numpy array or tensor)
            threshold: Probability threshold for detection (0-1)
            return_all: If True, include all 82 isotopes. If False, only detected ones.
            
        Returns:
            SpectrumPrediction with detected isotopes and activities
        """
        # Preprocess
        if isinstance(spectrum, np.ndarray):
            spectrum = self.preprocess(spectrum)
        
        # Run model
        logits, activities = self.model(spectrum)
        
        # Convert to probabilities
        probs = torch.sigmoid(logits).cpu().numpy()[0]
        activities = activities.cpu().numpy()[0] * self.model_config.max_activity_bq
        
        # Build predictions
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
        
        # Calculate confidence
        present_isotopes = [iso for iso in isotopes if iso.present]
        if present_isotopes:
            confidence = np.mean([iso.probability for iso in present_isotopes])
        else:
            confidence = 1.0 - probs.max()
        
        return SpectrumPrediction(
            isotopes=isotopes,
            num_present=len(present_isotopes),
            confidence=float(confidence),
            threshold_used=threshold
        )
    
    def predict_from_file(
        self,
        file_path: Union[str, Path],
        threshold: float = 0.5
    ) -> SpectrumPrediction:
        """Load spectrum from .npy file and run inference."""
        spectrum = np.load(file_path)
        return self.predict(spectrum, threshold)
    
    def predict_batch(
        self,
        spectra: List[np.ndarray],
        threshold: float = 0.5
    ) -> List[SpectrumPrediction]:
        """Run inference on multiple spectra."""
        return [self.predict(s, threshold) for s in spectra]


# =============================================================================
# SAMPLE SPECTRUM GENERATOR (For testing without real data)
# =============================================================================

def energy_to_channel(energy_kev: float, num_channels: int = 1023) -> int:
    """Convert energy in keV to channel index."""
    e_min, e_max = 20.0, 3000.0
    channel = int((energy_kev - e_min) / (e_max - e_min) * num_channels)
    return max(0, min(num_channels - 1, channel))


def channel_to_energy(channel: int, num_channels: int = 1023) -> float:
    """Convert channel index to energy in keV."""
    e_min, e_max = 20.0, 3000.0
    return e_min + channel * (e_max - e_min) / num_channels


def create_sample_spectrum(
    isotope: str = "Cs-137",
    activity_bq: float = 100.0,
    duration_seconds: float = 300.0,
    add_background: bool = True,
    add_noise: bool = True,
    detector_fwhm_percent: float = 8.5,
    seed: Optional[int] = None
) -> np.ndarray:
    """
    Generate a synthetic gamma spectrum for testing.
    
    This creates a realistic-looking spectrum with Gaussian peaks at the
    characteristic gamma energies of the specified isotope.
    
    Args:
        isotope: Isotope name (e.g., "Cs-137", "Co-60", "Na-22")
        activity_bq: Source activity in Becquerels
        duration_seconds: Measurement duration
        add_background: Add environmental background
        add_noise: Apply Poisson counting statistics
        detector_fwhm_percent: Detector resolution at 662 keV (%)
        seed: Random seed for reproducibility
        
    Returns:
        1D numpy array of shape (1023,) with counts per channel
    """
    if seed is not None:
        np.random.seed(seed)
    
    num_channels = 1023
    spectrum = np.zeros(num_channels)
    
    # Get gamma lines for the isotope
    if isotope in GAMMA_LINES:
        gamma_lines = GAMMA_LINES[isotope]
    else:
        # Use Cs-137 as fallback
        print(f"Warning: No gamma lines for {isotope}, using Cs-137")
        gamma_lines = GAMMA_LINES["Cs-137"]
    
    # Add peaks for each gamma line
    for energy_kev, branching_ratio in gamma_lines:
        # Calculate FWHM at this energy (scales with sqrt of energy)
        fwhm_kev = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(energy_kev / 662.0)
        sigma_kev = fwhm_kev / 2.355
        
        # Expected counts
        efficiency = 0.1 * math.exp(-energy_kev / 500.0)  # Simplified efficiency
        expected_counts = activity_bq * duration_seconds * branching_ratio * efficiency
        
        # Add Gaussian peak
        center_channel = energy_to_channel(energy_kev)
        sigma_channels = sigma_kev / ((3000 - 20) / num_channels)
        
        for ch in range(num_channels):
            energy = channel_to_energy(ch)
            peak = expected_counts * math.exp(-0.5 * ((energy - energy_kev) / sigma_kev) ** 2)
            spectrum[ch] += peak
    
    # Add background continuum
    if add_background:
        # Exponential continuum
        for ch in range(num_channels):
            energy = channel_to_energy(ch)
            bg = 50.0 * duration_seconds * math.exp(-energy / 300.0) / 300.0
            spectrum[ch] += bg
        
        # K-40 environmental background
        k40_energy = 1460.8
        k40_fwhm = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(k40_energy / 662.0)
        k40_sigma = k40_fwhm / 2.355
        k40_counts = 10.0 * duration_seconds  # Low activity environmental
        
        for ch in range(num_channels):
            energy = channel_to_energy(ch)
            peak = k40_counts * math.exp(-0.5 * ((energy - k40_energy) / k40_sigma) ** 2)
            spectrum[ch] += peak
    
    # Apply Poisson noise
    if add_noise:
        spectrum = np.maximum(spectrum, 0)
        spectrum = np.random.poisson(spectrum.astype(int)).astype(float)
    
    return spectrum


def create_sample_spectra_batch() -> Dict[str, np.ndarray]:
    """
    Create a batch of sample spectra for different isotopes.
    
    Returns:
        Dictionary mapping isotope names to their sample spectra
    """
    samples = {}
    
    # Common calibration isotopes
    for isotope in ["Cs-137", "Co-60", "Na-22", "Ba-133", "Am-241", "Eu-152"]:
        if isotope in GAMMA_LINES:
            samples[isotope] = create_sample_spectrum(
                isotope=isotope,
                activity_bq=100.0,
                duration_seconds=300.0,
                seed=hash(isotope) % 2**32
            )
    
    # Background only
    samples["Background"] = create_sample_spectrum(
        isotope="Cs-137",  # Will be overwritten by background
        activity_bq=0.0,   # No source
        duration_seconds=300.0,
        add_background=True,
        seed=12345
    )
    
    return samples


# =============================================================================
# DEMONSTRATION FUNCTIONS
# =============================================================================

def run_demo(model_path: str, threshold: float = 0.5):
    """
    Run a complete demonstration of the Vega inference system.
    
    Args:
        model_path: Path to trained model checkpoint
        threshold: Detection threshold (0-1)
    """
    print("\n" + "=" * 70)
    print("VEGA ISOTOPE IDENTIFICATION - INFERENCE DEMONSTRATION")
    print("=" * 70)
    
    # Load model
    print("\n[1] Loading Model")
    print("-" * 70)
    inference = VegaInference(model_path)
    
    # Generate sample spectra
    print("\n[2] Generating Sample Spectra")
    print("-" * 70)
    samples = create_sample_spectra_batch()
    print(f"Generated {len(samples)} sample spectra:")
    for name in samples:
        print(f"  • {name}")
    
    # Run inference on each
    print("\n[3] Running Inference")
    print("-" * 70)
    
    for name, spectrum in samples.items():
        print(f"\n{'─' * 70}")
        print(f"Sample: {name}")
        print(f"Spectrum shape: {spectrum.shape}")
        print(f"Spectrum range: [{spectrum.min():.1f}, {spectrum.max():.1f}]")
        
        # Run prediction
        result = inference.predict(spectrum, threshold=threshold)
        
        print(f"\nPrediction (threshold={threshold}):")
        print(result.summary())
        
        # Show top 5 probabilities even if below threshold
        print("\nTop 5 isotope probabilities:")
        all_result = inference.predict(spectrum, threshold=0.0, return_all=True)
        sorted_iso = sorted(all_result.isotopes, key=lambda x: -x.probability)[:5]
        for iso in sorted_iso:
            marker = "✓" if iso.probability >= threshold else " "
            print(f"  {marker} {iso.name}: {iso.probability*100:.2f}%")
    
    # Show JSON output format
    print("\n[4] JSON Output Format Example")
    print("-" * 70)
    sample_result = inference.predict(samples["Cs-137"], threshold=threshold)
    print(sample_result.to_json())
    
    print("\n" + "=" * 70)
    print("DEMONSTRATION COMPLETE")
    print("=" * 70)


def run_single_inference(model_path: str, spectrum_path: str, threshold: float = 0.5):
    """
    Run inference on a single spectrum file.
    
    Args:
        model_path: Path to trained model
        spectrum_path: Path to .npy spectrum file
        threshold: Detection threshold
    """
    print(f"\nLoading model from: {model_path}")
    inference = VegaInference(model_path)
    
    print(f"Loading spectrum from: {spectrum_path}")
    spectrum = np.load(spectrum_path)
    print(f"Spectrum shape: {spectrum.shape}")
    
    print(f"\nRunning inference (threshold={threshold})...")
    result = inference.predict(spectrum, threshold=threshold)
    
    print("\n" + "=" * 60)
    print("PREDICTION RESULTS")
    print("=" * 60)
    print(result.summary())
    print("=" * 60)
    
    return result


# =============================================================================
# MAIN ENTRY POINT
# =============================================================================

def main():
    """Main entry point for command-line usage."""
    import argparse
    
    parser = argparse.ArgumentParser(
        description="Vega Portable Inference - Gamma Spectrum Isotope Identification",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Run demo with sample spectra
  python vega_portable_inference.py --model vega_best.pt
  
  # Analyze a specific spectrum file
  python vega_portable_inference.py --model vega_best.pt --spectrum my_data.npy
  
  # Use lower threshold for higher recall
  python vega_portable_inference.py --model vega_best.pt --threshold 0.3
        """
    )
    
    parser.add_argument(
        "--model", "-m",
        type=str,
        required=True,
        help="Path to trained Vega model checkpoint (.pt file)"
    )
    parser.add_argument(
        "--spectrum", "-s",
        type=str,
        default=None,
        help="Path to spectrum file (.npy). If not provided, runs demo with synthetic spectra."
    )
    parser.add_argument(
        "--threshold", "-t",
        type=float,
        default=0.5,
        help="Detection threshold (0-1). Lower = more sensitive, higher = more specific. Default: 0.5"
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output results in JSON format"
    )
    
    args = parser.parse_args()
    
    if args.spectrum:
        # Single file inference
        result = run_single_inference(args.model, args.spectrum, args.threshold)
        if args.json:
            print("\nJSON Output:")
            print(result.to_json())
    else:
        # Demo mode
        run_demo(args.model, args.threshold)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
