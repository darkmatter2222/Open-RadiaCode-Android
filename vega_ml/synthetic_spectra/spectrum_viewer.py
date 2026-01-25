"""
Spectrum Viewer Application

A simple GUI application to browse and visualize generated synthetic spectra.
Randomly samples from the available spectra to avoid loading all files at once.

Usage:
    python -m synthetic_spectra.spectrum_viewer
    
    Or with options:
    python -m synthetic_spectra.spectrum_viewer --num_samples 200 --data_dir ./data/synthetic/spectra
"""

import tkinter as tk
from tkinter import ttk
import numpy as np
import json
from pathlib import Path
import random
from typing import Optional, List, Dict, Any

from .config import RADIACODE_CONFIGS, get_default_config

# Try to import matplotlib for plotting
try:
    import matplotlib
    matplotlib.use('TkAgg')
    import matplotlib.pyplot as plt
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
    from matplotlib.figure import Figure
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False
    print("Warning: matplotlib not found. Install with: pip install matplotlib")


class SpectrumViewer:
    """
    GUI application for viewing synthetic gamma spectra.
    """
    
    def __init__(
        self,
        data_dir: str = "./data/synthetic/spectra",
        num_samples: int = 100,
        random_seed: Optional[int] = None
    ):
        """
        Initialize the spectrum viewer.
        
        Args:
            data_dir: Directory containing spectrum .npy and .json files
            num_samples: Number of random samples to load (for performance)
            random_seed: Random seed for reproducible sample selection
        """
        self.data_dir = Path(data_dir)
        self.num_samples = num_samples
        
        if random_seed is not None:
            random.seed(random_seed)
        
        # Find and sample spectrum files
        self.spectrum_files = self._discover_and_sample_files()
        
        if not self.spectrum_files:
            raise ValueError(f"No spectrum files found in {self.data_dir}")
        
        print(f"Loaded {len(self.spectrum_files)} spectrum samples")
        
        # Current state
        self.current_index = 0
        self.current_spectrum: Optional[np.ndarray] = None
        self.current_metadata: Optional[Dict[str, Any]] = None
        
        # Setup GUI
        self._setup_gui()
        
        # Load first spectrum
        self._load_current_spectrum()
    
    def _discover_and_sample_files(self) -> List[Path]:
        """Find all spectrum files and randomly sample them."""
        # Find all .npy files
        all_npy_files = list(self.data_dir.glob("spectrum_*.npy"))
        
        if not all_npy_files:
            # Try without prefix
            all_npy_files = list(self.data_dir.glob("*.npy"))
        
        print(f"Found {len(all_npy_files)} total spectrum files")
        
        # Randomly sample if we have more than requested
        if len(all_npy_files) > self.num_samples:
            sampled = random.sample(all_npy_files, self.num_samples)
        else:
            sampled = all_npy_files
        
        # Sort by name for consistent ordering in dropdown
        return sorted(sampled, key=lambda p: p.stem)
    
    def _setup_gui(self):
        """Setup the tkinter GUI."""
        self.root = tk.Tk()
        self.root.title("Spectrum Viewer - Synthetic Gamma Spectra")
        self.root.geometry("1200x800")
        
        # Main container
        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.grid(row=0, column=0, sticky="nsew")
        
        # Configure grid weights for resizing
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        main_frame.columnconfigure(0, weight=1)
        main_frame.rowconfigure(1, weight=1)
        
        # === Top controls ===
        controls_frame = ttk.Frame(main_frame)
        controls_frame.grid(row=0, column=0, sticky="ew", pady=(0, 10))
        controls_frame.columnconfigure(1, weight=1)
        
        # Dropdown for spectrum selection
        ttk.Label(controls_frame, text="Select Spectrum:").grid(row=0, column=0, padx=(0, 10))
        
        self.spectrum_var = tk.StringVar()
        self.spectrum_dropdown = ttk.Combobox(
            controls_frame,
            textvariable=self.spectrum_var,
            values=[f.stem for f in self.spectrum_files],
            state="readonly",
            width=50
        )
        self.spectrum_dropdown.grid(row=0, column=1, sticky="ew", padx=(0, 10))
        self.spectrum_dropdown.bind("<<ComboboxSelected>>", self._on_spectrum_selected)
        self.spectrum_dropdown.current(0)
        
        # Navigation buttons
        nav_frame = ttk.Frame(controls_frame)
        nav_frame.grid(row=0, column=2)
        
        ttk.Button(nav_frame, text="◀ Prev", command=self._prev_spectrum).pack(side="left", padx=2)
        ttk.Button(nav_frame, text="Next ▶", command=self._next_spectrum).pack(side="left", padx=2)
        ttk.Button(nav_frame, text="🎲 Random", command=self._random_spectrum).pack(side="left", padx=2)
        
        # Sample count label
        self.count_label = ttk.Label(
            controls_frame,
            text=f"Showing {len(self.spectrum_files)} of available spectra"
        )
        self.count_label.grid(row=0, column=3, padx=(10, 0))
        
        # === Plotting area ===
        plot_frame = ttk.Frame(main_frame)
        plot_frame.grid(row=1, column=0, sticky="nsew")
        plot_frame.columnconfigure(0, weight=1)
        plot_frame.rowconfigure(0, weight=1)
        
        if HAS_MATPLOTLIB:
            # Create matplotlib figure with 2 subplots
            self.fig = Figure(figsize=(12, 6), dpi=100)
            
            # 2D spectrogram (heatmap)
            self.ax_2d = self.fig.add_subplot(121)
            self.ax_2d.set_title("2D Spectrogram (Time vs Energy)")
            self.ax_2d.set_xlabel("Energy Channel")
            self.ax_2d.set_ylabel("Time Interval (s)")
            
            # 1D summed spectrum
            self.ax_1d = self.fig.add_subplot(122)
            self.ax_1d.set_title("Summed Spectrum")
            self.ax_1d.set_xlabel("Energy (keV)")
            self.ax_1d.set_ylabel("Counts (normalized)")
            
            self.fig.tight_layout()
            
            # Embed in tkinter
            self.canvas = FigureCanvasTkAgg(self.fig, master=plot_frame)
            self.canvas.draw()
            self.canvas.get_tk_widget().grid(row=0, column=0, sticky="nsew")
            
            # Toolbar
            toolbar_frame = ttk.Frame(plot_frame)
            toolbar_frame.grid(row=1, column=0, sticky="ew")
            self.toolbar = NavigationToolbar2Tk(self.canvas, toolbar_frame)
            self.toolbar.update()
        else:
            ttk.Label(
                plot_frame,
                text="matplotlib not installed. Install with: pip install matplotlib",
                font=("Arial", 14)
            ).grid(row=0, column=0, pady=50)
        
        # === Metadata panel ===
        metadata_frame = ttk.LabelFrame(main_frame, text="Spectrum Metadata", padding="10")
        metadata_frame.grid(row=2, column=0, sticky="ew", pady=(10, 0))
        
        self.metadata_text = tk.Text(
            metadata_frame,
            height=10,
            wrap="word",
            font=("Consolas", 10)
        )
        self.metadata_text.pack(fill="both", expand=True)
        
        # Scrollbar for metadata
        scrollbar = ttk.Scrollbar(metadata_frame, orient="vertical", command=self.metadata_text.yview)
        scrollbar.pack(side="right", fill="y")
        self.metadata_text.configure(yscrollcommand=scrollbar.set)
    
    def _load_current_spectrum(self):
        """Load the currently selected spectrum and its metadata."""
        if not self.spectrum_files:
            return
        
        spectrum_path = self.spectrum_files[self.current_index]
        json_path = spectrum_path.with_suffix(".json")
        
        # Load numpy array
        try:
            self.current_spectrum = np.load(spectrum_path)
            print(f"Loaded spectrum: {spectrum_path.name}, shape: {self.current_spectrum.shape}")
        except Exception as e:
            print(f"Error loading spectrum: {e}")
            self.current_spectrum = None
        
        # Load metadata JSON
        if json_path.exists():
            try:
                with open(json_path, 'r') as f:
                    self.current_metadata = json.load(f)
            except Exception as e:
                print(f"Error loading metadata: {e}")
                self.current_metadata = None
        else:
            self.current_metadata = None
        
        # Update display
        self._update_plot()
        self._update_metadata()
    
    def _update_plot(self):
        """Update the matplotlib plots."""
        if not HAS_MATPLOTLIB or self.current_spectrum is None:
            return
        
        # Clear previous plots
        self.ax_2d.clear()
        self.ax_1d.clear()
        
        spectrum = self.current_spectrum

        num_channels = spectrum.shape[1] if len(spectrum.shape) > 1 else len(spectrum)

        # Energy axis: use the same mapping as generation whenever possible.
        detector_name = None
        if isinstance(self.current_metadata, dict):
            detector_name = (
                self.current_metadata.get('detector')
                or self.current_metadata.get('detector_name')
                or (self.current_metadata.get('config') or {}).get('detector_name')
            )
        detector_config = RADIACODE_CONFIGS.get(detector_name, get_default_config())

        energy_bins = detector_config.get_energy_bins()
        if len(energy_bins) != num_channels:
            # Fallback: linear mapping for the available channel count.
            energy_bins = np.linspace(
                detector_config.energy_min_kev,
                detector_config.energy_max_kev,
                num_channels,
                dtype=np.float64
            )

        energy_min = float(energy_bins[0])
        energy_max = float(energy_bins[-1])
        
        if len(spectrum.shape) == 2:
            # 2D spectrogram
            num_intervals = spectrum.shape[0]
            
            # Plot 2D heatmap
            im = self.ax_2d.imshow(
                spectrum,
                aspect='auto',
                origin='lower',
                extent=[energy_min, energy_max, 0, num_intervals],
                cmap='viridis'
            )
            self.ax_2d.set_title(f"2D Spectrogram ({num_intervals} time intervals)")
            self.ax_2d.set_xlabel("Energy (keV)")
            self.ax_2d.set_ylabel("Time Interval (s)")
            
            # Add colorbar - use a dedicated axes to avoid removal issues
            if not hasattr(self, '_cbar_ax') or self._cbar_ax is None:
                # Create a dedicated colorbar axes on first use
                self._cbar_ax = self.fig.add_axes([0.46, 0.55, 0.01, 0.35])
            else:
                self._cbar_ax.clear()
            self._colorbar = self.fig.colorbar(im, cax=self._cbar_ax, label='Counts')
            
            # Sum across time for 1D spectrum
            summed_spectrum = spectrum.sum(axis=0)
        else:
            # 1D spectrum
            self.ax_2d.text(
                0.5, 0.5, "1D Spectrum\n(No time dimension)",
                ha='center', va='center', transform=self.ax_2d.transAxes
            )
            summed_spectrum = spectrum
        
        # Plot 1D summed spectrum
        self.ax_1d.plot(energy_bins, summed_spectrum, 'b-', linewidth=0.8)
        self.ax_1d.fill_between(energy_bins, 0, summed_spectrum, alpha=0.3)
        self.ax_1d.set_title("Summed Spectrum")
        self.ax_1d.set_xlabel("Energy (keV)")
        self.ax_1d.set_ylabel("Counts (normalized)")
        self.ax_1d.set_xlim(energy_min, energy_max)
        self.ax_1d.set_ylim(0, None)
        self.ax_1d.grid(True, alpha=0.3)
        
        # Add vertical lines for common peaks if metadata available
        if self.current_metadata:
            isotopes = self.current_metadata.get('isotopes', [])
            if isotopes:
                # Add some common reference lines
                peak_energies = self._get_peak_energies_from_metadata()
                for energy, label in peak_energies[:5]:  # Show top 5 peaks
                    if energy_min < energy < energy_max:
                        self.ax_1d.axvline(x=energy, color='red', linestyle='--', alpha=0.5, linewidth=0.8)
                        self.ax_1d.annotate(
                            label,
                            xy=(energy, self.ax_1d.get_ylim()[1] * 0.95),
                            fontsize=8,
                            rotation=90,
                            ha='right',
                            va='top'
                        )
        
        # Use subplots_adjust instead of tight_layout to avoid colorbar axes conflict
        self.fig.subplots_adjust(left=0.08, right=0.95, top=0.92, bottom=0.12, wspace=0.3)
        self.canvas.draw()
    
    def _get_peak_energies_from_metadata(self) -> List[tuple]:
        """Extract key peak energies from metadata for annotation."""
        peaks = []
        
        if not self.current_metadata:
            return peaks
        
        isotopes = self.current_metadata.get('isotopes', [])
        
        # Common isotope peak energies
        isotope_peaks = {
            'Cs-137': [(661.66, 'Cs-137')],
            'Co-60': [(1173.23, 'Co-60'), (1332.49, 'Co-60')],
            'Am-241': [(59.54, 'Am-241')],
            'Ba-133': [(356.0, 'Ba-133'), (81.0, 'Ba-133')],
            'Na-22': [(511.0, 'Na-22'), (1274.54, 'Na-22')],
            'K-40': [(1460.83, 'K-40')],
            'Eu-152': [(344.28, 'Eu-152'), (1408.0, 'Eu-152')],
            'I-131': [(364.49, 'I-131')],
            'Tc-99m': [(140.51, 'Tc-99m')],
            'Co-57': [(122.06, 'Co-57')],
        }
        
        for iso_info in isotopes:
            iso_name = iso_info.get('name', '') if isinstance(iso_info, dict) else str(iso_info)
            if iso_name in isotope_peaks:
                peaks.extend(isotope_peaks[iso_name])
        
        return peaks
    
    def _update_metadata(self):
        """Update the metadata text display."""
        self.metadata_text.delete(1.0, tk.END)
        
        if self.current_spectrum is not None:
            # Add spectrum shape info
            info = f"Spectrum Shape: {self.current_spectrum.shape}\n"
            info += f"Data type: {self.current_spectrum.dtype}\n"
            info += f"Value range: [{self.current_spectrum.min():.4f}, {self.current_spectrum.max():.4f}]\n"
            info += f"Mean value: {self.current_spectrum.mean():.4f}\n"
            info += "\n" + "="*50 + "\n\n"
            self.metadata_text.insert(tk.END, info)
        
        if self.current_metadata:
            # Pretty print JSON metadata
            formatted = json.dumps(self.current_metadata, indent=2)
            self.metadata_text.insert(tk.END, formatted)
        else:
            self.metadata_text.insert(tk.END, "No metadata JSON file found for this spectrum.")
    
    def _on_spectrum_selected(self, event=None):
        """Handle spectrum selection from dropdown."""
        selection = self.spectrum_var.get()
        for i, f in enumerate(self.spectrum_files):
            if f.stem == selection:
                self.current_index = i
                break
        self._load_current_spectrum()
    
    def _prev_spectrum(self):
        """Go to previous spectrum."""
        self.current_index = (self.current_index - 1) % len(self.spectrum_files)
        self.spectrum_dropdown.current(self.current_index)
        self._load_current_spectrum()
    
    def _next_spectrum(self):
        """Go to next spectrum."""
        self.current_index = (self.current_index + 1) % len(self.spectrum_files)
        self.spectrum_dropdown.current(self.current_index)
        self._load_current_spectrum()
    
    def _random_spectrum(self):
        """Jump to a random spectrum."""
        self.current_index = random.randint(0, len(self.spectrum_files) - 1)
        self.spectrum_dropdown.current(self.current_index)
        self._load_current_spectrum()
    
    def run(self):
        """Start the GUI main loop."""
        self.root.mainloop()


def main():
    """Main entry point."""
    import argparse
    
    parser = argparse.ArgumentParser(
        description="Visualize synthetic gamma spectra"
    )
    parser.add_argument(
        "--data_dir",
        type=str,
        default="./data/synthetic/spectra",
        help="Directory containing spectrum files (default: ./data/synthetic/spectra)"
    )
    parser.add_argument(
        "--num_samples",
        type=int,
        default=100,
        help="Number of random samples to load (default: 100)"
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Random seed for reproducible sample selection"
    )
    
    args = parser.parse_args()
    
    if not HAS_MATPLOTLIB:
        print("ERROR: matplotlib is required for visualization.")
        print("Install with: pip install matplotlib")
        return
    
    print(f"Starting Spectrum Viewer...")
    print(f"Data directory: {args.data_dir}")
    print(f"Loading up to {args.num_samples} random samples...")
    
    try:
        viewer = SpectrumViewer(
            data_dir=args.data_dir,
            num_samples=args.num_samples,
            random_seed=args.seed
        )
        viewer.run()
    except ValueError as e:
        print(f"Error: {e}")
    except Exception as e:
        print(f"Unexpected error: {e}")
        raise


if __name__ == "__main__":
    main()
