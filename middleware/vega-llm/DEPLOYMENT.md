# Vega LLM Deployment Guide

## Overview

Vega LLM is a local LLM API service using Qwen2.5-0.5B-Instruct for fast, efficient text generation with a custom system prompt that gives it a helpful, technical personality.

## Prerequisites

- Docker with NVIDIA Container Toolkit
- RTX 3090 or similar GPU with 24GB VRAM (Qwen2.5-0.5B uses ~1.5GB)
- HuggingFace account and token (for model download)

## Quick Deployment

1. **Configure environment:**
   ```powershell
   Copy-Item .env.example .env
   # Edit .env with your server details and HF token
   ```

2. **Deploy:**
   ```powershell
   .\deploy.ps1
   ```

3. **Test:**
   ```powershell
   python client_sample.py chat
   ```

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SSH_USER` | Remote server username | `darkmatter2222` |
| `SSH_HOST` | Remote server IP/hostname | `192.168.86.48` |
| `REMOTE_PATH` | Deployment directory | `/home/darkmatter2222/vega-llm` |
| `API_PORT` | API port (default 8001) | `8001` |
| `HF_TOKEN` | HuggingFace token | `hf_xxxxx` |

## API Endpoints

### POST /chat
Chat completion with conversation history.

```json
{
  "message": "Hello, what can you help me with?",
  "system_prompt": "optional custom prompt",
  "history": [],
  "max_tokens": 512,
  "temperature": 0.7
}
```

Response:
```json
{
  "response": "Hello! I'm Vega, your technical assistant...",
  "full_history": [...],
  "model_name": "Qwen/Qwen3-0.6B",
  "tokens_generated": 45
}
```

### POST /generate
Raw text completion without chat formatting.

```json
{
  "prompt": "def fibonacci(n):",
  "max_tokens": 256,
  "temperature": 0.7
}
```

### GET /health
Health check and model status.

### GET /info
Model and server information.

## Manual Docker Commands

```bash
# Build
docker compose build

# Run
docker compose up -d

# View logs
docker logs -f vega-llm

# Stop
docker compose down
```

## Model Storage

The HuggingFace model cache is persisted to `~/huggingface-cache` on the server, so models only download once.

## Troubleshooting

### Model download fails
- Ensure `HF_TOKEN` is set in `.env`
- Check HuggingFace token has read permissions
- Try: `docker exec vega-llm pip install --upgrade huggingface-hub`

### GPU not detected
- Verify NVIDIA Container Toolkit: `nvidia-smi` should work inside container
- Check docker-compose has `deploy.resources.reservations`

### Out of memory
- Qwen2.5-0.5B should only use ~1.5GB VRAM
- Check for other processes using GPU memory
- Reduce `max_tokens` in requests
