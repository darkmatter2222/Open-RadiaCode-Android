"""
Dataset and DataLoader for Vega Model Training

Handles loading synthetic gamma spectra from numpy files and converting
them to PyTorch tensors with proper labels for multi-task learning.

Supports two label formats:
1. Individual JSON files per sample (recommended for large datasets)
2. Combined labels.json file (legacy format)
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
class SpectrumSample:
    """A single spectrum sample with metadata."""
    sample_id: str
    spectrum: np.ndarray  # 2D array (time_intervals, channels) or 1D (channels,)
    isotopes_present: List[str]
    activities_bq: Dict[str, float]
    duration_seconds: float
    detector: str


class SpectrumDataset(Dataset):
    """
    PyTorch Dataset for synthetic gamma spectra.
    
    Loads spectra from numpy files and their labels from JSON files.
    Supports both individual JSON files per sample (efficient for large datasets)
    and combined labels.json (legacy format).
    
    Converts to tensors suitable for the Vega model.
    """
    
    def __init__(
        self,
        data_dir: Path,
        isotope_index: Optional[IsotopeIndex] = None,
        max_activity_bq: float = 1000.0,
        collapse_time: bool = True,
        transform=None
    ):
        """
        Initialize the dataset.
        
        Args:
            data_dir: Path to directory containing spectra/ subdirectory
            isotope_index: Index mapping isotope names to indices
            max_activity_bq: Maximum activity for normalization
            collapse_time: If True, average across time dimension to get 1D spectrum
            transform: Optional transform to apply to spectra
        """
        self.data_dir = Path(data_dir)
        self.spectra_dir = self.data_dir / "spectra"
        self.isotope_index = isotope_index or get_default_isotope_index()
        self.max_activity_bq = max_activity_bq
        self.collapse_time = collapse_time
        self.transform = transform
        
        # Detect label format and load sample list
        self.use_individual_labels = self._detect_label_format()
        
        if self.use_individual_labels:
            # Scan for individual JSON files (efficient - no loading needed)
            self.sample_ids = self._scan_for_samples()
            self.metadata = None  # Labels loaded on-demand
            print(f"Using individual label files (efficient mode)")
        else:
            # Load combined labels.json (legacy mode)
            self.metadata = self._load_metadata()
            self.sample_ids = list(self.metadata['samples'].keys())
            print(f"Using combined labels.json (legacy mode)")
        
        print(f"Loaded dataset with {len(self.sample_ids)} samples")
        print(f"Isotope index has {self.isotope_index.num_isotopes} isotopes")
    
    def _detect_label_format(self) -> bool:
        """Detect whether to use individual JSON files or combined labels.json."""
        # Check if individual JSON files exist
        json_files = list(self.spectra_dir.glob("spectrum_*.json"))
        if len(json_files) > 0:
            return True
        
        # Fall back to combined labels.json
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
            # Extract sample ID from filename: spectrum_{id}.npy
            filename = npy_path.stem  # spectrum_{id}
            sample_id = filename.replace("spectrum_", "")
            sample_ids.append(sample_id)
        return sample_ids
    
    def _load_metadata(self) -> Dict:
        """Load the combined labels.json metadata file (legacy format)."""
        labels_path = self.data_dir / "labels.json"
        if not labels_path.exists():
            raise FileNotFoundError(f"Labels file not found: {labels_path}")
        
        with open(labels_path, 'r') as f:
            return json.load(f)
    
    def _load_sample_label(self, sample_id: str) -> Dict:
        """Load label for a single sample (individual JSON or from combined)."""
        if self.use_individual_labels:
            json_path = self.spectra_dir / f"spectrum_{sample_id}.json"
            with open(json_path, 'r') as f:
                return json.load(f)
        else:
            return self.metadata['samples'][sample_id]
    
    def __len__(self) -> int:
        return len(self.sample_ids)
    
    def __getitem__(self, idx: int) -> Dict[str, torch.Tensor]:
        """
        Get a single sample.
        
        Returns:
            Dictionary containing:
                - spectrum: Tensor of shape (num_channels,)
                - presence_labels: Binary tensor (num_isotopes,) indicating presence
                - activity_labels: Tensor (num_isotopes,) with normalized activities
                - sample_id: String identifier
        """
        sample_id = self.sample_ids[idx]
        sample_meta = self._load_sample_label(sample_id)
        
        # Load spectrum
        spectrum_path = self.spectra_dir / f"spectrum_{sample_id}.npy"
        spectrum = np.load(spectrum_path)
        
        # Collapse time dimension if needed
        if self.collapse_time and spectrum.ndim == 2:
            # Average across time intervals to get single spectrum
            spectrum = spectrum.mean(axis=0)
        
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
                # Isotope not in our index (might be a decay product)
                pass
        
        # Create activity labels (normalized)
        activity_labels = torch.zeros(self.isotope_index.num_isotopes, dtype=torch.float32)
        for isotope_name, activity in sample_meta.get('source_activities_bq', {}).items():
            try:
                idx_isotope = self.isotope_index.name_to_index(isotope_name)
                # Normalize activity to [0, 1] range
                activity_labels[idx_isotope] = min(activity / self.max_activity_bq, 1.0)
            except KeyError:
                pass
        
        return {
            'spectrum': spectrum_tensor,
            'presence_labels': presence_labels,
            'activity_labels': activity_labels,
            'sample_id': sample_id
        }
    
    def get_sample_info(self, idx: int) -> Dict:
        """Get metadata for a sample without loading the spectrum."""
        sample_id = self.sample_ids[idx]
        return {
            'sample_id': sample_id,
            **self.metadata['samples'][sample_id]
        }


def collate_fn(batch: List[Dict]) -> Dict[str, torch.Tensor]:
    """
    Custom collate function to handle batching.
    
    Args:
        batch: List of sample dictionaries
        
    Returns:
        Batched dictionary with stacked tensors
    """
    return {
        'spectrum': torch.stack([s['spectrum'] for s in batch]),
        'presence_labels': torch.stack([s['presence_labels'] for s in batch]),
        'activity_labels': torch.stack([s['activity_labels'] for s in batch]),
        'sample_ids': [s['sample_id'] for s in batch]
    }


def create_data_loaders(
    data_dir: Path,
    batch_size: int = 32,
    train_split: float = 0.8,
    val_split: float = 0.1,
    test_split: float = 0.1,
    num_workers: int = 8,
    prefetch_factor: int = 4,
    persistent_workers: bool = True,
    isotope_index: Optional[IsotopeIndex] = None,
    max_activity_bq: float = 1000.0,
    seed: int = 42
) -> Tuple[DataLoader, DataLoader, DataLoader]:
    """
    Create train, validation, and test data loaders.
    
    Args:
        data_dir: Path to data directory
        batch_size: Batch size for training
        train_split: Fraction of data for training
        val_split: Fraction of data for validation
        test_split: Fraction of data for testing
        num_workers: Number of data loading workers (parallel I/O)
        prefetch_factor: Batches to prefetch per worker
        persistent_workers: Keep workers alive between epochs
        isotope_index: Isotope name to index mapping
        max_activity_bq: Maximum activity for normalization
        seed: Random seed for reproducibility
        
    Returns:
        Tuple of (train_loader, val_loader, test_loader)
    """
    assert abs(train_split + val_split + test_split - 1.0) < 1e-6, \
        "Splits must sum to 1.0"
    
    # Create full dataset
    full_dataset = SpectrumDataset(
        data_dir=data_dir,
        isotope_index=isotope_index,
        max_activity_bq=max_activity_bq
    )
    
    # Calculate split sizes
    total_size = len(full_dataset)
    train_size = int(total_size * train_split)
    val_size = int(total_size * val_split)
    test_size = total_size - train_size - val_size
    
    # Handle small datasets
    if train_size == 0:
        train_size = max(1, total_size - 2)
    if val_size == 0 and total_size > 1:
        val_size = 1
        train_size = max(1, train_size - 1)
    if test_size == 0 and total_size > 2:
        test_size = 1
        train_size = max(1, train_size - 1)
    
    # Ensure sizes add up
    test_size = total_size - train_size - val_size
    
    print(f"Dataset splits: train={train_size}, val={val_size}, test={test_size}")
    
    # Split dataset
    generator = torch.Generator().manual_seed(seed)
    train_dataset, val_dataset, test_dataset = random_split(
        full_dataset,
        [train_size, val_size, test_size],
        generator=generator
    )
    
    # Create data loaders with parallel loading support
    # For Windows, num_workers > 0 requires spawn method (handled by PyTorch)
    use_workers = num_workers > 0
    
    train_loader = DataLoader(
        train_dataset,
        batch_size=min(batch_size, train_size),
        shuffle=True,
        num_workers=num_workers,
        collate_fn=collate_fn,
        pin_memory=True,
        prefetch_factor=prefetch_factor if use_workers else None,
        persistent_workers=persistent_workers and use_workers,
        drop_last=True  # Drop incomplete batches for consistent training
    )
    
    val_loader = DataLoader(
        val_dataset,
        batch_size=min(batch_size, max(1, val_size)),
        shuffle=False,
        num_workers=num_workers,
        collate_fn=collate_fn,
        pin_memory=True,
        prefetch_factor=prefetch_factor if use_workers else None,
        persistent_workers=persistent_workers and use_workers
    ) if val_size > 0 else None
    
    test_loader = DataLoader(
        test_dataset,
        batch_size=min(batch_size, max(1, test_size)),
        shuffle=False,
        num_workers=num_workers,
        collate_fn=collate_fn,
        pin_memory=True,
        prefetch_factor=prefetch_factor if use_workers else None,
        persistent_workers=persistent_workers and use_workers
    ) if test_size > 0 else None
    
    if num_workers > 0:
        print(f"DataLoader: {num_workers} workers, prefetch_factor={prefetch_factor}, persistent={persistent_workers}")
    
    return train_loader, val_loader, test_loader


if __name__ == "__main__":
    import sys
    
    # Test dataset loading
    data_dir = Path(__file__).parent.parent.parent / "data" / "synthetic"
    
    if not data_dir.exists():
        print(f"Data directory not found: {data_dir}")
        sys.exit(1)
    
    # Create dataset
    dataset = SpectrumDataset(data_dir)
    print(f"\nDataset size: {len(dataset)}")
    
    # Get a sample
    sample = dataset[0]
    print(f"\nSample keys: {sample.keys()}")
    print(f"Spectrum shape: {sample['spectrum'].shape}")
    print(f"Presence labels shape: {sample['presence_labels'].shape}")
    print(f"Activity labels shape: {sample['activity_labels'].shape}")
    print(f"Presence sum: {sample['presence_labels'].sum().item()}")
    
    # Create data loaders
    train_loader, val_loader, test_loader = create_data_loaders(
        data_dir,
        batch_size=4
    )
    
    print(f"\nTrain batches: {len(train_loader)}")
    if val_loader:
        print(f"Val batches: {len(val_loader)}")
    if test_loader:
        print(f"Test batches: {len(test_loader)}")
    
    # Test a batch
    batch = next(iter(train_loader))
    print(f"\nBatch spectrum shape: {batch['spectrum'].shape}")
    print(f"Batch presence shape: {batch['presence_labels'].shape}")
