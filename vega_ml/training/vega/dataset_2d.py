"""
Dataset for 2D Vega Model

Loads 2D spectra (time × channels) and pads/truncates to fixed dimensions.
"""

import json
import numpy as np
import torch
from torch.utils.data import Dataset, DataLoader, random_split
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass

from .isotope_index import IsotopeIndex, get_default_isotope_index


@dataclass 
class SpectrumSample2D:
    """A single 2D spectrum sample."""
    sample_id: str
    spectrum: np.ndarray  # 2D array (time_intervals, channels)
    isotopes_present: List[str]
    activities_bq: Dict[str, float]
    duration_seconds: float
    detector: str


class SpectrumDataset2D(Dataset):
    """
    PyTorch Dataset for 2D gamma spectra.
    
    Pads or truncates time dimension to fixed size for batch processing.
    """
    
    def __init__(
        self,
        data_dir: Path,
        isotope_index: Optional[IsotopeIndex] = None,
        max_activity_bq: float = 1000.0,
        target_time_intervals: int = 60,
        transform=None
    ):
        """
        Initialize the dataset.
        
        Args:
            data_dir: Path to directory containing spectra/ subdirectory
            isotope_index: Index mapping isotope names to indices
            max_activity_bq: Maximum activity for normalization
            target_time_intervals: Fixed time dimension (pad/truncate to this)
            transform: Optional transform to apply
        """
        self.data_dir = Path(data_dir)
        self.spectra_dir = self.data_dir / "spectra"
        self.isotope_index = isotope_index or get_default_isotope_index()
        self.max_activity_bq = max_activity_bq
        self.target_time_intervals = target_time_intervals
        self.transform = transform
        
        # Detect label format and load sample list
        self.use_individual_labels = self._detect_label_format()
        
        if self.use_individual_labels:
            self.sample_ids = self._scan_for_samples()
            self.metadata = None
            print(f"Using individual label files (efficient mode)")
        else:
            self.metadata = self._load_metadata()
            self.sample_ids = list(self.metadata['samples'].keys())
            print(f"Using combined labels.json (legacy mode)")
        
        print(f"Loaded 2D dataset with {len(self.sample_ids)} samples")
        print(f"Target shape: ({target_time_intervals}, 1023)")
        print(f"Isotope index has {self.isotope_index.num_isotopes} isotopes")
    
    def _detect_label_format(self) -> bool:
        """Detect whether to use individual JSON files or combined labels.json."""
        json_files = list(self.spectra_dir.glob("spectrum_*.json"))
        if len(json_files) > 0:
            return True
        
        labels_path = self.data_dir / "labels.json"
        if labels_path.exists():
            return False
        
        raise FileNotFoundError(
            f"No label files found. Expected either:\n"
            f"  - Individual files: {self.spectra_dir}/spectrum_*.json\n"
            f"  - Combined file: {self.data_dir}/labels.json"
        )
    
    def _scan_for_samples(self) -> List[str]:
        """Scan directory for sample IDs based on .npy files."""
        npy_files = sorted(self.spectra_dir.glob("spectrum_*.npy"))
        sample_ids = []
        for npy_path in npy_files:
            filename = npy_path.stem
            sample_id = filename.replace("spectrum_", "")
            sample_ids.append(sample_id)
        return sample_ids
    
    def _load_metadata(self) -> Dict:
        """Load the combined labels.json metadata file."""
        labels_path = self.data_dir / "labels.json"
        if not labels_path.exists():
            raise FileNotFoundError(f"Labels file not found: {labels_path}")
        
        with open(labels_path, 'r') as f:
            return json.load(f)
    
    def _load_sample_label(self, sample_id: str) -> Dict:
        """Load label for a single sample."""
        if self.use_individual_labels:
            json_path = self.spectra_dir / f"spectrum_{sample_id}.json"
            with open(json_path, 'r') as f:
                return json.load(f)
        else:
            return self.metadata['samples'][sample_id]
    
    def _pad_or_truncate(self, spectrum: np.ndarray) -> np.ndarray:
        """
        Pad or truncate spectrum to target time dimension.
        
        Args:
            spectrum: 2D array (time, channels)
        
        Returns:
            Array of shape (target_time_intervals, channels)
        """
        current_time = spectrum.shape[0]
        target_time = self.target_time_intervals
        num_channels = spectrum.shape[1]
        
        if current_time == target_time:
            return spectrum
        
        elif current_time > target_time:
            # Truncate: take evenly spaced intervals to preserve temporal coverage
            indices = np.linspace(0, current_time - 1, target_time, dtype=int)
            return spectrum[indices, :]
        
        else:
            # Pad with zeros at the end
            padded = np.zeros((target_time, num_channels), dtype=spectrum.dtype)
            padded[:current_time, :] = spectrum
            return padded
    
    def __len__(self) -> int:
        return len(self.sample_ids)
    
    def __getitem__(self, idx: int) -> Dict[str, torch.Tensor]:
        """
        Get a single sample.
        
        Returns:
            Dictionary containing:
                - spectrum: Tensor of shape (target_time_intervals, num_channels)
                - presence_labels: Binary tensor (num_isotopes,)
                - activity_labels: Tensor (num_isotopes,) with normalized activities
                - sample_id: String identifier
        """
        sample_id = self.sample_ids[idx]
        sample_meta = self._load_sample_label(sample_id)
        
        # Load spectrum
        spectrum_path = self.spectra_dir / f"spectrum_{sample_id}.npy"
        spectrum = np.load(spectrum_path)
        
        # Ensure 2D
        if spectrum.ndim == 1:
            spectrum = spectrum.reshape(1, -1)
        
        # Pad/truncate to fixed time dimension
        spectrum = self._pad_or_truncate(spectrum)
        
        # Normalize (max normalization)
        max_val = spectrum.max()
        if max_val > 0:
            spectrum = spectrum / max_val
        
        # Convert to tensor
        spectrum_tensor = torch.tensor(spectrum, dtype=torch.float32)
        
        # Apply transform if provided
        if self.transform:
            spectrum_tensor = self.transform(spectrum_tensor)
        
        # Create presence labels
        presence_labels = torch.zeros(self.isotope_index.num_isotopes, dtype=torch.float32)
        for isotope_name in sample_meta['isotopes']:
            try:
                idx_isotope = self.isotope_index.name_to_index(isotope_name)
                presence_labels[idx_isotope] = 1.0
            except KeyError:
                pass
        
        # Create activity labels (normalized)
        activity_labels = torch.zeros(self.isotope_index.num_isotopes, dtype=torch.float32)
        for isotope_name, activity in sample_meta.get('source_activities_bq', {}).items():
            try:
                idx_isotope = self.isotope_index.name_to_index(isotope_name)
                activity_labels[idx_isotope] = min(activity / self.max_activity_bq, 1.0)
            except KeyError:
                pass
        
        return {
            'spectrum': spectrum_tensor,
            'presence_labels': presence_labels,
            'activity_labels': activity_labels,
            'sample_id': sample_id
        }


