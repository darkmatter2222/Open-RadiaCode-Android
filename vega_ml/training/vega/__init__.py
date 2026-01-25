"""
Vega Model - CNN-FCNN with Multi-Task Heads for Gamma Spectrum Isotope Identification

Architecture based on research findings from:
- Wang et al. (2026): CNN-FCNN achieves 99.8% accuracy
- Galib et al. (2021): Hybrid CNN outperforms pure architectures
- Turner et al. (2021): 1D CNN robust to gain shifts and shielding

Features:
- 1D CNN backbone for spectral feature extraction
- Multi-task heads for isotope classification + activity regression
- Support for 82 isotopes from the synthetic spectra database
"""

from .model import VegaModel, VegaConfig
from .dataset import SpectrumDataset, create_data_loaders
from .train import train_vega, VegaTrainer

__all__ = [
    'VegaModel',
    'VegaConfig', 
    'SpectrumDataset',
    'create_data_loaders',
    'train_vega',
    'VegaTrainer'
]
