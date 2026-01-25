"""
Training Data Visualization Script

Generates an interactive HTML dashboard with Plotly visualizations to explore
the synthetic training data distribution, isotope combinations, activities,
durations, and sample spectra.

Usage:
    python -m synthetic_spectra.visualize_training_data
    python -m synthetic_spectra.visualize_training_data --data-dir data/synthetic/spectra
    python -m synthetic_spectra.visualize_training_data --output report.html --max-samples 1000

Output:
    An interactive HTML file that can be opened in any browser.
"""

import argparse
import json
import sys
from pathlib import Path
from collections import Counter, defaultdict
from itertools import combinations
from typing import Dict, List, Tuple, Optional
import numpy as np

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    import plotly.graph_objects as go
    import plotly.express as px
    from plotly.subplots import make_subplots
except ImportError:
    print("Error: Plotly is required. Install it with: pip install plotly")
    sys.exit(1)

from synthetic_spectra.ground_truth.isotope_data import (
    ISOTOPE_DATABASE,
    IsotopeCategory,
    get_isotopes_by_category,
)


def load_all_metadata(data_dir: Path, max_samples: Optional[int] = None) -> List[Dict]:
    """Load all JSON metadata files from the data directory."""
    json_files = sorted(data_dir.glob("*.json"))
    
    if max_samples is not None and len(json_files) > max_samples:
        # Randomly sample if we have too many
        np.random.seed(42)
        indices = np.random.choice(len(json_files), max_samples, replace=False)
        json_files = [json_files[i] for i in sorted(indices)]
    
    metadata_list = []
    print(f"Loading {len(json_files)} metadata files...")
    
    for i, json_file in enumerate(json_files):
        try:
            with open(json_file, 'r') as f:
                data = json.load(f)
                data['_filename'] = json_file.stem
                metadata_list.append(data)
        except Exception as e:
            print(f"  Warning: Could not load {json_file}: {e}")
        
        if (i + 1) % 1000 == 0:
            print(f"  Loaded {i + 1}/{len(json_files)} files...")
    
    print(f"Loaded {len(metadata_list)} samples successfully.")
    return metadata_list


def load_sample_spectra(data_dir: Path, sample_ids: List[str]) -> Dict[str, np.ndarray]:
    """Load a few sample spectra for visualization."""
    spectra = {}
    for sample_id in sample_ids:
        npy_file = data_dir / f"{sample_id}.npy"
        if npy_file.exists():
            try:
                spectra[sample_id] = np.load(npy_file)
            except Exception as e:
                print(f"  Warning: Could not load spectrum {npy_file}: {e}")
    return spectra


def compute_statistics(metadata_list: List[Dict]) -> Dict:
    """Compute various statistics from the metadata."""
    stats = {
        'total_samples': len(metadata_list),
        'isotope_counts': Counter(),
        'isotope_cooccurrence': defaultdict(int),
        'num_isotopes_distribution': Counter(),
        'durations': [],
        'activities': defaultdict(list),
        'detectors': Counter(),
        'category_counts': Counter(),
        'samples_by_num_isotopes': defaultdict(list),
    }
    
    for meta in metadata_list:
        isotopes = meta.get('isotopes', [])
        source_activities = meta.get('source_activities_bq', {})
        duration = meta.get('duration_seconds', 0)
        detector = meta.get('detector', 'unknown')
        
        # Count isotopes
        for iso in isotopes:
            stats['isotope_counts'][iso] += 1
            
            # Get category
            if iso in ISOTOPE_DATABASE:
                cat = ISOTOPE_DATABASE[iso].category.value
                stats['category_counts'][cat] += 1
        
        # Count isotope pairs (co-occurrence)
        for pair in combinations(sorted(isotopes), 2):
            stats['isotope_cooccurrence'][pair] += 1
        
        # Number of isotopes distribution
        num_iso = len(isotopes)
        stats['num_isotopes_distribution'][num_iso] += 1
        stats['samples_by_num_isotopes'][num_iso].append(meta['_filename'])
        
        # Duration
        stats['durations'].append(duration)
        
        # Activities per isotope
        for iso, activity in source_activities.items():
            stats['activities'][iso].append(activity)
        
        # Detector
        stats['detectors'][detector] += 1
    
    return stats


