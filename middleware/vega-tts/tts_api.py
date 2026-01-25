#!/usr/bin/env python3
"""
Vega Voice Synthesis REST API

FastAPI server for text-to-speech synthesis using the tuned Vega voice.

Endpoints:
    POST /synthesize     - Generate audio from text, returns WAV file
    POST /synthesize/b64 - Generate audio from text, returns base64-encoded WAV
    GET  /health         - Health check
    GET  /info           - Model info and status
"""

import base64
import io
import os
import time
import wave
from pathlib import Path
from typing import Optional

import numpy as np
import torch
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, JSONResponse
from pydantic import BaseModel, Field
import uvicorn

# ==============================================================================
# Configuration
# ==============================================================================

SAMPLE_RATE = 24000
MAX_CHUNK_CHARS = 450
CHUNK_PAUSE_SEC = 0.08
MODEL_DIR = os.environ.get("VEGA_MODEL_DIR", "models/vega_tuned")
DEVICE = os.environ.get("VEGA_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
MAX_TEXT_LENGTH = int(os.environ.get("VEGA_MAX_TEXT_LENGTH", "5000"))

# ==============================================================================
# API Models
# ==============================================================================

class SynthesizeRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TEXT_LENGTH, description="Text to synthesize")
    
class SynthesizeResponse(BaseModel):
    audio_base64: str = Field(..., description="Base64-encoded WAV audio")
    duration_seconds: float = Field(..., description="Audio duration in seconds")
    generation_time_seconds: float = Field(..., description="Time taken to generate")
    sample_rate: int = Field(default=SAMPLE_RATE, description="Audio sample rate")

class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str

class InfoResponse(BaseModel):
    model_dir: str
    device: str
    sample_rate: int
    max_text_length: int
    model_loaded: bool

# ==============================================================================
# Audio utilities (from synthesize.py)
# ==============================================================================

import re

def split_into_chunks(text: str, max_chars: int = MAX_CHUNK_CHARS) -> list[str]:
    """Split text at sentence boundaries for chunked generation."""
    sentences = re.split(r'(?<=[.!?])\s+', text.strip())
    
    chunks = []
    current_chunk = []
    current_len = 0
    
    for sentence in sentences:
        sentence_len = len(sentence)
        
        if sentence_len > max_chars and not current_chunk:
            chunks.append(sentence)
            continue
        
        if current_len + sentence_len + 1 > max_chars and current_chunk:
            chunks.append(" ".join(current_chunk))
            current_chunk = [sentence]
            current_len = sentence_len
        else:
            current_chunk.append(sentence)
            current_len += sentence_len + 1
    
    if current_chunk:
        chunks.append(" ".join(current_chunk))
    
    return chunks


def trim_silence(audio: np.ndarray, threshold_db: float = -40.0) -> np.ndarray:
    """Trim leading and trailing silence from audio."""
    if len(audio) == 0:
        return audio
    
    peak = np.max(np.abs(audio))
    if peak < 1e-10:
        return audio
    
    threshold_linear = peak * (10 ** (threshold_db / 20.0))
    above_threshold = np.abs(audio) > threshold_linear
    
    if not np.any(above_threshold):
        return audio
    
    indices = np.where(above_threshold)[0]
    start_idx = max(0, indices[0] - 240)
    end_idx = min(len(audio), indices[-1] + 240)
    
    return audio[start_idx:end_idx + 1]


def audio_to_wav_bytes(audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> bytes:
    """Convert float32 audio array to WAV bytes."""
    audio = np.clip(audio, -1.0, 1.0)
    pcm16 = (audio * 32767.0).astype(np.int16)
    
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm16.tobytes())
    
    return buffer.getvalue()


# ==============================================================================
# Synthesizer singleton
# ==============================================================================

