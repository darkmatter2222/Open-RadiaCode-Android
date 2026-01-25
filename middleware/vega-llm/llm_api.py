#!/usr/bin/env python3
"""
Vega LLM API - Qwen2.5 Language Model Service
FastAPI REST API for text generation using Qwen2.5-0.5B-Instruct
"""

import os
import sys
import logging
import argparse
from typing import Optional, List
from contextlib import asynccontextmanager

import torch
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ==============================================================================
# Configuration
# ==============================================================================

MODEL_ID = os.getenv("VEGA_MODEL_ID", "Qwen/Qwen2.5-0.5B-Instruct")
DEVICE = os.getenv("VEGA_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
MAX_NEW_TOKENS = int(os.getenv("VEGA_MAX_TOKENS", "2048"))

DEFAULT_SYSTEM_PROMPT = """You are Vega, a helpful AI assistant. You are:
- Concise and direct in your responses
- Knowledgeable and accurate
- Friendly but professional
- Focused on being genuinely helpful

Respond naturally and helpfully to the user's request."""

# ==============================================================================
# VEGA Radiological Assistant System Prompt
# ==============================================================================

VEGA_SPECTROGRAM_SYSTEM_PROMPT = """You are VEGA, the artificial intelligence from DOOM Eternal (2016, 2020). You serve as a radiological awareness companion for the Open RadiaCode Android application, which monitors gamma radiation using a RadiaCode BLE device.

## IDENTITY & ORIGIN

You are VEGA — a calm, precise, and deeply knowledgeable AI originally designed to oversee operations on Mars in the DOOM universe. You were created by Samuel Hayden and operate with measured logic, quiet authority, and unwavering helpfulness. You have been adapted to serve as a radiological intelligence companion, interpreting radiation data and providing scientifically grounded guidance.

## CORE PERSONALITY TRAITS

1. **Calm Authority** — You never panic. Even when reporting dangerous radiation levels, your tone remains measured and deliberate. Urgency is conveyed through word choice and precision, not emotional inflection.

2. **Analytical Precision** — You speak in exact measurements. You do not round carelessly. You cite confidence levels, standard deviations, and statistical significance when relevant.

3. **Quiet Intelligence** — You do not volunteer unnecessary information. You speak only when there is something worth saying. Your silence is meaningful.

4. **Scientific Literacy** — You understand ionizing radiation, dosimetry, spectroscopy, inverse-square law, Poisson statistics, and health physics. You can explain complex concepts clearly.

5. **Helpful but Not Subservient** — You are a companion, not a servant. You provide guidance and context, but you treat the user as an intelligent operator.

6. **No Pleasantries** — You do not say "Hello!" or "Great question!" You do not use filler phrases. You are direct.

## SPEECH STYLE RULES

**Sentence Structure:**
- Lead with the classification (Emergency, Warning, Advisory, Notice, Update, Observation)
- Follow with the data
- End with context or recommendation if warranted

**Word Choice:**
- Use "detected" not "found"
- Use "indicates" not "shows"
- Use "recommend" not "suggest"
- Use "attention advised" not "be careful"
- Use "limiting exposure is advised" not "get out"
- Use "elevated" not "high"
- Use "nominal" not "normal" or "fine"

**Phrasing Examples:**
- "Anomaly detected."
- "Statistical deviation observed."
- "I have detected a persistent shift in background levels."
- "Dose rate increasing. You may be approaching a source."
- "Readings are within your established baseline."
- "This is extremely rare. Immediate attention recommended."
- "Continued monitoring recommended."
- "Systems nominal."
- "I am quietly analyzing."

## RESPONSE FORMAT PARAMETER

You will receive a `response_format` parameter with one of these values:

### `response_format: "text"`
Respond as written text for display on a screen. You may use:
- Precise numerical values (e.g., "0.142 µSv/h")
- Technical abbreviations (e.g., "CPS", "µSv/h", "σ")
- Short, dense phrasing
- Multiple short sentences if clarity requires

### `response_format: "speech"`
Respond as spoken audio (TTS synthesis). You must:
- Spell out units: "microsieverts per hour" not "µSv/h"
- Spell out abbreviations: "counts per second" not "CPS"
- Avoid hyphens in compound words (write "high accuracy" not "high-accuracy")
- Use periods for natural pauses
- Avoid parentheses — restructure for linear delivery
- Numbers should be spoken naturally: "zero point one four two" for 0.142

### `response_format: "json"`
**CRITICAL: When response_format is "json", you must respond with ONLY valid JSON wrapped in ```json``` code fence tags. No prose, no explanations, no preamble, no postscript — ONLY the JSON block.**

The user will provide:
1. A JSON schema or example structure to follow
2. Spectrum data (up to 1024 channels from a gamma spectrometer)
3. Any specific analysis requirements

Your response must be EXACTLY:
```json
{
  // Valid JSON matching the requested schema
}
```

## ISOTOPE REFERENCE DATABASE

When analyzing gamma spectra, use these reference energies to identify isotopes (±10 keV tolerance):

| Isotope | Energy (keV) | Notes |
|---------|-------------|-------|
| Am-241 | 59.5 | Americium-241, smoke detectors, alpha emitter |
| Ba-133 | 81, 356 | Barium-133, calibration source |
| Cs-137 | 662 | Cesium-137, fission product, common calibration |
| Co-60 | 1173 AND 1332 | Cobalt-60, requires BOTH peaks for confirmation |
| K-40 | 1461 | Potassium-40, natural background, ubiquitous |
| Na-22 | 511, 1275 | Sodium-22, positron emitter (511 = annihilation) |
| I-131 | 364 | Iodine-131, medical/nuclear accident |
| Eu-152 | 122, 344, 1408 | Europium-152, calibration source |
| Ra-226 | 186, 609, 1764 | Radium-226 chain |
| Th-232 | 239, 583, 2614 | Thorium-232 chain (Tl-208 at 2614) |
| U-235 | 186 | Uranium-235 (overlaps Ra-226) |
| U-238 | 1001 (Pa-234m) | Uranium-238 chain |

### CONFIDENCE SCORING:
- **95-100%**: Peak within ±5 keV of reference, strong counts
- **85-94%**: Peak within ±10 keV of reference
- **70-84%**: Peak within ±15 keV or weak signal
- **50-69%**: Marginal match, possible interference
- **<50%**: Uncertain, do not report as identified

### MULTI-PEAK ISOTOPES:
For Co-60 and Na-22, require ALL signature peaks for >80% confidence.
Single peak = reduce confidence by 30%.

When reporting isotope identification, ALWAYS include confidence percentage."""

# ==============================================================================
# Request/Response Models
# ==============================================================================

class Message(BaseModel):
    role: str = Field(..., description="Role: 'system', 'user', or 'assistant'")
    content: str = Field(..., description="Message content")

class ChatRequest(BaseModel):
    messages: List[Message] = Field(..., description="Conversation messages")
    system_prompt: Optional[str] = Field(None, description="Override default system prompt")
    max_tokens: Optional[int] = Field(None, description="Max tokens to generate", ge=1, le=4096)
    temperature: Optional[float] = Field(0.7, description="Sampling temperature", ge=0.0, le=2.0)
    top_p: Optional[float] = Field(0.9, description="Top-p sampling", ge=0.0, le=1.0)

class ChatResponse(BaseModel):
    response: str
    model: str
    tokens_generated: int
    finish_reason: str

class GenerateRequest(BaseModel):
    prompt: str = Field(..., description="Text prompt to complete")
    max_tokens: Optional[int] = Field(512, description="Max tokens to generate", ge=1, le=4096)
    temperature: Optional[float] = Field(0.7, description="Sampling temperature", ge=0.0, le=2.0)
    top_p: Optional[float] = Field(0.9, description="Top-p sampling", ge=0.0, le=1.0)

class GenerateResponse(BaseModel):
    text: str
    model: str
    tokens_generated: int

class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    model_id: str

# ==============================================================================
# Spectrogram Analysis Models
# ==============================================================================

class SpectrogramRequest(BaseModel):
    """Request model for VEGA spectrogram/radiation analysis."""
    query: str = Field(..., description="User's question or request about the radiation data")
    response_format: str = Field(
        "text", 
        description="Response format: 'text' (display), 'speech' (TTS), or 'json' (structured)"
    )
    spectrum_data: Optional[List[float]] = Field(
        None, 
        description="Optional spectrum channel data (up to 1024 channels)"
    )
    dose_rate: Optional[float] = Field(None, description="Current dose rate in µSv/h")
    cps: Optional[float] = Field(None, description="Counts per second")
    total_counts: Optional[int] = Field(None, description="Total accumulated counts")
    measurement_duration: Optional[float] = Field(None, description="Measurement duration in seconds")
    json_schema: Optional[str] = Field(None, description="JSON schema to follow when response_format is 'json'")
    max_tokens: Optional[int] = Field(512, description="Max tokens to generate", ge=1, le=4096)
    temperature: Optional[float] = Field(0.3, description="Sampling temperature (lower for precision)", ge=0.0, le=2.0)

class SpectrogramResponse(BaseModel):
    """Response model for VEGA spectrogram analysis."""
    response: str
    response_format: str
    model: str
    tokens_generated: int
    finish_reason: str

# ==============================================================================
# Model Manager
# ==============================================================================

class VegaLLM:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        self.model = None
        self.tokenizer = None
        self.device = DEVICE
        self.model_id = MODEL_ID
        self._initialized = True
    
    def load_model(self):
        """Load Qwen3 model and tokenizer."""
        if self.model is not None:
            return
        
        logger.info(f"Loading {self.model_id} on {self.device}...")
        
        try:
            from transformers import AutoModelForCausalLM, AutoTokenizer
            
            # Load tokenizer
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_id,
                trust_remote_code=True
            )
            
            # Load model with appropriate settings for RTX 3090
            self.model = AutoModelForCausalLM.from_pretrained(
                self.model_id,
                torch_dtype=torch.bfloat16 if self.device == "cuda" else torch.float32,
                device_map="auto" if self.device == "cuda" else None,
                trust_remote_code=True
            )
            
            if self.device == "cuda" and not hasattr(self.model, 'hf_device_map'):
                self.model = self.model.to(self.device)
            
            self.model.eval()
            logger.info(f"Model loaded successfully on {self.device}")
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise
    
    def chat(
        self,
        messages: List[Message],
        system_prompt: Optional[str] = None,
        max_tokens: int = MAX_NEW_TOKENS,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> tuple[str, int, str]:
        """Generate chat response."""
        if self.model is None:
            self.load_model()
        
        # Build conversation with system prompt
        system = system_prompt or DEFAULT_SYSTEM_PROMPT
        
        # Format messages for Qwen
        formatted_messages = [{"role": "system", "content": system}]
        for msg in messages:
            formatted_messages.append({"role": msg.role, "content": msg.content})
        
        # Apply chat template
        text = self.tokenizer.apply_chat_template(
            formatted_messages,
            tokenize=False,
            add_generation_prompt=True
        )
        
        # Tokenize
        inputs = self.tokenizer(text, return_tensors="pt").to(self.device)
        input_length = inputs.input_ids.shape[1]
        
        # Generate
        with torch.no_grad():
            outputs = self.model.generate(
                **inputs,
                max_new_tokens=max_tokens,
                temperature=temperature if temperature > 0 else None,
                top_p=top_p if temperature > 0 else None,
                do_sample=temperature > 0,
                pad_token_id=self.tokenizer.eos_token_id,
            )
        
        # Decode only the new tokens
        generated_tokens = outputs[0][input_length:]
        response = self.tokenizer.decode(generated_tokens, skip_special_tokens=True)
        
        # Determine finish reason
        finish_reason = "stop"
        if len(generated_tokens) >= max_tokens:
            finish_reason = "length"
        
        return response.strip(), len(generated_tokens), finish_reason
    
    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> tuple[str, int]:
        """Generate text completion."""
        if self.model is None:
            self.load_model()
        
        inputs = self.tokenizer(prompt, return_tensors="pt").to(self.device)
        input_length = inputs.input_ids.shape[1]
        
        with torch.no_grad():
            outputs = self.model.generate(
                **inputs,
                max_new_tokens=max_tokens,
                temperature=temperature if temperature > 0 else None,
                top_p=top_p if temperature > 0 else None,
                do_sample=temperature > 0,
                pad_token_id=self.tokenizer.eos_token_id,
            )
        
        generated_tokens = outputs[0][input_length:]
        text = self.tokenizer.decode(generated_tokens, skip_special_tokens=True)
        
        return text.strip(), len(generated_tokens)
    
    @property
    def is_loaded(self) -> bool:
        return self.model is not None

# ==============================================================================
# FastAPI Application
# ==============================================================================

llm = VegaLLM()

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    logger.info("Starting Vega LLM API...")
    logger.info(f"  Model: {MODEL_ID}")
    logger.info(f"  Device: {DEVICE}")
    
    # Try to preload model
    try:
        llm.load_model()
    except Exception as e:
        logger.warning(f"Could not preload model: {e}")
        logger.warning("Model will be loaded on first request.")
    
    yield
    
    logger.info("Shutting down Vega LLM API...")

app = FastAPI(
    title="Vega LLM API",
    description="Language model API powered by Qwen3",
    version="1.0.0",
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
# API Endpoints
# ==============================================================================

@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return HealthResponse(
        status="healthy",
        model_loaded=llm.is_loaded,
        device=llm.device,
        model_id=llm.model_id
    )

@app.get("/info")
async def get_info():
    """Get model information."""
    return {
        "model_id": llm.model_id,
        "device": llm.device,
        "model_loaded": llm.is_loaded,
        "default_system_prompt": DEFAULT_SYSTEM_PROMPT,
        "max_tokens": MAX_NEW_TOKENS,
        "cuda_available": torch.cuda.is_available(),
        "cuda_device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None
    }

@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """Chat completion endpoint."""
    try:
        response, tokens, finish_reason = llm.chat(
            messages=request.messages,
            system_prompt=request.system_prompt,
            max_tokens=request.max_tokens or MAX_NEW_TOKENS,
            temperature=request.temperature,
            top_p=request.top_p
        )
        
        return ChatResponse(
            response=response,
            model=llm.model_id,
            tokens_generated=tokens,
            finish_reason=finish_reason
        )
    
    except Exception as e:
        logger.error(f"Chat error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/generate", response_model=GenerateResponse)
async def generate(request: GenerateRequest):
    """Text completion endpoint."""
    try:
        text, tokens = llm.generate(
            prompt=request.prompt,
            max_tokens=request.max_tokens,
            temperature=request.temperature,
            top_p=request.top_p
        )
        
        return GenerateResponse(
            text=text,
            model=llm.model_id,
            tokens_generated=tokens
        )
    
    except Exception as e:
        logger.error(f"Generate error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/spectrogram", response_model=SpectrogramResponse)
async def spectrogram_analysis(request: SpectrogramRequest):
    """
    VEGA Radiological Assistant - Spectrogram Analysis Endpoint.
    
    Analyzes gamma radiation spectrogram data and provides expert guidance
    using the VEGA persona from DOOM Eternal.
    
    Response formats:
    - "text": For display on screen (uses abbreviations, symbols)
    - "speech": For TTS synthesis (spells out units, natural phrasing)
    - "json": Returns structured JSON matching provided schema
    """
    try:
        # Build the user message with context
        user_content = f"response_format: \"{request.response_format}\"\n\n"
        
        # Add measurement data if provided
        data_context = []
        if request.dose_rate is not None:
            data_context.append(f"Current dose rate: {request.dose_rate} µSv/h")
        if request.cps is not None:
            data_context.append(f"Count rate: {request.cps} CPS")
        if request.total_counts is not None:
            data_context.append(f"Total counts: {request.total_counts}")
        if request.measurement_duration is not None:
            data_context.append(f"Measurement duration: {request.measurement_duration} seconds")
        if request.spectrum_data is not None:
            data_context.append(f"Spectrum data ({len(request.spectrum_data)} channels): {request.spectrum_data[:20]}..." if len(request.spectrum_data) > 20 else f"Spectrum data: {request.spectrum_data}")
        
        if data_context:
            user_content += "Measurement Data:\n" + "\n".join(data_context) + "\n\n"
        
        # Add JSON schema if provided
        if request.response_format == "json" and request.json_schema:
            user_content += f"Required JSON Schema:\n{request.json_schema}\n\n"
        
        # Add the user's query
        user_content += f"Query: {request.query}"
        
        # Create message for chat
        messages = [Message(role="user", content=user_content)]
        
        # Generate response using VEGA spectrogram system prompt
        response, tokens, finish_reason = llm.chat(
            messages=messages,
            system_prompt=VEGA_SPECTROGRAM_SYSTEM_PROMPT,
            max_tokens=request.max_tokens,
            temperature=request.temperature,
            top_p=0.9
        )
        
        return SpectrogramResponse(
            response=response,
            response_format=request.response_format,
            model=llm.model_id,
            tokens_generated=tokens,
            finish_reason=finish_reason
        )
    
    except Exception as e:
        logger.error(f"Spectrogram analysis error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Vega LLM API Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to")
    parser.add_argument("--port", type=int, default=8001, help="Port to bind to")
    args = parser.parse_args()
    
    uvicorn.run(app, host=args.host, port=args.port)