def create_isotope_frequency_chart(stats: Dict) -> go.Figure:
    """Create bar chart of isotope frequencies."""
    isotope_counts = stats['isotope_counts']
    
    # Sort by frequency
    sorted_isotopes = sorted(isotope_counts.items(), key=lambda x: x[1], reverse=True)
    isotopes, counts = zip(*sorted_isotopes) if sorted_isotopes else ([], [])
    
    # Color by category
    colors = []
    category_colors = {
        'natural_background': '#2ecc71',
        'primordial': '#27ae60',
        'cosmogenic': '#1abc9c',
        'u238_chain': '#e74c3c',
        'th232_chain': '#c0392b',
        'u235_chain': '#d35400',
        'calibration': '#3498db',
        'industrial': '#9b59b6',
        'medical': '#f1c40f',
        'reactor_fallout': '#e67e22',
        'activation': '#95a5a6',
    }
    
    for iso in isotopes:
        if iso in ISOTOPE_DATABASE:
            cat = ISOTOPE_DATABASE[iso].category.value
            colors.append(category_colors.get(cat, '#7f8c8d'))
        else:
            colors.append('#7f8c8d')
    
    fig = go.Figure(data=[
        go.Bar(
            x=list(isotopes),
            y=list(counts),
            marker_color=colors,
            hovertemplate="<b>%{x}</b><br>Count: %{y}<extra></extra>"
        )
    ])
    
    fig.update_layout(
        title="Isotope Frequency Distribution",
        xaxis_title="Isotope",
        yaxis_title="Number of Samples",
        xaxis_tickangle=-45,
        height=500,
        showlegend=False
    )
    
    return fig


