# Vega Project - Agent Instructions

## Project Overview

Vega is a suite of local AI services designed for GPU deployment. It consists of:

1. **vega-tts** - Text-to-Speech service using Chatterbox TTS with voice cloning
2. **vega-llm** - Chat/text generation service using Qwen2.5-0.5B-Instruct
3. **vega-isotope-identification** - 2D CNN isotope identification service (ports 8020 direct, 8080 via ingress)

## Architecture

- Both services are containerized with Docker and NVIDIA runtime
- Services run on a local server with RTX 3090 GPU
- TTS runs on port 8000, LLM runs on port 8001
- Each service has its own deployment script (`deploy.ps1`)

## Tech Stack

- **Runtime**: Python 3.10+, PyTorch 2.1.0, CUDA 12.1
- **API Framework**: FastAPI + Uvicorn
- **TTS Model**: Chatterbox TTS (ResembleAI)
- **LLM Model**: Qwen3-0.6B (Alibaba/Qwen)
- **Containerization**: Docker with nvidia-container-toolkit
- **Deployment Target**: Ubuntu Linux with NVIDIA GPU

## Directory Structure

```
vega/
├── vega-tts/           # Text-to-Speech service
│   ├── api.py          # FastAPI server
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── deploy.ps1      # PowerShell deployment script
│   ├── client_sample.py
│   ├── requirements-api.txt
│   └── models/vega_tuned/  # Voice conditioning files
│
├── vega-llm/           # LLM service
│   ├── api.py          # FastAPI server
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── deploy.ps1      # PowerShell deployment script
│   ├── client_sample.py
│   └── requirements.txt
│
├── README.md           # Main documentation
├── LICENSE
└── .gitignore
```

## Deployment

Both services deploy via PowerShell scripts that:
1. Load credentials from `.env`
2. Copy files to remote server via SCP
3. Build Docker image on server
4. Start container with GPU access
5. Verify deployment

The isotope identification service deploys the same way (remote build/run on the server; do not rely on local Docker).

Server details are in each service's `.env` file (not committed to git).

## API Patterns

### TTS API (port 8000)
- `POST /synthesize` - Returns WAV audio
- `POST /synthesize/b64` - Returns base64-encoded audio
- `GET /health` - Health check
- `GET /info` - Model info

### LLM API (port 8001)
- `POST /chat` - Chat completion with history
- `POST /generate` - Raw text completion
- `GET /health` - Health check
- `GET /info` - Model info

## Code Style

- Use type hints in Python
- FastAPI with Pydantic models for request/response
- Async endpoints where beneficial
- Comprehensive error handling with HTTPException
- Docker multi-stage builds for smaller images

## Important Files

- `.env` files contain secrets (SSH credentials, HF tokens) - NEVER commit these
- `.env.example` files show required variables without values
- `models/` directories contain large binary files - use Git LFS or exclude from git

## When Making Changes

1. Test locally if possible before deploying
2. Update DEPLOYMENT.md if deployment process changes
3. Keep Docker images minimal (use .dockerignore)
4. Both services should start and load models on container startup
5. Models are downloaded from HuggingFace at first run, cached locally

## Common Tasks

### Add new TTS endpoint
Edit `vega-tts/api.py`, add Pydantic models and FastAPI route

### Change LLM system prompt
Edit `DEFAULT_SYSTEM_PROMPT` in `vega-llm/api.py`

### Deploy updated service
```powershell
cd vega-tts  # or vega-llm
.\deploy.ps1
```

### Deploy isotope identification service
```powershell
cd vega-isotope-identification
.\deploy.ps1
```

### Test API locally
```powershell
python client_sample.py demo
python client_sample.py chat
```
