"""
Isotope Index - Mapping between isotope names and model output indices.

This module provides a consistent mapping between isotope names and their
corresponding indices in the model's output tensors. This is critical for
training and inference to ensure consistent label encoding.
"""

import sys
from pathlib import Path
from typing import Dict, List, Optional

# Add project root to path for imports
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from synthetic_spectra.ground_truth.isotope_data import ISOTOPE_DATABASE, get_isotope_names


class IsotopeIndex:
    """
    Manages the mapping between isotope names and model indices.
    
    The index is deterministic - isotopes are sorted alphabetically to ensure
    consistent ordering across training and inference.
    """
    
    def __init__(self, isotope_names: Optional[List[str]] = None):
        """
        Initialize the isotope index.
        
        Args:
            isotope_names: Optional list of isotope names. If None, uses all
                          isotopes from the database.
        """
        if isotope_names is None:
            isotope_names = get_isotope_names()
        
        # Sort alphabetically for deterministic ordering
        self._isotope_names = sorted(isotope_names)
        
        # Build bidirectional mappings
        self._name_to_idx: Dict[str, int] = {
            name: idx for idx, name in enumerate(self._isotope_names)
        }
        self._idx_to_name: Dict[int, str] = {
            idx: name for idx, name in enumerate(self._isotope_names)
        }
    
    @property
    def num_isotopes(self) -> int:
        """Total number of isotopes in the index."""
        return len(self._isotope_names)
    
    @property
    def isotope_names(self) -> List[str]:
        """List of all isotope names in index order."""
        return self._isotope_names.copy()
    
    def name_to_index(self, name: str) -> int:
        """
        Get the index for an isotope name.
        
        Args:
            name: Isotope name (e.g., "Cs-137")
            
        Returns:
            Integer index for the isotope
            
        Raises:
            KeyError: If isotope name not in index
        """
        if name not in self._name_to_idx:
            raise KeyError(f"Isotope '{name}' not found in index. "
                          f"Available isotopes: {self._isotope_names[:5]}...")
        return self._name_to_idx[name]
    
    def index_to_name(self, idx: int) -> str:
        """
        Get the isotope name for an index.
        
        Args:
            idx: Integer index
            
        Returns:
            Isotope name string
            
        Raises:
            KeyError: If index out of range
        """
        if idx not in self._idx_to_name:
            raise KeyError(f"Index {idx} out of range. Valid range: 0-{self.num_isotopes-1}")
        return self._idx_to_name[idx]
    
    def names_to_indices(self, names: List[str]) -> List[int]:
        """Convert list of names to list of indices."""
        return [self.name_to_index(name) for name in names]
    
    def indices_to_names(self, indices: List[int]) -> List[str]:
        """Convert list of indices to list of names."""
        return [self.index_to_name(idx) for idx in indices]
    
    def save(self, path: Path):
        """Save the isotope index to a file."""
        with open(path, 'w') as f:
            for name in self._isotope_names:
                f.write(f"{name}\n")
    
    @classmethod
    def load(cls, path: Path) -> 'IsotopeIndex':
        """Load an isotope index from a file."""
        with open(path, 'r') as f:
            isotope_names = [line.strip() for line in f if line.strip()]
        return cls(isotope_names)
    
    def __repr__(self) -> str:
        return f"IsotopeIndex(num_isotopes={self.num_isotopes})"
    
    def __len__(self) -> int:
        return self.num_isotopes


# Global default isotope index using all isotopes from database
DEFAULT_ISOTOPE_INDEX = IsotopeIndex()


def get_default_isotope_index() -> IsotopeIndex:
    """Get the default isotope index with all database isotopes."""
    return DEFAULT_ISOTOPE_INDEX


if __name__ == "__main__":
    # Print isotope index information
    index = get_default_isotope_index()
    print(f"Isotope Index: {index}")
    print(f"\nFirst 10 isotopes:")
    for i in range(min(10, index.num_isotopes)):
        print(f"  {i:3d}: {index.index_to_name(i)}")
    print(f"\nLast 10 isotopes:")
    for i in range(max(0, index.num_isotopes - 10), index.num_isotopes):
        print(f"  {i:3d}: {index.index_to_name(i)}")
