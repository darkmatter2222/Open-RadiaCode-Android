#!/usr/bin/env python
"""
================================================================================
VEGA 2D PORTABLE INFERENCE - Self-Contained Isotope Identification
================================================================================

This is a FULLY SELF-CONTAINED inference script for the Vega 2D gamma spectrum
isotope identification model. You only need:

    1. This Python file (vega_portable_inference_2d.py)
    2. A trained 2D model checkpoint (.pt file)
    3. PyTorch, NumPy installed

NO other project files are required. The model architecture, isotope index,
and sample data generator are all embedded in this file.

================================================================================
USAGE EXAMPLES:
================================================================================

1. Basic inference with embedded sample data:
   
   python vega_portable_inference_2d.py --model vega_2d_best.pt

2. Inference on a specific spectrum file:
   
   python vega_portable_inference_2d.py --model vega_2d_best.pt --spectrum my_spectrum.npy

3. Programmatic usage:

   from vega_portable_inference_2d import Vega2DInference, create_sample_spectrum_2d
   
   inference = Vega2DInference("vega_2d_best.pt")
   spectrum = create_sample_spectrum_2d("Cs-137", activity_bq=100)
   result = inference.predict(spectrum)
   print(result.summary())

================================================================================
INPUT FORMAT:
================================================================================

The 2D model expects gamma spectra in the following format:

- NumPy array, shape: (60, 1023) for 60 time intervals × 1023 channels
- Values: Counts per channel per time interval (will be normalized automatically)
- Energy range: 20 keV to 3000 keV across 1023 channels
- Time: 60 one-second intervals (1 minute total measurement)

If your spectrum has different time dimensions, it will be padded or truncated
to 60 intervals automatically.

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
MODEL ARCHITECTURE (2D CNN):
================================================================================

Vega 2D uses 2D convolutions to process time × energy spectral images:

    Input (1, 60, 1023) - single channel image
           ↓
    ConvBlock 1: Conv2d(1→32, k=3×7) → BN → LeakyReLU → Conv2d → BN → LeakyReLU → MaxPool2d → Dropout
           ↓
    ConvBlock 2: Conv2d(32→64, k=3×7) → BN → LeakyReLU → Conv2d → BN → LeakyReLU → MaxPool2d → Dropout
           ↓
    ConvBlock 3: Conv2d(64→128, k=3×7) → BN → LeakyReLU → Conv2d → BN → LeakyReLU → MaxPool2d → Dropout
           ↓
    Flatten (113,792 features)
           ↓
    FC: Linear(→512) → BN → LeakyReLU → Dropout → Linear(→256) → BN → LeakyReLU → Dropout
           ↓
    Classifier: Linear(→82) [isotope logits]
    Regressor: Linear(→82) → ReLU [activity Bq]

Outputs:
- 82 isotope presence probabilities (multi-label classification)
- 82 activity estimates in Bq (regression)

Total parameters: ~59 million

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

================================================================================
"""

import sys
import json
import math
import numpy as np
import torch
import torch.nn as nn
from pathlib import Path
from dataclasses import dataclass, field
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
    """Maps isotope names to model output indices and vice versa."""
    
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
            raise KeyError(f"Isotope '{name}' not in index")
        return self._name_to_idx[name]
    
    def index_to_name(self, idx: int) -> str:
        if idx not in self._idx_to_name:
            raise KeyError(f"Index {idx} out of range [0, {self.num_isotopes-1}]")
        return self._idx_to_name[idx]
    
    def __len__(self) -> int:
        return self.num_isotopes


# =============================================================================
# 2D MODEL ARCHITECTURE (Embedded)
# =============================================================================

@dataclass
class Vega2DConfig:
    """Configuration for Vega 2D model."""
    
    # Input dimensions
    num_channels: int = 1023  # Energy channels
    num_time_intervals: int = 60  # Fixed time dimension
    
    # Output
    num_isotopes: int = 82
    
    # CNN architecture
    conv_channels: List[int] = field(default_factory=lambda: [32, 64, 128])
    kernel_size: Tuple[int, int] = (3, 7)  # (time, energy)
    pool_size: Tuple[int, int] = (2, 2)
    
    # FC layers
    fc_hidden_dims: List[int] = field(default_factory=lambda: [512, 256])
    
    # Regularization
    dropout_rate: float = 0.3
    leaky_relu_slope: float = 0.01
    
    # Activity scaling
    max_activity_bq: float = 1000.0