class VegaSynthesizer:
    """Voice synthesizer using tuned Vega model."""
    
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        
        self.model_dir = Path(MODEL_DIR)
        self.device = DEVICE
        self.model = None
        self.conds_path = self.model_dir / "conds.pt"
        self._initialized = True
        
    def load(self):
        """Load the model and conditioning."""
        if self.model is not None:
            return
        
        if not self.conds_path.exists():
            raise FileNotFoundError(
                f"Conditioning not found: {self.conds_path}\n"
                "Ensure the model files are mounted correctly."
            )
        
        print(f"Loading Chatterbox-Turbo on {self.device}...")
        from chatterbox.tts_turbo import ChatterboxTurboTTS, Conditionals
        
        self.model = ChatterboxTurboTTS.from_pretrained(device=self.device)
        self.model.conds = Conditionals.load(self.conds_path, map_location=self.device)
        print("Model ready.")
    
    @property
    def is_loaded(self) -> bool:
        return self.model is not None
    
    def synthesize(self, text: str) -> tuple[np.ndarray, float]:
        """
        Synthesize speech from text.
        
        Returns:
            Tuple of (audio array, generation time)
        """
        self.load()
        
        text = text.strip()
        if not text:
            raise ValueError("Empty text provided")
        
        start_time = time.time()
        char_count = len(text)
        
        if char_count > MAX_CHUNK_CHARS:
            chunks = split_into_chunks(text)
            all_audio = []
            
            for chunk in chunks:
                wav_tensor = self.model.generate(chunk)
                chunk_audio = wav_tensor.squeeze().cpu().numpy()
                chunk_audio = trim_silence(chunk_audio)
                all_audio.append(chunk_audio)
                all_audio.append(np.zeros(int(CHUNK_PAUSE_SEC * SAMPLE_RATE), dtype=np.float32))
            
            audio = np.concatenate(all_audio[:-1])
        else:
            wav_tensor = self.model.generate(text)
            audio = wav_tensor.squeeze().cpu().numpy()
            audio = trim_silence(audio)
        
        generation_time = time.time() - start_time
        return audio, generation_time


# ==============================================================================
# FastAPI App
# ==============================================================================

app = FastAPI(
    title="Vega Voice Synthesis API",
    description="REST API for text-to-speech synthesis using the tuned Vega voice",
    version="1.0.0",
)

synthesizer = VegaSynthesizer()


@app.on_event("startup")
async def startup_event():
    """Preload model on startup."""
    print("Starting Vega API...")
    print(f"  Model dir: {MODEL_DIR}")
    print(f"  Device: {DEVICE}")
    try:
        synthesizer.load()
    except Exception as e:
        print(f"Warning: Could not preload model: {e}")
        print("Model will be loaded on first request.")


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return HealthResponse(
        status="healthy",
        model_loaded=synthesizer.is_loaded,
        device=synthesizer.device,
    )


@app.get("/info", response_model=InfoResponse)
async def get_info():
    """Get API info and model status."""
    return InfoResponse(
        model_dir=str(synthesizer.model_dir),
        device=synthesizer.device,
        sample_rate=SAMPLE_RATE,
        max_text_length=MAX_TEXT_LENGTH,
        model_loaded=synthesizer.is_loaded,
    )


@app.post("/synthesize")
async def synthesize_audio(request: SynthesizeRequest):
    """
    Synthesize speech from text.
    
    Returns WAV audio file directly.
    """
    try:
        audio, gen_time = synthesizer.synthesize(request.text)
        wav_bytes = audio_to_wav_bytes(audio)
        
        return Response(
            content=wav_bytes,
            media_type="audio/wav",
            headers={
                "X-Duration-Seconds": str(len(audio) / SAMPLE_RATE),
                "X-Generation-Time-Seconds": str(gen_time),
            }
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/synthesize/b64", response_model=SynthesizeResponse)
async def synthesize_audio_base64(request: SynthesizeRequest):
    """
    Synthesize speech from text.
    
    Returns base64-encoded WAV audio in JSON response.
    """
    try:
        audio, gen_time = synthesizer.synthesize(request.text)
        wav_bytes = audio_to_wav_bytes(audio)
        
        return SynthesizeResponse(
            audio_base64=base64.b64encode(wav_bytes).decode("utf-8"),
            duration_seconds=len(audio) / SAMPLE_RATE,
            generation_time_seconds=gen_time,
            sample_rate=SAMPLE_RATE,
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Vega Voice Synthesis API")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to")
    parser.add_argument("--port", type=int, default=8000, help="Port to bind to")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload")
    
    args = parser.parse_args()
    
    uvicorn.run(
        "tts_api:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
    )