def create_category_pie_chart(stats: Dict) -> go.Figure:
    """Create pie chart of isotope categories."""
    category_counts = stats['category_counts']
    
    if not category_counts:
        return go.Figure().add_annotation(text="No category data available", 
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    labels = list(category_counts.keys())
    values = list(category_counts.values())
    
    # Pretty names for categories
    pretty_names = {
        'natural_background': 'Natural Background',
        'primordial': 'Primordial',
        'cosmogenic': 'Cosmogenic',
        'u238_chain': 'U-238 Chain',
        'th232_chain': 'Th-232 Chain',
        'u235_chain': 'U-235 Chain',
        'calibration': 'Calibration',
        'industrial': 'Industrial',
        'medical': 'Medical',
        'reactor_fallout': 'Reactor/Fallout',
        'activation': 'Activation Products',
    }
    
    labels = [pretty_names.get(l, l) for l in labels]
    
    fig = go.Figure(data=[
        go.Pie(
            labels=labels,
            values=values,
            hole=0.4,
            hovertemplate="<b>%{label}</b><br>Count: %{value}<br>%{percent}<extra></extra>"
        )
    ])
    
    fig.update_layout(
        title="Isotope Categories Distribution",
        height=450,
    )
    
    return fig


def create_num_isotopes_histogram(stats: Dict) -> go.Figure:
    """Create histogram of number of isotopes per sample."""
    num_iso_dist = stats['num_isotopes_distribution']
    
    x = sorted(num_iso_dist.keys())
    y = [num_iso_dist[k] for k in x]
    
    # Calculate percentages
    total = sum(y)
    percentages = [f"{(v/total)*100:.1f}%" for v in y]
    
    fig = go.Figure(data=[
        go.Bar(
            x=[str(k) for k in x],
            y=y,
            text=percentages,
            textposition='auto',
            marker_color='#3498db',
            hovertemplate="<b>%{x} isotopes</b><br>Count: %{y}<br>%{text}<extra></extra>"
        )
    ])
    
    fig.update_layout(
        title="Sample Complexity (Number of Isotopes per Sample)",
        xaxis_title="Number of Source Isotopes",
        yaxis_title="Number of Samples",
        height=400,
    )
    
    return fig


def create_duration_histogram(stats: Dict) -> go.Figure:
    """Create histogram of measurement durations."""
    durations = stats['durations']
    
    if not durations:
        return go.Figure().add_annotation(text="No duration data available",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    fig = go.Figure(data=[
        go.Histogram(
            x=durations,
            nbinsx=50,
            marker_color='#9b59b6',
            hovertemplate="Duration: %{x:.1f}s<br>Count: %{y}<extra></extra>"
        )
    ])
    
    fig.update_layout(
        title="Measurement Duration Distribution",
        xaxis_title="Duration (seconds)",
        yaxis_title="Number of Samples",
        height=400,
    )
    
    # Add statistics annotation
    mean_dur = np.mean(durations)
    std_dur = np.std(durations)
    min_dur = np.min(durations)
    max_dur = np.max(durations)
    
    fig.add_annotation(
        text=f"Mean: {mean_dur:.1f}s | Std: {std_dur:.1f}s | Range: [{min_dur:.1f}, {max_dur:.1f}]s",
        xref="paper", yref="paper",
        x=0.98, y=0.98,
        xanchor='right', yanchor='top',
        showarrow=False,
        bgcolor="white",
        bordercolor="black",
        borderwidth=1,
        font=dict(size=11)
    )
    
    return fig


def create_activity_boxplot(stats: Dict) -> go.Figure:
    """Create box plot of activities per isotope."""
    activities = stats['activities']
    
    if not activities:
        return go.Figure().add_annotation(text="No activity data available",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    # Sort by median activity
    sorted_isotopes = sorted(
        activities.keys(),
        key=lambda x: np.median(activities[x]) if activities[x] else 0,
        reverse=True
    )
    
    # Only show top 30 for readability
    top_isotopes = sorted_isotopes[:30]
    
    fig = go.Figure()
    
    for iso in top_isotopes:
        fig.add_trace(go.Box(
            y=activities[iso],
            name=iso,
            boxpoints='outliers',
            hovertemplate=f"<b>{iso}</b><br>Activity: %{{y:.2f}} Bq<extra></extra>"
        ))
    
    fig.update_layout(
        title="Activity Distribution by Isotope (Top 30)",
        xaxis_title="Isotope",
        yaxis_title="Activity (Bq)",
        xaxis_tickangle=-45,
        height=500,
        showlegend=False
    )
    
    return fig


def create_cooccurrence_heatmap(stats: Dict, top_n: int = 20) -> go.Figure:
    """Create heatmap of isotope co-occurrence."""
    cooccurrence = stats['isotope_cooccurrence']
    isotope_counts = stats['isotope_counts']
    
    if not cooccurrence:
        return go.Figure().add_annotation(text="No co-occurrence data (need multi-isotope samples)",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    # Get top N most frequent isotopes
    top_isotopes = [iso for iso, _ in isotope_counts.most_common(top_n)]
    
    # Build matrix
    n = len(top_isotopes)
    matrix = np.zeros((n, n))
    
    for i, iso1 in enumerate(top_isotopes):
        for j, iso2 in enumerate(top_isotopes):
            if i < j:
                pair = tuple(sorted([iso1, iso2]))
                matrix[i, j] = cooccurrence.get(pair, 0)
                matrix[j, i] = matrix[i, j]
    
    fig = go.Figure(data=go.Heatmap(
        z=matrix,
        x=top_isotopes,
        y=top_isotopes,
        colorscale='Blues',
        hovertemplate="<b>%{x}</b> + <b>%{y}</b><br>Co-occurrences: %{z}<extra></extra>"
    ))
    
    fig.update_layout(
        title=f"Isotope Co-occurrence Matrix (Top {top_n} Isotopes)",
        xaxis_tickangle=-45,
        height=600,
        width=700,
    )
    
    return fig


def create_activity_vs_duration_scatter(metadata_list: List[Dict]) -> go.Figure:
    """Create scatter plot of total activity vs duration."""
    durations = []
    total_activities = []
    num_isotopes = []
    sample_ids = []
    
    for meta in metadata_list:
        duration = meta.get('duration_seconds', 0)
        activities = meta.get('source_activities_bq', {})
        
        if duration > 0 and activities:
            durations.append(duration)
            total_activities.append(sum(activities.values()))
            num_isotopes.append(len(meta.get('isotopes', [])))
            sample_ids.append(meta['_filename'])
    
    if not durations:
        return go.Figure().add_annotation(text="No data available",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    fig = go.Figure(data=go.Scatter(
        x=durations,
        y=total_activities,
        mode='markers',
        marker=dict(
            size=6,
            color=num_isotopes,
            colorscale='Viridis',
            colorbar=dict(title="# Isotopes"),
            opacity=0.6
        ),
        text=sample_ids,
        hovertemplate="<b>%{text}</b><br>Duration: %{x:.1f}s<br>Total Activity: %{y:.2f} Bq<extra></extra>"
    ))
    
    fig.update_layout(
        title="Total Source Activity vs Measurement Duration",
        xaxis_title="Duration (seconds)",
        yaxis_title="Total Activity (Bq)",
        height=500,
    )
    
    return fig


def create_sample_spectrum_plot(spectra: Dict[str, np.ndarray], metadata_list: List[Dict]) -> go.Figure:
    """Create interactive plot of sample spectra."""
    if not spectra:
        return go.Figure().add_annotation(text="No spectrum data loaded",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    # Create a metadata lookup
    meta_lookup = {m['_filename']: m for m in metadata_list}
    
    # Energy axis (keV) - 1023 channels from 20 to 3000 keV
    num_channels = 1023
    energy = np.linspace(20, 3000, num_channels)
    
    fig = go.Figure()
    
    colors = px.colors.qualitative.Set2
    
    for i, (sample_id, spectrum) in enumerate(list(spectra.items())[:6]):
        # Sum across time intervals to get total spectrum
        total_spectrum = spectrum.sum(axis=0) if spectrum.ndim == 2 else spectrum
        
        # Get isotope info
        meta = meta_lookup.get(sample_id, {})
        isotopes = meta.get('isotopes', ['Unknown'])
        label = f"{sample_id[-6:]}: {', '.join(isotopes)}"
        
        fig.add_trace(go.Scatter(
            x=energy,
            y=total_spectrum,
            mode='lines',
            name=label,
            line=dict(color=colors[i % len(colors)], width=1),
            hovertemplate=f"<b>{label}</b><br>Energy: %{{x:.1f}} keV<br>Counts: %{{y:.2f}}<extra></extra>"
        ))
    
    fig.update_layout(
        title="Sample Spectra (Time-Integrated)",
        xaxis_title="Energy (keV)",
        yaxis_title="Normalized Counts",
        height=500,
        legend=dict(yanchor="top", y=0.99, xanchor="right", x=0.99),
        hovermode='closest'
    )
    
    return fig


def create_3d_spectrum_surface(spectrum: np.ndarray, sample_id: str) -> go.Figure:
    """Create 3D surface plot of a single spectrum (time vs energy vs counts)."""
    if spectrum.ndim != 2:
        return go.Figure().add_annotation(text="Spectrum must be 2D",
                                          xref="paper", yref="paper", x=0.5, y=0.5)
    
    num_intervals, num_channels = spectrum.shape
    
    # Create axes
    time_axis = np.arange(num_intervals)
    energy_axis = np.linspace(20, 3000, num_channels)
    
    # Downsample for performance if needed
    if num_intervals > 100:
        step = num_intervals // 100
        spectrum = spectrum[::step, :]
        time_axis = time_axis[::step]
    
    if num_channels > 256:
        ch_step = num_channels // 256
        spectrum = spectrum[:, ::ch_step]
        energy_axis = energy_axis[::ch_step]
    
    fig = go.Figure(data=[
        go.Surface(
            z=spectrum,
            x=energy_axis,
            y=time_axis,
            colorscale='Viridis',
            hovertemplate="Time: %{y}s<br>Energy: %{x:.1f} keV<br>Counts: %{z:.3f}<extra></extra>"
        )
    ])
    
    fig.update_layout(
        title=f"3D Spectrum View: {sample_id}",
        scene=dict(
            xaxis_title="Energy (keV)",
            yaxis_title="Time (s)",
            zaxis_title="Counts",
        ),
        height=600,
    )
    
    return fig


def create_summary_table(stats: Dict) -> str:
    """Create an HTML summary table."""
    total = stats['total_samples']
    num_unique_isotopes = len(stats['isotope_counts'])
    avg_isotopes_per_sample = sum(k * v for k, v in stats['num_isotopes_distribution'].items()) / total if total else 0
    
    durations = stats['durations']
    activities_all = [a for acts in stats['activities'].values() for a in acts]
    
    html = f"""
    <div style="padding: 20px; background: #f8f9fa; border-radius: 10px; margin: 20px 0;">
        <h3 style="margin-top: 0; color: #2c3e50;">📊 Dataset Summary</h3>
        <table style="width: 100%; border-collapse: collapse; font-size: 14px;">
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Total Samples</strong></td>
                <td style="padding: 8px;">{total:,}</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Unique Isotopes</strong></td>
                <td style="padding: 8px;">{num_unique_isotopes}</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Avg Isotopes per Sample</strong></td>
                <td style="padding: 8px;">{avg_isotopes_per_sample:.2f}</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Duration Range</strong></td>
                <td style="padding: 8px;">{min(durations) if durations else 0:.1f}s - {max(durations) if durations else 0:.1f}s</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Mean Duration</strong></td>
                <td style="padding: 8px;">{np.mean(durations) if durations else 0:.1f}s</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
                <td style="padding: 8px;"><strong>Activity Range</strong></td>
                <td style="padding: 8px;">{min(activities_all) if activities_all else 0:.2f} - {max(activities_all) if activities_all else 0:.2f} Bq</td>
            </tr>
            <tr>
                <td style="padding: 8px;"><strong>Detectors</strong></td>
                <td style="padding: 8px;">{', '.join(stats['detectors'].keys())}</td>
            </tr>
        </table>
    </div>
    """
    return html


def create_isotope_database_summary() -> go.Figure:
    """Create a sunburst chart of the isotope database by category."""
    # Build hierarchy data
    categories = defaultdict(list)
    for name, isotope in ISOTOPE_DATABASE.items():
        categories[isotope.category.value].append(name)
    
    # Create sunburst data
    ids = []
    labels = []
    parents = []
    values = []
    
    # Root
    ids.append("Isotope Database")
    labels.append("Isotope Database")
    parents.append("")
    values.append(len(ISOTOPE_DATABASE))
    
    # Categories and isotopes
    pretty_names = {
        'natural_background': 'Natural Background',
        'primordial': 'Primordial',
        'cosmogenic': 'Cosmogenic',
        'u238_chain': 'U-238 Chain',
        'th232_chain': 'Th-232 Chain',
        'u235_chain': 'U-235 Chain',
        'calibration': 'Calibration',
        'industrial': 'Industrial',
        'medical': 'Medical',
        'reactor_fallout': 'Reactor/Fallout',
        'activation': 'Activation',
    }
    
    for cat, isotopes in categories.items():
        cat_label = pretty_names.get(cat, cat)
        ids.append(cat_label)
        labels.append(f"{cat_label} ({len(isotopes)})")
        parents.append("Isotope Database")
        values.append(len(isotopes))
        
        for iso in isotopes:
            ids.append(f"{cat_label}/{iso}")
            labels.append(iso)
            parents.append(cat_label)
            values.append(1)
    
    fig = go.Figure(go.Sunburst(
        ids=ids,
        labels=labels,
        parents=parents,
        values=values,
        branchvalues="total",
        hovertemplate="<b>%{label}</b><extra></extra>"
    ))
    
    fig.update_layout(
        title=f"Isotope Database Structure ({len(ISOTOPE_DATABASE)} isotopes)",
        height=600,
    )
    
    return fig


def generate_html_report(
    data_dir: Path,
    output_file: Path,
    max_samples: Optional[int] = None
):
    """Generate the complete HTML report."""
    
    print("=" * 60)
    print("Training Data Visualization Report Generator")
    print("=" * 60)
    
    # Load all metadata
    metadata_list = load_all_metadata(data_dir, max_samples)
    
    if not metadata_list:
        print("Error: No metadata files found!")
        return
    
    # Compute statistics
    print("\nComputing statistics...")
    stats = compute_statistics(metadata_list)
    
    # Load a few sample spectra
    print("\nLoading sample spectra for visualization...")
    sample_ids = [m['_filename'] for m in metadata_list[:10]]
    spectra = load_sample_spectra(data_dir, sample_ids)
    
    print(f"\nGenerating visualizations...")
    
    # Generate all figures
    figures = {
        'isotope_freq': create_isotope_frequency_chart(stats),
        'category_pie': create_category_pie_chart(stats),
        'num_isotopes': create_num_isotopes_histogram(stats),
        'duration_hist': create_duration_histogram(stats),
        'activity_box': create_activity_boxplot(stats),
        'cooccurrence': create_cooccurrence_heatmap(stats),
        'activity_duration': create_activity_vs_duration_scatter(metadata_list),
        'sample_spectra': create_sample_spectrum_plot(spectra, metadata_list),
        'isotope_db': create_isotope_database_summary(),
    }
    
    # Add 3D spectrum if we have data
    if spectra:
        first_id = list(spectra.keys())[0]
        figures['spectrum_3d'] = create_3d_spectrum_surface(spectra[first_id], first_id)
    
    # Create HTML
    print("\nBuilding HTML report...")
    
    html_parts = [
        """
<!DOCTYPE html>
<html>
<head>
    <title>Synthetic Training Data Visualization</title>
    <script src="https://cdn.plot.ly/plotly-latest.min.js"></script>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            margin: 0;
            padding: 20px;
            background: #ecf0f1;
            color: #2c3e50;
        }
        .container {
            max-width: 1400px;
            margin: 0 auto;
        }
        h1 {
            text-align: center;
            color: #2c3e50;
            padding: 20px;
            background: white;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        h2 {
            color: #34495e;
            border-bottom: 2px solid #3498db;
            padding-bottom: 10px;
            margin-top: 40px;
        }
        .chart-container {
            background: white;
            padding: 20px;
            border-radius: 10px;
            margin: 20px 0;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        .row {
            display: flex;
            gap: 20px;
            flex-wrap: wrap;
        }
        .col-6 {
            flex: 1;
            min-width: 400px;
        }
        .col-12 {
            width: 100%;
        }
        .toc {
            background: white;
            padding: 20px;
            border-radius: 10px;
            margin: 20px 0;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        .toc ul {
            list-style: none;
            padding-left: 0;
        }
        .toc li {
            margin: 10px 0;
        }
        .toc a {
            color: #3498db;
            text-decoration: none;
        }
        .toc a:hover {
            text-decoration: underline;
        }
        .info-box {
            background: #e8f6ff;
            border-left: 4px solid #3498db;
            padding: 15px;
            margin: 20px 0;
            border-radius: 0 10px 10px 0;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🔬 Synthetic Gamma Spectra Training Data Analysis</h1>
        """,
        
        create_summary_table(stats),
        
        """
        <div class="toc">
            <h3>📑 Table of Contents</h3>
            <ul>
                <li><a href="#isotope-distribution">1. Isotope Distribution</a></li>
                <li><a href="#sample-complexity">2. Sample Complexity</a></li>
                <li><a href="#temporal-activity">3. Temporal & Activity Analysis</a></li>
                <li><a href="#cooccurrence">4. Isotope Co-occurrence</a></li>
                <li><a href="#sample-spectra">5. Sample Spectra</a></li>
                <li><a href="#database-overview">6. Isotope Database Overview</a></li>
            </ul>
        </div>
        
        <h2 id="isotope-distribution">1. Isotope Distribution</h2>
        <div class="info-box">
            <strong>What this shows:</strong> The frequency of each isotope across all training samples.
            Imbalanced distributions may lead to model bias towards common isotopes.
        </div>
        <div class="row">
            <div class="col-6 chart-container">
        """,
        figures['isotope_freq'].to_html(full_html=False, include_plotlyjs=False),
        """
            </div>
            <div class="col-6 chart-container">
        """,
        figures['category_pie'].to_html(full_html=False, include_plotlyjs=False),
        """
            </div>
        </div>
        
        <h2 id="sample-complexity">2. Sample Complexity</h2>
        <div class="info-box">
            <strong>What this shows:</strong> Distribution of how many source isotopes are present per sample.
            Mix of single and multi-isotope samples helps the model handle real-world complexity.
        </div>
        <div class="chart-container">
        """,
        figures['num_isotopes'].to_html(full_html=False, include_plotlyjs=False),
        """
        </div>
        
        <h2 id="temporal-activity">3. Temporal & Activity Analysis</h2>
        <div class="info-box">
            <strong>What this shows:</strong> Distribution of measurement durations and source activities.
            Varied durations simulate different counting scenarios.
        </div>
        <div class="row">
            <div class="col-6 chart-container">
        """,
        figures['duration_hist'].to_html(full_html=False, include_plotlyjs=False),
        """
            </div>
            <div class="col-6 chart-container">
        """,
        figures['activity_duration'].to_html(full_html=False, include_plotlyjs=False),
        """
            </div>
        </div>
        <div class="chart-container">
        """,
        figures['activity_box'].to_html(full_html=False, include_plotlyjs=False),
        """
        </div>
        
        <h2 id="cooccurrence">4. Isotope Co-occurrence</h2>
        <div class="info-box">
            <strong>What this shows:</strong> Which isotopes frequently appear together in training samples.
            This helps understand potential confusion pairs and realistic combinations.
        </div>
        <div class="chart-container">
        """,
        figures['cooccurrence'].to_html(full_html=False, include_plotlyjs=False),
        """
        </div>
        
        <h2 id="sample-spectra">5. Sample Spectra Visualization</h2>
        <div class="info-box">
            <strong>What this shows:</strong> Actual spectrum shapes from the training data.
            Each peak corresponds to gamma emission lines from the source isotopes.
        </div>
        <div class="chart-container">
        """,
        figures['sample_spectra'].to_html(full_html=False, include_plotlyjs=False),
        """
        </div>
        """
    ]
    
    # Add 3D spectrum if available
    if 'spectrum_3d' in figures:
        html_parts.append("""
        <div class="chart-container">
            <h3>3D Time-Energy-Counts View</h3>
        """)
        html_parts.append(figures['spectrum_3d'].to_html(full_html=False, include_plotlyjs=False))
        html_parts.append("</div>")
    
    html_parts.append("""
        <h2 id="database-overview">6. Isotope Database Overview</h2>
        <div class="info-box">
            <strong>What this shows:</strong> The complete isotope database structure organized by category.
            Click to explore the hierarchy.
        </div>
        <div class="chart-container">
        """)
    html_parts.append(figures['isotope_db'].to_html(full_html=False, include_plotlyjs=False))
    html_parts.append("""
        </div>
        
        <footer style="text-align: center; padding: 40px; color: #7f8c8d;">
            <p>Generated by ML for Isotope Identification Training Data Analyzer</p>
        </footer>
    </div>
</body>
</html>
    """)
    
    # Write HTML file
    html_content = ''.join(html_parts)
    
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(html_content)
    
    print(f"\n✅ Report generated successfully!")
    print(f"   Output: {output_file.absolute()}")
    print(f"\nOpen in your browser to view the interactive visualizations.")


def main():
    parser = argparse.ArgumentParser(
        description="Generate interactive HTML visualization of training data"
    )
    parser.add_argument(
        '--data-dir',
        type=str,
        default='data/synthetic/spectra',
        help='Directory containing spectrum .json and .npy files'
    )
    parser.add_argument(
        '--output',
        type=str,
        default='training_data_report.html',
        help='Output HTML file name'
    )
    parser.add_argument(
        '--max-samples',
        type=int,
        default=None,
        help='Maximum number of samples to analyze (for faster generation)'
    )
    
    args = parser.parse_args()
    
    data_dir = Path(args.data_dir)
    output_file = Path(args.output)
    
    if not data_dir.exists():
        print(f"Error: Data directory not found: {data_dir}")
        sys.exit(1)
    
    generate_html_report(data_dir, output_file, args.max_samples)


if __name__ == "__main__":
    main()