class ConvBlock2D(nn.Module):
    """2D Convolutional block with BatchNorm, activation, pooling, and dropout."""
    
    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: Tuple[int, int],
        pool_size: Tuple[int, int],
        dropout_rate: float,
        leaky_relu_slope: float
    ):
        super().__init__()
        
        padding = (kernel_size[0] // 2, kernel_size[1] // 2)
        
        self.conv1 = nn.Conv2d(in_channels, out_channels, kernel_size, padding=padding)
        self.bn1 = nn.BatchNorm2d(out_channels)
        self.conv2 = nn.Conv2d(out_channels, out_channels, kernel_size, padding=padding)
        self.bn2 = nn.BatchNorm2d(out_channels)
        self.activation = nn.LeakyReLU(leaky_relu_slope)
        self.pool = nn.MaxPool2d(pool_size)
        self.dropout = nn.Dropout2d(dropout_rate)
    
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.activation(self.bn1(self.conv1(x)))
        x = self.activation(self.bn2(self.conv2(x)))
        x = self.pool(x)
        x = self.dropout(x)
        return x


class Vega2DModel(nn.Module):
    """
    2D CNN model for gamma spectrum isotope identification.
    
    Treats spectra as images with time on one axis and energy channels on the other.
    """
    
    def __init__(self, config: Vega2DConfig = None):
        super().__init__()
        self.config = config or Vega2DConfig()
        
        # Build CNN backbone
        self.conv_blocks = nn.ModuleList()
        in_channels = 1
        
        for out_channels in self.config.conv_channels:
            self.conv_blocks.append(ConvBlock2D(
                in_channels=in_channels,
                out_channels=out_channels,
                kernel_size=self.config.kernel_size,
                pool_size=self.config.pool_size,
                dropout_rate=self.config.dropout_rate,
                leaky_relu_slope=self.config.leaky_relu_slope
            ))
            in_channels = out_channels
        
        # Calculate flattened size
        self.flat_size = self._calculate_flat_size()
        
        # FC backbone
        fc_layers = []
        fc_in = self.flat_size
        
        for fc_out in self.config.fc_hidden_dims:
            fc_layers.extend([
                nn.Linear(fc_in, fc_out),
                nn.BatchNorm1d(fc_out),
                nn.LeakyReLU(self.config.leaky_relu_slope),
                nn.Dropout(self.config.dropout_rate)
            ])
            fc_in = fc_out
        
        self.fc_backbone = nn.Sequential(*fc_layers)
        
        # Output heads
        self.classifier = nn.Linear(fc_in, self.config.num_isotopes)
        self.regressor = nn.Sequential(
            nn.Linear(fc_in, self.config.num_isotopes),
            nn.ReLU()
        )
    
    def _calculate_flat_size(self) -> int:
        h = self.config.num_time_intervals
        w = self.config.num_channels
        
        for _ in self.config.conv_channels:
            h = h // self.config.pool_size[0]
            w = w // self.config.pool_size[1]
        
        return self.config.conv_channels[-1] * h * w
    
    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        # Add channel dimension if needed: (B, T, C) -> (B, 1, T, C)
        if x.dim() == 3:
            x = x.unsqueeze(1)
        
        # CNN backbone
        for conv_block in self.conv_blocks:
            x = conv_block(x)
        
        # Flatten
        x = x.view(x.size(0), -1)
        
        # FC backbone
        x = self.fc_backbone(x)
        
        # Output heads
        logits = self.classifier(x)
        activities = self.regressor(x)
        
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

class Vega2DInference:
    """
    Inference engine for the Vega 2D isotope identification model.
    
    Example usage:
        inference = Vega2DInference("vega_2d_best.pt")
        spectrum = np.load("my_spectrum.npy")  # Shape: (60, 1023)
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
        
        # Device selection
        if device is not None:
            self.device = device
        elif torch.cuda.is_available():
            try:
                _ = torch.zeros(1, device='cuda') + 1
                self.device = torch.device('cuda')
            except RuntimeError:
                self.device = torch.device('cpu')
        else:
            self.device = torch.device('cpu')
        
        # Load checkpoint
        print(f"Loading 2D model from: {self.model_path}")
        self.checkpoint = torch.load(self.model_path, map_location=self.device, weights_only=False)
        
        # Load model config
        if 'model_config' in self.checkpoint:
            config_dict = self.checkpoint['model_config']
            # Handle tuple conversion for kernel_size and pool_size
            if 'kernel_size' in config_dict and isinstance(config_dict['kernel_size'], list):
                config_dict['kernel_size'] = tuple(config_dict['kernel_size'])
            if 'pool_size' in config_dict and isinstance(config_dict['pool_size'], list):
                config_dict['pool_size'] = tuple(config_dict['pool_size'])
            self.model_config = Vega2DConfig(**config_dict)
        else:
            self.model_config = Vega2DConfig()
        
        # Create and load model
        self.model = Vega2DModel(self.model_config)
        self.model.load_state_dict(self.checkpoint['model_state_dict'])
        self.model = self.model.to(self.device)
        self.model.eval()
        
        # Set isotope index
        self.isotope_index = isotope_index or IsotopeIndex()
        
        print(f"✓ Model loaded successfully")
        print(f"  Device: {self.device}")
        print(f"  Input shape: ({self.model_config.num_time_intervals}, {self.model_config.num_channels})")
        print(f"  Isotopes: {self.isotope_index.num_isotopes}")
        print(f"  Architecture: 2D-CNN{self.model_config.conv_channels} → FC{self.model_config.fc_hidden_dims}")
    
    def _pad_or_truncate(self, spectrum: np.ndarray) -> np.ndarray:
        """Ensure spectrum has exactly num_time_intervals rows."""
        target_rows = self.model_config.num_time_intervals
        current_rows = spectrum.shape[0]
        
        if current_rows == target_rows:
            return spectrum
        elif current_rows > target_rows:
            # Truncate - take last N intervals (most recent data)
            return spectrum[-target_rows:]
        else:
            # Pad with zeros at the beginning
            padding = np.zeros((target_rows - current_rows, spectrum.shape[1]))
            return np.vstack([padding, spectrum])
    
    def preprocess(self, spectrum: np.ndarray, normalize: bool = True) -> torch.Tensor:
        """
        Preprocess spectrum for model input.
        
        Args:
            spectrum: Input array, shape (T, 1023) where T is any number of time intervals
            normalize: Normalize to [0, 1] range
            
        Returns:
            Tensor ready for model, shape (1, 60, 1023)
        """
        # Handle 1D input (average spectrum)
        if spectrum.ndim == 1:
            # Expand to 2D by repeating
            spectrum = np.tile(spectrum.reshape(1, -1), (self.model_config.num_time_intervals, 1))
        
        # Ensure correct time dimension
        spectrum = self._pad_or_truncate(spectrum)
        
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


def create_sample_spectrum_2d(
    isotope: str = "Cs-137",
    activity_bq: float = 100.0,
    duration_seconds: int = 60,
    add_background: bool = True,
    add_noise: bool = True,
    detector_fwhm_percent: float = 8.5,
    seed: Optional[int] = None
) -> np.ndarray:
    """
    Generate a synthetic 2D gamma spectrum for testing.
    
    Args:
        isotope: Isotope name (e.g., "Cs-137", "Co-60")
        activity_bq: Source activity in Becquerels
        duration_seconds: Number of 1-second time intervals (default 60)
        add_background: Add environmental background
        add_noise: Apply Poisson counting statistics
        detector_fwhm_percent: Detector resolution at 662 keV (%)
        seed: Random seed for reproducibility
        
    Returns:
        2D numpy array of shape (duration_seconds, 1023)
    """
    if seed is not None:
        np.random.seed(seed)
    
    num_channels = 1023
    spectrum = np.zeros((duration_seconds, num_channels))
    
    # Get gamma lines for the isotope
    if isotope in GAMMA_LINES:
        gamma_lines = GAMMA_LINES[isotope]
    else:
        print(f"Warning: No gamma lines for {isotope}, using Cs-137")
        gamma_lines = GAMMA_LINES["Cs-137"]
    
    # Generate spectrum for each time interval
    for t in range(duration_seconds):
        for energy_kev, branching_ratio in gamma_lines:
            fwhm_kev = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(energy_kev / 662.0)
            sigma_kev = fwhm_kev / 2.355
            
            efficiency = 0.1 * math.exp(-energy_kev / 500.0)
            expected_counts = activity_bq * 1.0 * branching_ratio * efficiency  # 1 second interval
            
            for ch in range(num_channels):
                energy = channel_to_energy(ch)
                peak = expected_counts * math.exp(-0.5 * ((energy - energy_kev) / sigma_kev) ** 2)
                spectrum[t, ch] += peak
        
        # Add background
        if add_background:
            for ch in range(num_channels):
                energy = channel_to_energy(ch)
                bg = 50.0 * 1.0 * math.exp(-energy / 300.0) / 300.0
                spectrum[t, ch] += bg
            
            # K-40 environmental
            k40_energy = 1460.8
            k40_fwhm = (detector_fwhm_percent / 100.0) * 662.0 * math.sqrt(k40_energy / 662.0)
            k40_sigma = k40_fwhm / 2.355
            k40_counts = 10.0 * 1.0
            
            for ch in range(num_channels):
                energy = channel_to_energy(ch)
                peak = k40_counts * math.exp(-0.5 * ((energy - k40_energy) / k40_sigma) ** 2)
                spectrum[t, ch] += peak
    
    # Apply Poisson noise
    if add_noise:
        spectrum = np.maximum(spectrum, 0)
        spectrum = np.random.poisson(spectrum.astype(int)).astype(float)
    
    return spectrum


def create_sample_spectra_batch_2d() -> Dict[str, np.ndarray]:
    """Create a batch of sample 2D spectra for different isotopes."""
    samples = {}
    
    for isotope in ["Cs-137", "Co-60", "Na-22", "Ba-133", "Am-241", "Eu-152"]:
        if isotope in GAMMA_LINES:
            samples[isotope] = create_sample_spectrum_2d(
                isotope=isotope,
                activity_bq=100.0,
                duration_seconds=60,
                seed=hash(isotope) % 2**32
            )
    
    # Background only
    samples["Background"] = create_sample_spectrum_2d(
        isotope="Cs-137",
        activity_bq=0.0,
        duration_seconds=60,
        add_background=True,
        seed=12345
    )
    
    return samples


# =============================================================================
# DEMONSTRATION FUNCTIONS
# =============================================================================

def run_demo(model_path: str, threshold: float = 0.5):
    """Run a complete demonstration of the Vega 2D inference system."""
    print("\n" + "=" * 70)
    print("VEGA 2D ISOTOPE IDENTIFICATION - INFERENCE DEMONSTRATION")
    print("=" * 70)
    
    # Load model
    print("\n[1] Loading 2D Model")
    print("-" * 70)
    inference = Vega2DInference(model_path)
    
    # Generate sample spectra
    print("\n[2] Generating Sample 2D Spectra (60 time intervals × 1023 channels)")
    print("-" * 70)
    samples = create_sample_spectra_batch_2d()
    print(f"Generated {len(samples)} sample spectra:")
    for name, spec in samples.items():
        print(f"  • {name}: shape {spec.shape}")
    
    # Run inference on each
    print("\n[3] Running Inference")
    print("-" * 70)
    
    for name, spectrum in samples.items():
        print(f"\n{'─' * 70}")
        print(f"Sample: {name}")
        print(f"Spectrum shape: {spectrum.shape}")
        print(f"Spectrum range: [{spectrum.min():.1f}, {spectrum.max():.1f}]")
        
        result = inference.predict(spectrum, threshold=threshold)
        
        print(f"\nPrediction (threshold={threshold}):")
        print(result.summary())
        
        # Top 5 probabilities
        print("\nTop 5 isotope probabilities:")
        all_result = inference.predict(spectrum, threshold=0.0, return_all=True)
        sorted_iso = sorted(all_result.isotopes, key=lambda x: -x.probability)[:5]
        for iso in sorted_iso:
            marker = "✓" if iso.probability >= threshold else " "
            print(f"  {marker} {iso.name}: {iso.probability*100:.2f}%")
    
    # JSON output format
    print("\n[4] JSON Output Format Example")
    print("-" * 70)
    sample_result = inference.predict(samples["Cs-137"], threshold=threshold)
    print(sample_result.to_json())
    
    print("\n" + "=" * 70)
    print("DEMONSTRATION COMPLETE")
    print("=" * 70)


def run_single_inference(model_path: str, spectrum_path: str, threshold: float = 0.5):
    """Run inference on a single spectrum file."""
    print(f"\nLoading model from: {model_path}")
    inference = Vega2DInference(model_path)
    
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
        description="Vega 2D Portable Inference - Gamma Spectrum Isotope Identification",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Run demo with sample spectra
  python vega_portable_inference_2d.py --model vega_2d_best.pt
  
  # Analyze a specific spectrum file
  python vega_portable_inference_2d.py --model vega_2d_best.pt --spectrum my_data.npy
  
  # Use lower threshold for higher recall
  python vega_portable_inference_2d.py --model vega_2d_best.pt --threshold 0.3
        """
    )
    
    parser.add_argument(
        "--model", "-m",
        type=str,
        required=True,
        help="Path to trained Vega 2D model checkpoint (.pt file)"
    )
    parser.add_argument(
        "--spectrum", "-s",
        type=str,
        default=None,
        help="Path to spectrum file (.npy, shape 60×1023 or variable×1023). Runs demo if not provided."
    )
    parser.add_argument(
        "--threshold", "-t",
        type=float,
        default=0.5,
        help="Detection threshold (0-1). Lower = more sensitive. Default: 0.5"
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output results in JSON format"
    )
    
    args = parser.parse_args()
    
    if args.spectrum:
        result = run_single_inference(args.model, args.spectrum, args.threshold)
        if args.json:
            print("\nJSON Output:")
            print(result.to_json())
    else:
        run_demo(args.model, args.threshold)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
