#!/usr/bin/env python3
"""
Vega 2D Isotope Identification REST API

FastAPI server for gamma spectrum isotope identification using the trained Vega 2D model.

Endpoints:
    POST /identify         - Identify isotopes from 2D spectrum data
    POST /identify/1d      - Identify isotopes from 1D spectrum (legacy support)
    POST /identify/b64     - Identify isotopes from base64-encoded spectrum
    POST /identify/batch   - Batch identification for multiple spectra
    GET  /health           - Health check
    GET  /info             - Model info and supported isotopes
    GET  /isotopes         - List all supported isotopes

The 2D model accepts:
    - 2D spectrum: shape (60, 1023) - 60 time intervals × 1023 energy channels
    - 1D spectrum: shape (1023,) - will be expanded to 2D automatically
    
Energy range: 20 keV to 3000 keV across 1023 channels
Time: 60 one-second intervals (1 minute total measurement)
"""

import os
import sys
import base64
import io
import json
import logging
import argparse
from typing import Optional, List, Dict, Any, Tuple
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

import numpy as np
import torch
import torch.nn as nn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn
import uuid

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ==============================================================================
# Configuration
# ==============================================================================

MODEL_PATH = os.getenv("VEGA_ISOTOPE_MODEL", "models/vega_2d_final.pt")
DEVICE = os.getenv("VEGA_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
DEFAULT_THRESHOLD = float(os.getenv("VEGA_DEFAULT_THRESHOLD", "0.5"))
MODEL_NAME = os.getenv("VEGA_MODEL_NAME", "vega-2d")
MODEL_VERSION = os.getenv("VEGA_MODEL_VERSION", "2.0.0")
LABEL_INDEX_VERSION = os.getenv("VEGA_LABEL_INDEX_VERSION", "portable_2d_sorted")

# Input/semantics policies
CLAMP_NON_NEGATIVE = os.getenv("VEGA_CLAMP_NON_NEGATIVE", "true").lower() in ("1", "true", "yes", "y")
CHANNEL_POLICY = os.getenv("VEGA_CHANNEL_POLICY", "drop_last_if_1024")
ENERGY_ALIGNMENT_POLICY = os.getenv(
    "VEGA_ENERGY_ALIGNMENT_POLICY",
    "assume_aligned_no_resampling; send raw counts + calibration metadata for server-side rebin if needed"
)
NUM_CHANNELS = 1023       # Fixed by model architecture (energy channels)
NUM_TIME_INTERVALS = 60   # Fixed by model architecture (time dimension)
NUM_ISOTOPES = 82         # Fixed by model architecture

# ==============================================================================
# Isotope Database (Embedded)
# ==============================================================================

# Canonical list (source-of-truth) for the deployed checkpoint label ordering.
# This ordering MUST match the checkpoint output index order (0..81).
ISOTOPE_NAMES = [
    "Ac-228",
    "Ag-110m",
    "Am-241",
    "Ba-133",
    "Ba-140",
    "Be-7",
    "Bi-207",
    "Bi-211",
    "Bi-212",
    "Bi-214",
    "Br-82",
    "C-14",
    "Ca-45",
    "Ca-47",
    "Cd-109",
    "Ce-141",
    "Ce-144",
    "Cf-252",
    "Cl-36",
    "Cm-244",
    "Co-57",
    "Co-60",
    "Cr-51",
    "Cs-134",
    "Cs-137",
    "Cu-64",
    "Eu-152",
    "Eu-154",
    "F-18",
    "Fe-59",
    "Ga-67",
    "Ga-68",
    "Gd-153",
    "H-3",
    "Hg-203",
    "I-123",
    "I-125",
    "I-131",
    "In-111",
    "Ir-192",
    "K-40",
    "Lu-177",
    "Mn-54",
    "Na-22",
    "Nb-95",
    "Np-237",
    "P-32",
    "Pa-231",
    "Pa-234m",
    "Pb-210",
    "Pb-211",
    "Pb-212",
    "Pb-214",
    "Po-210",
    "Pu-239",
    "Ra-223",
    "Ra-224",
    "Ra-226",
    "Rn-219",
    "Rn-222",
    "Ru-103",
    "Ru-106",
    "Sb-124",
    "Sb-125",
    "Se-75",
    "Sn-113",
    "Sr-85",
    "Sr-90",
    "Tc-99m",
    "Th-227",
    "Th-228",
    "Th-230",
    "Th-232",
    "Th-234",
    "Tl-201",
    "Tl-208",
    "U-234",
    "U-235",
    "U-238",
    "Y-90",
    "Zn-65",
    "Zr-95",
]

# Gamma emission lines (keV) for reference
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

        # IMPORTANT: preserve the provided ordering as the authoritative index order.
        self._isotope_names = list(isotope_names)
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


# ==============================================================================
# 2D Model Architecture (Embedded from vega_portable_inference_2d.py)
# ==============================================================================

@dataclass
class Vega2DConfig:
    """Configuration for Vega 2D model."""
    
    # Input dimensions
    num_channels: int = 1023          # Energy channels
    num_time_intervals: int = 60      # Fixed time dimension
    
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
    Input shape: (batch, 1, 60, 1023) or (batch, 60, 1023)
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


# ==============================================================================
# API Models (Pydantic)
# ==============================================================================

class IsotopePrediction(BaseModel):
    """Single isotope prediction result."""
    name: str = Field(..., description="Isotope name (e.g., 'Cs-137')")
    probability: float = Field(..., ge=0, le=1, description="Detection confidence [0, 1]")
    activity_bq: float = Field(..., ge=0, description="Estimated activity in Becquerels")
    present: bool = Field(..., description="True if probability >= threshold")


class IdentifyRequest(BaseModel):
    """Request to identify isotopes from 2D spectrum data."""
    spectrum: List[List[float]] = Field(
        ..., 
        description=f"2D gamma spectrum as list of {NUM_TIME_INTERVALS} time intervals, each with {NUM_CHANNELS} channels"
    )
    threshold: float = Field(
        default=DEFAULT_THRESHOLD, 
        ge=0, 
        le=1,
        description="Detection threshold (0-1). Lower = more sensitive, higher = more specific"
    )
    return_all: bool = Field(
        default=False,
        description="If true, return all 82 isotopes. If false, only detected ones."
    )


class IdentifyRequest1D(BaseModel):
    """Request to identify isotopes from 1D spectrum data (legacy support)."""
    spectrum: List[float] = Field(
        ..., 
        min_length=NUM_CHANNELS, 
        max_length=NUM_CHANNELS,
        description=f"1D gamma spectrum as list of {NUM_CHANNELS} float values (will be expanded to 2D)"
    )
    threshold: float = Field(default=DEFAULT_THRESHOLD, ge=0, le=1)
    return_all: bool = Field(default=False)


class IdentifyB64Request(BaseModel):
    """Request with base64-encoded numpy array."""
    spectrum_b64: str = Field(
        ...,
        description="Base64-encoded numpy array (.npy format bytes). Shape: (60, 1023) or (1023,)"
    )
    threshold: float = Field(default=DEFAULT_THRESHOLD, ge=0, le=1)
    return_all: bool = Field(default=False)


class IdentifyBatchRequest(BaseModel):
    """Batch identification request."""
    spectra: List[List[List[float]]] = Field(
        ...,
        description=f"List of 2D spectra, each with shape ({NUM_TIME_INTERVALS}, {NUM_CHANNELS})"
    )
    threshold: float = Field(default=DEFAULT_THRESHOLD, ge=0, le=1)
    return_all: bool = Field(default=False)


class IdentifyResponse(BaseModel):
    """Response from isotope identification."""
    model_name: str
    model_version: str
    label_index_version: str
    isotope_names: List[str] = Field(..., description="Canonical isotope names array (length 82)")
    probabilities: List[float] = Field(..., description="Per-isotope probabilities (length 82)")
    activities_bq: List[float] = Field(..., description="Per-isotope activity estimates in Bq (length 82)")
    isotopes: List[IsotopePrediction] = Field(..., description="List of isotope predictions")
    num_detected: int = Field(..., description="Number of isotopes detected above threshold")
    confidence: float = Field(..., description="Overall prediction confidence")
    threshold_used: float = Field(..., description="Detection threshold that was applied")
    processing_time_ms: float = Field(..., description="Processing time in milliseconds")


class IdentifyBatchResponse(BaseModel):
    """Response from batch identification."""
    results: List[IdentifyResponse]
    total_spectra: int
    total_processing_time_ms: float


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    model_loaded: bool
    device: str


class InfoResponse(BaseModel):
    """Model information response."""
    service: str = "vega-isotope-identification"
    version: str = "2.0.0"
    model_path: str
    device: str
    num_isotopes: int
    num_channels: int
    num_time_intervals: int
    default_threshold: float
    model_loaded: bool
    architecture: str
    model_name: str
    model_version: str
    label_index_version: str
    clamp_non_negative: bool
    channel_policy: str
    padding_policy: str
    normalization_policy: str
    energy_alignment_policy: str


class IsotopeListResponse(BaseModel):
    """List of supported isotopes."""
    isotopes: List[str]
    total: int
    gamma_lines: Dict[str, List[List[float]]]


# ==============================================================================
# Inference Engine Singleton
# ==============================================================================

class Vega2DInferenceEngine:
    """Singleton inference engine for 2D isotope identification."""
    
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def initialize(self, model_path: str, device: str = None):
        """Load the 2D model."""
        if self._initialized:
            return
        
        self.model_path = model_path
        
        # Device selection
        if device is not None:
            self.device = torch.device(device)
        elif torch.cuda.is_available():
            try:
                _ = torch.zeros(1, device='cuda') + 1
                self.device = torch.device('cuda')
            except RuntimeError:
                self.device = torch.device('cpu')
        else:
            self.device = torch.device('cpu')
        
        logger.info(f"Loading 2D model from: {model_path}")
        logger.info(f"Using device: {self.device}")
        
        # Load checkpoint
        self.checkpoint = torch.load(model_path, map_location=self.device, weights_only=False)
        
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
        
        # Isotope index
        self.isotope_index = IsotopeIndex()
        
        logger.info(f"✓ Model loaded successfully")
        logger.info(f"  Input shape: ({self.model_config.num_time_intervals}, {self.model_config.num_channels})")
        logger.info(f"  Isotopes: {self.isotope_index.num_isotopes}")
        logger.info(f"  Architecture: 2D-CNN{self.model_config.conv_channels} → FC{self.model_config.fc_hidden_dims}")
        
        self._initialized = True
    
    @property
    def is_loaded(self) -> bool:
        return self._initialized
    
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
            spectrum: Input array, shape (T, 1023) or (1023,)
            normalize: Normalize to [0, 1] range
            
        Returns:
            Tensor ready for model, shape (1, 60, 1023)
        """
        # Coerce to float32
        spectrum = np.asarray(spectrum, dtype=np.float32)

        # Handle 1D input (single spectrum) - expand by repeating
        if spectrum.ndim == 1:
            spectrum = np.tile(spectrum.reshape(1, -1), (self.model_config.num_time_intervals, 1))

        if spectrum.ndim != 2:
            raise ValueError(f"Expected 1D or 2D spectrum, got shape {spectrum.shape}")

        # Channel semantics policy: some upstream devices provide 1024 channels with an overflow/dummy channel.
        if spectrum.shape[1] == 1024 and CHANNEL_POLICY == "drop_last_if_1024":
            spectrum = spectrum[:, :NUM_CHANNELS]

        if spectrum.shape[1] != NUM_CHANNELS:
            raise ValueError(f"Expected {NUM_CHANNELS} channels, got {spectrum.shape[1]}")

        # Enforce non-negativity only (no other clamping/quantization)
        if CLAMP_NON_NEGATIVE:
            spectrum = np.maximum(spectrum, 0.0)
        
        # Ensure correct time dimension
        spectrum = self._pad_or_truncate(spectrum)
        
        # Normalize (global max over entire 2D matrix), matching training: x = x / max(x)
        if normalize:
            max_val = float(np.max(spectrum))
            if max_val > 0:
                spectrum = spectrum / max_val
        
        # To tensor with batch dimension
        tensor = torch.tensor(spectrum, dtype=torch.float32).unsqueeze(0)
        return tensor.to(self.device)
    
    @torch.no_grad()
    def predict(
        self,
        spectrum: np.ndarray,
        threshold: float = 0.5,
        return_all: bool = False
    ) -> Dict[str, Any]:
        """Run inference on a gamma spectrum."""
        import time
        start_time = time.perf_counter()
        
        # Preprocess
        tensor = self.preprocess(spectrum)
        
        # Run model
        logits, activities = self.model(tensor)
        
        # Convert to probabilities
        probs = torch.sigmoid(logits).cpu().numpy()[0]
        activities = activities.cpu().numpy()[0] * self.model_config.max_activity_bq
        
        # Build predictions
        isotope_names = self.isotope_index.isotope_names

        probabilities = [float(p) for p in probs.tolist()]
        activities_bq = [float(a) for a in activities.tolist()]

        isotopes = []
        for i in range(len(probabilities)):
            prob = probabilities[i]
            activity = activities_bq[i]
            present = prob >= threshold

            if return_all or present:
                isotopes.append({
                    "name": isotope_names[i],
                    "probability": prob,
                    "activity_bq": activity,
                    "present": present
                })
        
        # Calculate confidence
        present_isotopes = [iso for iso in isotopes if iso["present"]]
        if present_isotopes:
            confidence = np.mean([iso["probability"] for iso in present_isotopes])
        else:
            confidence = 1.0 - float(probs.max())
        
        processing_time = (time.perf_counter() - start_time) * 1000
        
        return {
            "model_name": MODEL_NAME,
            "model_version": MODEL_VERSION,
            "label_index_version": LABEL_INDEX_VERSION,
            "isotope_names": isotope_names,
            "probabilities": probabilities,
            "activities_bq": activities_bq,
            "isotopes": isotopes,
            "num_detected": len(present_isotopes),
            "confidence": round(float(confidence), 4),
            "threshold_used": threshold,
            "processing_time_ms": round(processing_time, 2)
        }


# Global inference engine
engine = Vega2DInferenceEngine()


# ==============================================================================
# FastAPI Application
# ==============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    # Startup: load model
    logger.info("Starting Vega 2D Isotope Identification API...")
    try:
        engine.initialize(MODEL_PATH, DEVICE)
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        # Don't crash - allow health check to report unhealthy
    yield
    # Shutdown
    logger.info("Shutting down...")


app = FastAPI(
    title="Vega 2D Isotope Identification API",
    description="Gamma spectrum isotope identification using 2D CNN deep learning",
    version="2.0.0",
    lifespan=lifespan
)


@app.middleware("http")
async def log_request_metadata(request, call_next):
    request_id = str(uuid.uuid4())
    logger.info(
        f"request_id={request_id} method={request.method} path={request.url.path} "
        f"model_name={MODEL_NAME} model_version={MODEL_VERSION} label_index_version={LABEL_INDEX_VERSION}"
    )
    response = await call_next(request)
    response.headers["x-request-id"] = request_id
    return response

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

@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy" if engine.is_loaded else "unhealthy",
        "model_loaded": engine.is_loaded,
        "device": str(engine.device) if engine.is_loaded else "N/A"
    }


@app.get("/info", response_model=InfoResponse)
async def get_info():
    """Get model and service information."""
    return {
        "service": "vega-isotope-identification",
        "version": "2.0.0",
        "model_path": MODEL_PATH,
        "device": str(engine.device) if engine.is_loaded else "N/A",
        "num_isotopes": NUM_ISOTOPES,
        "num_channels": NUM_CHANNELS,
        "num_time_intervals": NUM_TIME_INTERVALS,
        "default_threshold": DEFAULT_THRESHOLD,
        "model_loaded": engine.is_loaded,
        "architecture": f"2D-CNN{engine.model_config.conv_channels} → FC{engine.model_config.fc_hidden_dims}" if engine.is_loaded else "N/A",
        "model_name": MODEL_NAME,
        "model_version": MODEL_VERSION,
        "label_index_version": LABEL_INDEX_VERSION,
        "clamp_non_negative": CLAMP_NON_NEGATIVE,
        "channel_policy": CHANNEL_POLICY,
        "padding_policy": "pad_zeros_at_beginning; truncate_keep_last_intervals",
        "normalization_policy": "global_max_normalization_over_entire_matrix; if max==0 no-op",
        "energy_alignment_policy": ENERGY_ALIGNMENT_POLICY,
    }


@app.get("/isotopes", response_model=IsotopeListResponse)
async def list_isotopes():
    """List all supported isotopes with their gamma lines."""
    return {
        "isotopes": ISOTOPE_NAMES,
        "total": len(ISOTOPE_NAMES),
        "gamma_lines": {k: [list(line) for line in v] for k, v in GAMMA_LINES.items()}
    }


@app.post("/identify", response_model=IdentifyResponse)
async def identify_isotopes(request: IdentifyRequest):
    """
    Identify isotopes from a 2D gamma spectrum.
    
    The spectrum should be 60 time intervals × 1023 energy channels.
    Energy range: 20 keV to 3000 keV.
    Time: 60 one-second intervals.
    """
    if not engine.is_loaded:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        spectrum = np.asarray(request.spectrum, dtype=np.float32)
        if spectrum.ndim != 2:
            raise HTTPException(status_code=400, detail=f"Expected 2D spectrum, got shape {spectrum.shape}")

        # NOTE: time dimension can be != 60; engine will pad/truncate.
        # Channel dimension must be 1023 (or 1024 with policy drop_last_if_1024).
        result = engine.predict(spectrum, request.threshold, request.return_all)
        return result
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Prediction error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/identify/1d", response_model=IdentifyResponse)
async def identify_isotopes_1d(request: IdentifyRequest1D):
    """
    Identify isotopes from a 1D gamma spectrum (legacy support).
    
    The spectrum will be expanded to 2D by repeating across 60 time intervals.
    """
    if not engine.is_loaded:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        spectrum = np.array(request.spectrum, dtype=np.float32)
        result = engine.predict(spectrum, request.threshold, request.return_all)
        return result
    except Exception as e:
        logger.error(f"Prediction error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/identify/b64", response_model=IdentifyResponse)
async def identify_isotopes_b64(request: IdentifyB64Request):
    """
    Identify isotopes from a base64-encoded numpy array.
    
    The spectrum should be a .npy file encoded as base64.
    Accepts shape (60, 1023) or (1023,).
    """
    if not engine.is_loaded:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        # Decode base64
        npy_bytes = base64.b64decode(request.spectrum_b64)
        spectrum = np.load(io.BytesIO(npy_bytes))
        
        # NOTE: shape validation (channels/time) is enforced by engine.preprocess.
        result = engine.predict(spectrum, request.threshold, request.return_all)
        return result
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Prediction error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/identify/batch", response_model=IdentifyBatchResponse)
async def identify_isotopes_batch(request: IdentifyBatchRequest):
    """
    Batch identification for multiple 2D spectra.
    """
    if not engine.is_loaded:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    import time
    start_time = time.perf_counter()
    
    results = []
    for spectrum_data in request.spectra:
        spectrum = np.array(spectrum_data, dtype=np.float32)
        if spectrum.ndim != 2 or spectrum.shape[1] != NUM_CHANNELS:
            raise HTTPException(
                status_code=400,
                detail=f"Each spectrum must have shape (T, {NUM_CHANNELS})"
            )
        result = engine.predict(spectrum, request.threshold, request.return_all)
        results.append(result)
    
    total_time = (time.perf_counter() - start_time) * 1000
    
    return {
        "results": results,
        "total_spectra": len(results),
        "total_processing_time_ms": round(total_time, 2)
    }


# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Vega 2D Isotope Identification API")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to")
    parser.add_argument("--port", type=int, default=8020, help="Port to bind to")
    parser.add_argument("--model", default=MODEL_PATH, help="Path to model checkpoint")
    args = parser.parse_args()
    
    MODEL_PATH = args.model
    uvicorn.run(app, host=args.host, port=args.port)
