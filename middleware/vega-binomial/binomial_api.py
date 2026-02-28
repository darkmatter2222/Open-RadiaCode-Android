#!/usr/bin/env python3
"""
Vega Bulk Binomial Isotope Detection API

FastAPI server that loads ALL trained binary isotope detection models onto GPU
at startup and performs bulk inference on incoming spectra.

Key Features:
- Loads all per-isotope binary models into GPU memory on startup
- Accepts raw spectrum + device calibration coefficients
- Translates device spectrum to training energy axis using flux-conserving rebinning
- Runs inference through all loaded models in parallel
- Returns comprehensive results with timing information for every step

Endpoints:
    POST /detect             - Bulk binomial detection from spectrum + calibration
    POST /detect/batch       - Batch detection for multiple spectra
    GET  /health             - Health check
    GET  /info               - Service info and loaded models
    GET  /isotopes           - List all isotopes with model status
"""

import os
import sys
import json
import logging
import argparse
import time
import uuid
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import numpy as np
import torch
import torch.nn as nn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s.%(msecs)03d | %(levelname)-8s | %(name)s | %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("vega.binomial")

# ==============================================================================
# Configuration
# ==============================================================================

MODELS_DIR = Path(os.getenv("VEGA_BINOMIAL_MODELS_DIR", "/app/models"))
DEVICE = os.getenv("VEGA_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
DEFAULT_THRESHOLD = float(os.getenv("VEGA_DEFAULT_THRESHOLD", "0.5"))
MIN_TUNED_THRESHOLD = float(os.getenv("VEGA_MIN_TUNED_THRESHOLD", "0.1"))
MODEL_VERSION = os.getenv("VEGA_MODEL_VERSION", "v5-micro-2000per")
SERVICE_VERSION = "1.0.0"

# Input dimensions expected by training
NUM_CHANNELS = 1023
NUM_TIME_INTERVALS = 300  # 5 minutes at 1-second intervals

# Training calibration constants (must match training pipeline)
TRAINING_E_MIN = 20.0      # keV
TRAINING_E_MAX = 3000.0    # keV
TRAINING_RAW_CHANNELS = 1024
TRAINING_USABLE_CHANNELS = 1023
TRAINING_KEV_PER_CHANNEL = (TRAINING_E_MAX - TRAINING_E_MIN) / TRAINING_RAW_CHANNELS

# ==============================================================================
# Model Architecture (must match train_binary_isotope.py exactly)
# ==============================================================================

@dataclass
class Binary2DConfig:
    num_channels: int = 1023
    num_time_intervals: int = 300
    conv1_channels: int = 4
    conv2_channels: int = 8
    conv3_channels: int = 16
    kernel_size: Tuple[int, int] = (3, 3)
    pool1: Tuple[int, int] = (5, 5)
    pool2: Tuple[int, int] = (5, 5)
    pool3: Tuple[int, int] = (4, 8)
    # Two-layer classification head (must match training)
    fc1_dim: int = 64
    fc2_dim: int = 32
    # Legacy field for backward compat with old checkpoints
    fc_dim: int = 16
    dropout: float = 0.2


class TinyBinary2DCNN(nn.Module):
    """Tiny binary classifier matching train_binary_isotope.py architecture.
    
    Supports both legacy (single fc_dim) and new (fc1_dim, fc2_dim) head configs.
    """

    def __init__(self, cfg: Binary2DConfig):
        super().__init__()
        self.cfg = cfg

        pad = (cfg.kernel_size[0] // 2, cfg.kernel_size[1] // 2)

        self.conv1 = nn.Conv2d(1, cfg.conv1_channels, cfg.kernel_size, padding=pad)
        self.bn1 = nn.BatchNorm2d(cfg.conv1_channels)
        self.pool1 = nn.MaxPool2d(cfg.pool1)

        self.conv2 = nn.Conv2d(cfg.conv1_channels, cfg.conv2_channels, cfg.kernel_size, padding=pad)
        self.bn2 = nn.BatchNorm2d(cfg.conv2_channels)
        self.pool2 = nn.MaxPool2d(cfg.pool2)

        self.conv3 = nn.Conv2d(cfg.conv2_channels, cfg.conv3_channels, cfg.kernel_size, padding=pad)
        self.bn3 = nn.BatchNorm2d(cfg.conv3_channels)
        self.pool3 = nn.MaxPool2d(cfg.pool3)

        self.act = nn.ReLU(inplace=True)
        self.dropout2d = nn.Dropout2d(cfg.dropout)

        self.gap = nn.AdaptiveAvgPool2d(1)

        # Detect whether this is a new (two-layer) or legacy (single-layer) head
        self._use_two_layer_head = hasattr(cfg, 'fc1_dim') and cfg.fc1_dim > 0

        if self._use_two_layer_head:
            self.fc1 = nn.Linear(cfg.conv3_channels, cfg.fc1_dim)
            self.fc1_bn = nn.BatchNorm1d(cfg.fc1_dim)
            self.fc2 = nn.Linear(cfg.fc1_dim, cfg.fc2_dim)
            self.fc2_bn = nn.BatchNorm1d(cfg.fc2_dim)
            self.fc_drop = nn.Dropout(cfg.dropout)
            self.out = nn.Linear(cfg.fc2_dim, 1)
        else:
            # Legacy single-layer head
            self.fc = nn.Linear(cfg.conv3_channels, cfg.fc_dim)
            self.fc_bn = nn.BatchNorm1d(cfg.fc_dim)
            self.fc_drop = nn.Dropout(cfg.dropout)
            self.out = nn.Linear(cfg.fc_dim, 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # (B, T, C) -> (B, 1, T, C)
        if x.dim() == 3:
            x = x.unsqueeze(1)

        x = self.pool1(self.act(self.bn1(self.conv1(x))))
        x = self.dropout2d(x)

        x = self.pool2(self.act(self.bn2(self.conv2(x))))
        x = self.dropout2d(x)

        x = self.pool3(self.act(self.bn3(self.conv3(x))))
        x = self.dropout2d(x)

        x = self.gap(x).view(x.size(0), -1)

        if self._use_two_layer_head:
            x = self.fc_drop(self.act(self.fc1_bn(self.fc1(x))))
            x = self.fc_drop(self.act(self.fc2_bn(self.fc2(x))))
        else:
            x = self.fc_drop(self.act(self.fc_bn(self.fc(x))))

        logit = self.out(x).squeeze(-1)
        return logit


# ==============================================================================
# Calibration Translation (Flux-Conserving Rebinning)
# ==============================================================================

# Pre-compute training edges once at module load (immutable)
_TRAINING_EDGES_CACHE: Optional[np.ndarray] = None


def get_training_energy_edges() -> np.ndarray:
    """Get 1024 bin edges for training energy axis (1023 bins). Cached."""
    global _TRAINING_EDGES_CACHE
    if _TRAINING_EDGES_CACHE is None:
        ch = np.arange(TRAINING_USABLE_CHANNELS + 1) + 1  # Skip raw channel 0
        _TRAINING_EDGES_CACHE = TRAINING_E_MIN + ch * TRAINING_KEV_PER_CHANNEL
    return _TRAINING_EDGES_CACHE


def get_device_energy_edges(a0: float, a1: float, a2: float, n_channels: int = 1023) -> np.ndarray:
    """Get bin edges for device energy axis with quadratic calibration.
    E(ch) = a0 + a1*ch + a2*ch^2
    """
    ch = np.arange(n_channels + 1, dtype=np.float64)
    return a0 + a1 * ch + a2 * ch * ch


def flux_conserving_rebin_vectorized(
    src_counts: np.ndarray,
    src_edges: np.ndarray,
    dst_edges: np.ndarray
) -> np.ndarray:
    """
    Vectorized flux-conserving rebinning using precomputed overlap matrix.
    Much faster than the O(n*m) nested loop version.
    """
    n_src = len(src_counts)
    n_dst = len(dst_edges) - 1
    
    # Source bin boundaries
    src_lo = src_edges[:-1]  # (n_src,)
    src_hi = src_edges[1:]   # (n_src,)
    src_width = src_hi - src_lo  # (n_src,)
    
    # Destination bin boundaries  
    dst_lo = dst_edges[:-1]  # (n_dst,)
    dst_hi = dst_edges[1:]   # (n_dst,)
    
    # Compute overlap matrix: overlap[i_dst, i_src] = fraction of src bin i_src in dst bin i_dst
    # Broadcast to (n_dst, n_src)
    overlap_lo = np.maximum(src_lo[np.newaxis, :], dst_lo[:, np.newaxis])  # (n_dst, n_src)
    overlap_hi = np.minimum(src_hi[np.newaxis, :], dst_hi[:, np.newaxis])  # (n_dst, n_src)
    overlap_width = np.maximum(0.0, overlap_hi - overlap_lo)  # (n_dst, n_src)
    
    # Fraction of each source bin that falls into each destination bin
    with np.errstate(divide='ignore', invalid='ignore'):
        fractions = overlap_width / src_width[np.newaxis, :]  # (n_dst, n_src)
        fractions = np.nan_to_num(fractions, nan=0.0, posinf=0.0, neginf=0.0)
    
    # Matrix multiply: dst_counts = fractions @ src_counts
    dst_counts = fractions @ src_counts.astype(np.float64)
    
    return dst_counts.astype(np.float32)


def translate_spectrum_flux_conserving(
    device_counts: np.ndarray,
    a0: float,
    a1: float,
    a2: float
) -> np.ndarray:
    """Translate device spectrum to training energy axis using flux-conserving rebinning."""
    device_edges = get_device_energy_edges(a0, a1, a2, len(device_counts))
    training_edges = get_training_energy_edges()
    return flux_conserving_rebin_vectorized(device_counts, device_edges, training_edges)


def translate_spectrogram_flux_conserving(
    spectrogram: np.ndarray,
    a0: float,
    a1: float,
    a2: float
) -> np.ndarray:
    """Translate 2D spectrogram (time x channels) using flux-conserving rebinning.
    
    Optimized: computes the rebin matrix once and applies to all rows via matmul.
    """
    n_rows = spectrogram.shape[0]
    n_src = spectrogram.shape[1]
    n_dst = TRAINING_USABLE_CHANNELS

    device_edges = get_device_energy_edges(a0, a1, a2, n_src)
    training_edges = get_training_energy_edges()

    # Compute rebin matrix once: (n_dst, n_src)
    src_lo = device_edges[:-1]
    src_hi = device_edges[1:]
    src_width = src_hi - src_lo
    
    dst_lo = training_edges[:-1]
    dst_hi = training_edges[1:]
    
    overlap_lo = np.maximum(src_lo[np.newaxis, :], dst_lo[:, np.newaxis])
    overlap_hi = np.minimum(src_hi[np.newaxis, :], dst_hi[:, np.newaxis])
    overlap_width = np.maximum(0.0, overlap_hi - overlap_lo)
    
    with np.errstate(divide='ignore', invalid='ignore'):
        rebin_matrix = overlap_width / src_width[np.newaxis, :]
        rebin_matrix = np.nan_to_num(rebin_matrix, nan=0.0, posinf=0.0, neginf=0.0)
    
    # Apply to all rows at once: (n_rows, n_dst) = (n_rows, n_src) @ (n_src, n_dst).T
    # Or equivalently: (n_dst, n_src) @ (n_src, n_rows) then transpose
    result = (rebin_matrix @ spectrogram.T).T  # (n_rows, n_dst)
    
    return result.astype(np.float32)


# ==============================================================================
# Model Loading and Inference Engine
# ==============================================================================

@dataclass
class LoadedModel:
    """Holds a loaded model with metadata."""
    isotope: str
    model: nn.Module
    config: Binary2DConfig
    threshold: float
    tuned_threshold: Optional[float]
    meta: Dict[str, Any]
    params: int


class BinomialInferenceEngine:
    """Manages loading all binary models and running bulk inference."""

    def __init__(self, models_dir: Path, device: str = "cuda"):
        self.models_dir = models_dir
        self.device = torch.device(device if torch.cuda.is_available() else "cpu")
        self.models: Dict[str, LoadedModel] = {}
        self.load_errors: Dict[str, str] = {}
        self._training_edges = get_training_energy_edges()

    def load_all_models(self) -> Tuple[int, int]:
        """Load all binary models from models_dir. Returns (loaded, failed) counts."""
        if not self.models_dir.exists():
            logger.warning(f"Models directory does not exist: {self.models_dir}")
            return 0, 0

        ckpt_files = sorted(self.models_dir.glob("binary_*.pt"))
        logger.info(f"Found {len(ckpt_files)} checkpoint files in {self.models_dir}")

        loaded = 0
        failed = 0

        for ckpt_path in ckpt_files:
            isotope = self._parse_isotope_from_ckpt(ckpt_path)
            meta_path = ckpt_path.with_name(ckpt_path.stem + "_meta.json")

            try:
                start = time.perf_counter()
                loaded_model = self._load_single_model(ckpt_path, meta_path, isotope)
                elapsed_ms = (time.perf_counter() - start) * 1000

                self.models[isotope] = loaded_model
                loaded += 1
                logger.info(
                    f"Loaded model: {isotope} | "
                    f"params={loaded_model.params:,} | "
                    f"threshold={loaded_model.threshold:.4f} | "
                    f"tuned={loaded_model.tuned_threshold} | "
                    f"time={elapsed_ms:.1f}ms"
                )
            except Exception as e:
                failed += 1
                self.load_errors[isotope] = str(e)
                logger.warning(f"Failed to load model for {isotope}: {e}")

        logger.info(f"Model loading complete: {loaded} loaded, {failed} failed")
        return loaded, failed

    def _parse_isotope_from_ckpt(self, ckpt_path: Path) -> str:
        """Parse isotope name from checkpoint filename."""
        name = ckpt_path.stem
        if name.startswith("binary_"):
            name = name[len("binary_"):]
        # Reverse training replacement: '_' -> '-' (e.g., U_238 -> U-238)
        return name.replace("_", "-")

    def _load_single_model(
        self,
        ckpt_path: Path,
        meta_path: Optional[Path],
        isotope: str
    ) -> LoadedModel:
        """Load a single binary model checkpoint."""
        ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)

        cfg_dict = ckpt.get("config")
        if not isinstance(cfg_dict, dict):
            raise ValueError("Checkpoint missing config dict")

        # Handle tuple conversion for kernel_size, pool1, etc.
        for key in ["kernel_size", "pool1", "pool2", "pool3"]:
            if key in cfg_dict and isinstance(cfg_dict[key], list):
                cfg_dict[key] = tuple(cfg_dict[key])

        cfg = Binary2DConfig(**cfg_dict)
        model = TinyBinary2DCNN(cfg)

        state_dict = ckpt.get("state_dict")
        if not isinstance(state_dict, dict):
            raise ValueError("Checkpoint missing state_dict")

        model.load_state_dict(state_dict, strict=True)
        model.eval()
        model.to(self.device)

        # Load metadata
        meta = {}
        threshold = DEFAULT_THRESHOLD
        tuned_threshold = None

        if meta_path and meta_path.exists():
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)

                # Prefer tuned threshold, but protect against pathological values.
                tuned_metrics = meta.get("best_tuned_metrics")
                constraint_met = True
                if isinstance(tuned_metrics, dict) and "constraint_met" in tuned_metrics:
                    constraint_met = bool(tuned_metrics.get("constraint_met"))

                if "best_tuned_threshold" in meta:
                    tuned_threshold = float(meta["best_tuned_threshold"])
                    if not np.isfinite(tuned_threshold):
                        logger.warning("Invalid tuned threshold for %s; ignoring", isotope)
                        tuned_threshold = None
                    else:
                        tuned_threshold = float(max(MIN_TUNED_THRESHOLD, min(0.99, tuned_threshold)))

                    # If training couldn't satisfy its precision constraint, don't trust the tuned threshold.
                    if tuned_threshold is not None and not constraint_met:
                        logger.warning(
                            "Tuned threshold constraint not met for %s; using default threshold=%.3f (tuned=%.3f)",
                            isotope,
                            float(DEFAULT_THRESHOLD),
                            float(tuned_threshold),
                        )
                        threshold = float(DEFAULT_THRESHOLD)
                    elif tuned_threshold is not None:
                        threshold = tuned_threshold
                elif "threshold" in meta:
                    threshold = float(meta["threshold"])
            except Exception as e:
                logger.warning(f"Failed to load meta for {isotope}: {e}")

        # Final safety clamp (covers both tuned and non-tuned thresholds).
        try:
            threshold = float(threshold)
        except Exception:
            threshold = float(DEFAULT_THRESHOLD)
        if not np.isfinite(threshold):
            threshold = float(DEFAULT_THRESHOLD)
        threshold = float(max(MIN_TUNED_THRESHOLD, min(0.99, threshold)))

        params = sum(p.numel() for p in model.parameters())

        return LoadedModel(
            isotope=isotope,
            model=model,
            config=cfg,
            threshold=threshold,
            tuned_threshold=tuned_threshold,
            meta=meta,
            params=params
        )

    def preprocess_spectrum(
        self,
        spectrum: np.ndarray,
        calibration: Dict[str, float],
        target_time_intervals: int = NUM_TIME_INTERVALS
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        """
        Preprocess spectrum: translate calibration and normalize.
        Returns (processed_spectrum, timing_info).
        """
        timing = {}

        # Get calibration coefficients
        a0 = calibration.get("a0", 0.0)
        a1 = calibration.get("a1", 1.0)
        a2 = calibration.get("a2", 0.0)

        # Ensure 2D
        if spectrum.ndim == 1:
            spectrum = spectrum.reshape(1, -1)

        original_shape = spectrum.shape
        total_counts_before = float(np.sum(spectrum))

        # Translate to training calibration
        start = time.perf_counter()
        translated = translate_spectrogram_flux_conserving(spectrum, a0, a1, a2)
        timing["translate_ms"] = (time.perf_counter() - start) * 1000
        total_counts_after = float(np.sum(translated))

        # Pad or truncate time dimension
        start = time.perf_counter()
        current_time = translated.shape[0]
        if current_time != target_time_intervals:
            if current_time > target_time_intervals:
                # Truncate: evenly spaced indices
                indices = np.linspace(0, current_time - 1, target_time_intervals, dtype=int)
                translated = translated[indices, :]
            else:
                # Pad with zeros
                padded = np.zeros((target_time_intervals, translated.shape[1]), dtype=translated.dtype)
                padded[:current_time, :] = translated
                translated = padded
        timing["pad_truncate_ms"] = (time.perf_counter() - start) * 1000

        # Max normalize
        start = time.perf_counter()
        max_val = float(np.max(translated))
        if max_val > 0:
            translated = translated / max_val
        timing["normalize_ms"] = (time.perf_counter() - start) * 1000

        timing["original_shape"] = list(original_shape)
        timing["final_shape"] = list(translated.shape)
        timing["total_counts_before"] = total_counts_before
        timing["total_counts_after"] = total_counts_after
        timing["max_value_for_norm"] = max_val
        timing["calibration"] = {"a0": a0, "a1": a1, "a2": a2}

        return translated.astype(np.float32), timing

    @torch.inference_mode()
    def run_inference(
        self,
        spectrum: np.ndarray,
        isotopes: Optional[List[str]] = None
    ) -> Tuple[Dict[str, Dict[str, Any]], Dict[str, Any]]:
        """
        Run inference through all (or specified) models.
        Returns (results_per_isotope, timing_info).
        """
        timing = {"inference_per_isotope_ms": {}}
        results = {}

        # Select models to run
        if isotopes is None:
            models_to_run = list(self.models.items())
        else:
            models_to_run = [(iso, self.models[iso]) for iso in isotopes if iso in self.models]
            for iso in isotopes:
                if iso not in self.models:
                    results[iso] = {
                        "error": "Model not loaded",
                        "probability": None,
                        "detected": None
                    }

        # Prepare tensor once
        start = time.perf_counter()
        x = torch.tensor(spectrum, dtype=torch.float32, device=self.device)
        x = x.unsqueeze(0)  # (1, T, C)
        timing["tensor_prep_ms"] = (time.perf_counter() - start) * 1000

        # Run all models
        total_inference_start = time.perf_counter()

        for isotope, loaded_model in models_to_run:
            model_start = time.perf_counter()

            logit = loaded_model.model(x)
            prob = torch.sigmoid(logit).cpu().item()

            model_elapsed = (time.perf_counter() - model_start) * 1000
            timing["inference_per_isotope_ms"][isotope] = model_elapsed

            detected = prob >= loaded_model.threshold

            results[isotope] = {
                "probability": round(prob, 6),
                "threshold": loaded_model.threshold,
                "tuned_threshold": loaded_model.tuned_threshold,
                "detected": detected,
                "inference_ms": round(model_elapsed, 3)
            }

        timing["total_inference_ms"] = (time.perf_counter() - total_inference_start) * 1000
        timing["models_run"] = len(models_to_run)

        return results, timing

    def get_model_info(self) -> Dict[str, Any]:
        """Get information about loaded models."""
        return {
            "loaded_count": len(self.models),
            "failed_count": len(self.load_errors),
            "device": str(self.device),
            "models": {
                iso: {
                    "threshold": m.threshold,
                    "tuned_threshold": m.tuned_threshold,
                    "params": m.params
                }
                for iso, m in self.models.items()
            },
            "load_errors": self.load_errors
        }


# ==============================================================================
# Pydantic Models
# ==============================================================================

class CalibrationData(BaseModel):
    """Device calibration coefficients: E(ch) = a0 + a1*ch + a2*ch^2"""
    a0: float = Field(default=0.0, description="Constant term (keV)")
    a1: float = Field(default=2.36, description="Linear term (keV/channel)")
    a2: float = Field(default=0.0, description="Quadratic term (keV/channel^2)")


class DetectionRequest(BaseModel):
    """Request for bulk binomial detection."""
    spectrum: List[List[float]] = Field(
        ...,
        description="2D spectrum array (time x channels), typically 300x1023"
    )
    calibration: CalibrationData = Field(
        default_factory=lambda: CalibrationData(a0=3.5093544, a1=2.3624456, a2=4.0645464e-4),
        description="Device calibration coefficients"
    )
    isotopes: Optional[List[str]] = Field(
        default=None,
        description="List of specific isotopes to check (None = all)"
    )
    request_id: Optional[str] = Field(
        default=None,
        description="Client-provided request ID for correlation"
    )


class Detection1DRequest(BaseModel):
    """Request for 1D spectrum detection (will be expanded to 2D)."""
    spectrum: List[float] = Field(
        ...,
        description="1D spectrum array (1023 channels)"
    )
    calibration: CalibrationData = Field(
        default_factory=lambda: CalibrationData(a0=3.5093544, a1=2.3624456, a2=4.0645464e-4),
        description="Device calibration coefficients"
    )
    isotopes: Optional[List[str]] = Field(
        default=None,
        description="List of specific isotopes to check (None = all)"
    )
    request_id: Optional[str] = Field(
        default=None,
        description="Client-provided request ID for correlation"
    )


class BatchDetectionRequest(BaseModel):
    """Request for batch detection of multiple spectra."""
    spectra: List[List[List[float]]] = Field(
        ...,
        description="List of 2D spectra"
    )
    calibrations: Optional[List[CalibrationData]] = Field(
        default=None,
        description="Per-spectrum calibrations (or single calibration for all)"
    )
    isotopes: Optional[List[str]] = Field(
        default=None,
        description="List of specific isotopes to check (None = all)"
    )
    request_id: Optional[str] = Field(
        default=None,
        description="Client-provided request ID for correlation"
    )


class IsotopeResult(BaseModel):
    """Result for a single isotope."""
    probability: Optional[float]
    threshold: Optional[float]
    tuned_threshold: Optional[float]
    detected: Optional[bool]
    inference_ms: Optional[float]
    error: Optional[str] = None


class DetectionResponse(BaseModel):
    """Response from bulk binomial detection."""
    request_id: str
    timestamp_utc: str
    total_elapsed_ms: float
    preprocessing: Dict[str, Any]
    inference: Dict[str, Any]
    results: Dict[str, IsotopeResult]
    detected_isotopes: List[str]
    summary: Dict[str, Any]


# ==============================================================================
# FastAPI Application
# ==============================================================================

engine: Optional[BinomialInferenceEngine] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load models on startup."""
    global engine
    logger.info("=" * 60)
    logger.info("Vega Bulk Binomial Detection API - Starting")
    logger.info("=" * 60)
    logger.info(f"Models directory: {MODELS_DIR}")
    logger.info(f"Device: {DEVICE}")

    engine = BinomialInferenceEngine(MODELS_DIR, DEVICE)
    loaded, failed = engine.load_all_models()

    logger.info(f"Startup complete: {loaded} models loaded, {failed} failed")
    logger.info("=" * 60)

    yield

    logger.info("Shutting down...")


app = FastAPI(
    title="Vega Bulk Binomial Isotope Detection API",
    description=(
        "Loads ALL trained per-isotope binary detection models onto GPU "
        "and performs bulk inference on incoming spectra with calibration translation."
    ),
    version=SERVICE_VERSION,
    lifespan=lifespan
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==============================================================================
# Endpoints
# ==============================================================================

@app.get("/health")
async def health_check():
    """Health check endpoint."""
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    return {
        "status": "healthy",
        "models_loaded": len(engine.models),
        "models_failed": len(engine.load_errors),
        "device": str(engine.device),
        "cuda_available": torch.cuda.is_available()
    }


@app.get("/info")
async def get_info():
    """Get detailed service information."""
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    return {
        "service": "vega-binomial",
        "version": SERVICE_VERSION,
        "model_version": MODEL_VERSION,
        "models_dir": str(MODELS_DIR),
        "device": str(engine.device),
        "cuda_available": torch.cuda.is_available(),
        "cuda_device_name": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "input_shape": {"time_intervals": NUM_TIME_INTERVALS, "channels": NUM_CHANNELS},
        "training_calibration": {
            "energy_min_keV": TRAINING_E_MIN,
            "energy_max_keV": TRAINING_E_MAX,
            "channels": TRAINING_USABLE_CHANNELS,
            "keV_per_channel": TRAINING_KEV_PER_CHANNEL
        },
        **engine.get_model_info()
    }


@app.get("/isotopes")
async def list_isotopes():
    """List all isotopes with their model status."""
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    loaded = []
    for iso, m in sorted(engine.models.items()):
        loaded.append({
            "isotope": iso,
            "status": "loaded",
            "threshold": m.threshold,
            "tuned_threshold": m.tuned_threshold,
            "params": m.params
        })

    failed = []
    for iso, err in sorted(engine.load_errors.items()):
        failed.append({
            "isotope": iso,
            "status": "failed",
            "error": err
        })

    return {
        "loaded_count": len(loaded),
        "failed_count": len(failed),
        "loaded": loaded,
        "failed": failed
    }


@app.post("/detect", response_model=DetectionResponse)
async def detect_isotopes(request: DetectionRequest):
    """
    Bulk binomial isotope detection.

    Accepts a 2D spectrum (time x channels) with device calibration coefficients.
    Translates the spectrum to the training energy axis using flux-conserving rebinning,
    then runs inference through all loaded models.

    Returns detailed results including:
    - Per-isotope probabilities and detection decisions
    - Timing information for every processing step
    - List of detected isotopes
    """
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    request_id = request.request_id or str(uuid.uuid4())
    timestamp = datetime.now(timezone.utc).isoformat()
    total_start = time.perf_counter()

    logger.info(f"[{request_id}] Detection request received | isotopes={request.isotopes}")

    # Convert to numpy
    try:
        spectrum = np.array(request.spectrum, dtype=np.float32)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid spectrum format: {e}")

    logger.info(f"[{request_id}] Input shape: {spectrum.shape}")

    # Validate shape
    if spectrum.ndim == 1:
        if len(spectrum) != NUM_CHANNELS:
            raise HTTPException(
                status_code=400,
                detail=f"1D spectrum must have {NUM_CHANNELS} channels, got {len(spectrum)}"
            )
        spectrum = spectrum.reshape(1, -1)
    elif spectrum.ndim == 2:
        if spectrum.shape[1] != NUM_CHANNELS:
            raise HTTPException(
                status_code=400,
                detail=f"Spectrum must have {NUM_CHANNELS} channels, got {spectrum.shape[1]}"
            )
    else:
        raise HTTPException(status_code=400, detail=f"Spectrum must be 1D or 2D, got {spectrum.ndim}D")

    # Preprocess
    calibration = {
        "a0": request.calibration.a0,
        "a1": request.calibration.a1,
        "a2": request.calibration.a2
    }
    preprocessed, preprocess_timing = engine.preprocess_spectrum(spectrum, calibration)

    logger.info(
        f"[{request_id}] Preprocessing done | "
        f"translate={preprocess_timing['translate_ms']:.1f}ms | "
        f"normalize={preprocess_timing['normalize_ms']:.1f}ms"
    )

    # Run inference
    results, inference_timing = engine.run_inference(preprocessed, request.isotopes)

    logger.info(
        f"[{request_id}] Inference done | "
        f"models={inference_timing['models_run']} | "
        f"time={inference_timing['total_inference_ms']:.1f}ms"
    )

    # Build response
    detected_isotopes = [
        iso for iso, r in results.items()
        if r.get("detected") is True
    ]

    total_elapsed = (time.perf_counter() - total_start) * 1000

    logger.info(
        f"[{request_id}] Complete | "
        f"detected={len(detected_isotopes)} | "
        f"total={total_elapsed:.1f}ms | "
        f"isotopes={detected_isotopes}"
    )

    return DetectionResponse(
        request_id=request_id,
        timestamp_utc=timestamp,
        total_elapsed_ms=round(total_elapsed, 3),
        preprocessing=preprocess_timing,
        inference=inference_timing,
        results={k: IsotopeResult(**v) for k, v in results.items()},
        detected_isotopes=detected_isotopes,
        summary={
            "total_models": len(engine.models),
            "models_run": inference_timing["models_run"],
            "detected_count": len(detected_isotopes)
        }
    )


@app.post("/detect/1d")
async def detect_isotopes_1d(request: Detection1DRequest):
    """
    Detection from 1D spectrum (single time slice).
    Spectrum will be expanded to 2D by replicating across time dimension.
    """
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    # Convert 1D to 2D by replication
    spectrum_2d = [request.spectrum] * NUM_TIME_INTERVALS

    # Create 2D request
    request_2d = DetectionRequest(
        spectrum=spectrum_2d,
        calibration=request.calibration,
        isotopes=request.isotopes,
        request_id=request.request_id
    )

    return await detect_isotopes(request_2d)


@app.post("/detect/batch")
async def detect_batch(request: BatchDetectionRequest):
    """
    Batch detection for multiple spectra.
    Returns results for each spectrum in order.
    """
    if engine is None:
        raise HTTPException(status_code=503, detail="Engine not initialized")

    request_id = request.request_id or str(uuid.uuid4())
    timestamp = datetime.now(timezone.utc).isoformat()
    total_start = time.perf_counter()

    logger.info(f"[{request_id}] Batch detection request | spectra={len(request.spectra)}")

    results_list = []

    for i, spectrum_data in enumerate(request.spectra):
        # Get calibration for this spectrum
        if request.calibrations and len(request.calibrations) > i:
            calibration = request.calibrations[i]
        elif request.calibrations and len(request.calibrations) == 1:
            calibration = request.calibrations[0]
        else:
            calibration = CalibrationData(a0=3.5093544, a1=2.3624456, a2=4.0645464e-4)

        single_request = DetectionRequest(
            spectrum=spectrum_data,
            calibration=calibration,
            isotopes=request.isotopes,
            request_id=f"{request_id}-{i}"
        )

        try:
            result = await detect_isotopes(single_request)
            results_list.append({"index": i, "status": "success", "result": result})
        except HTTPException as e:
            results_list.append({"index": i, "status": "error", "error": e.detail})
        except Exception as e:
            results_list.append({"index": i, "status": "error", "error": str(e)})

    total_elapsed = (time.perf_counter() - total_start) * 1000

    logger.info(f"[{request_id}] Batch complete | elapsed={total_elapsed:.1f}ms")

    return {
        "request_id": request_id,
        "timestamp_utc": timestamp,
        "total_elapsed_ms": round(total_elapsed, 3),
        "spectra_count": len(request.spectra),
        "results": results_list
    }


# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Vega Bulk Binomial Detection API")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to")
    parser.add_argument("--port", type=int, default=8021, help="Port to bind to")
    parser.add_argument("--models-dir", type=str, help="Override models directory")
    args = parser.parse_args()

    if args.models_dir:
        MODELS_DIR = Path(args.models_dir)

    uvicorn.run(app, host=args.host, port=args.port)