def collate_fn_2d(batch: List[Dict]) -> Dict[str, torch.Tensor]:
    """Custom collate function for 2D batching."""
    return {
        'spectrum': torch.stack([s['spectrum'] for s in batch]),
        'presence_labels': torch.stack([s['presence_labels'] for s in batch]),
        'activity_labels': torch.stack([s['activity_labels'] for s in batch]),
        'sample_ids': [s['sample_id'] for s in batch]
    }


def create_data_loaders_2d(
    data_dir: Path,
    batch_size: int = 32,
    train_split: float = 0.8,
    val_split: float = 0.1,
    test_split: float = 0.1,
    num_workers: int = 4,
    target_time_intervals: int = 60,
    isotope_index: Optional[IsotopeIndex] = None,
    max_activity_bq: float = 1000.0,
    seed: int = 42
) -> Tuple[DataLoader, DataLoader, DataLoader]:
    """
    Create train, validation, and test data loaders for 2D data.
    """
    # Create full dataset
    dataset = SpectrumDataset2D(
        data_dir=data_dir,
        isotope_index=isotope_index,
        max_activity_bq=max_activity_bq,
        target_time_intervals=target_time_intervals
    )
    
    # Calculate split sizes
    total = len(dataset)
    train_size = int(total * train_split)
    val_size = int(total * val_split)
    test_size = total - train_size - val_size
    
    # Split dataset
    generator = torch.Generator().manual_seed(seed)
    train_dataset, val_dataset, test_dataset = random_split(
        dataset, [train_size, val_size, test_size], generator=generator
    )
    
    print(f"Dataset splits: train={train_size}, val={val_size}, test={test_size}")
    
    # Create loaders
    train_loader = DataLoader(
        train_dataset,
        batch_size=batch_size,
        shuffle=True,
        num_workers=num_workers,
        collate_fn=collate_fn_2d,
        pin_memory=True,
        persistent_workers=num_workers > 0
    )
    
    val_loader = DataLoader(
        val_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=num_workers,
        collate_fn=collate_fn_2d,
        pin_memory=True,
        persistent_workers=num_workers > 0
    )
    
    test_loader = DataLoader(
        test_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=num_workers,
        collate_fn=collate_fn_2d,
        pin_memory=True,
        persistent_workers=num_workers > 0
    )
    
    return train_loader, val_loader, test_loader


if __name__ == "__main__":
    # Test the dataset
    from pathlib import Path
    
    data_dir = Path("O:/master_data_collection/isotopev2")
    
    dataset = SpectrumDataset2D(data_dir, target_time_intervals=60)
    sample = dataset[0]
    
    print(f"\nSample:")
    print(f"  Spectrum shape: {sample['spectrum'].shape}")
    print(f"  Presence labels: {sample['presence_labels'].sum().item():.0f} isotopes")
    print(f"  Sample ID: {sample['sample_id']}")
