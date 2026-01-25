# Vega

A suite of AI services designed for local GPU deployment.

## Services

| Service | Description | Port | Model |
|---------|-------------|------|-------|
| [vega-tts](./vega-tts/) | Text-to-Speech with voice cloning | 8000 | Chatterbox TTS |
| [vega-llm](./vega-llm/) | Chat & text generation | 8001 | Qwen2.5-0.5B-Instruct |
| [vega-ingress](./vega-ingress/) | API Gateway / reverse proxy + request logging | 8080 | N/A |

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Client Applications                   │
└─────────────────────┬───────────────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼                           ▼
┌───────────────┐           ┌───────────────┐
│   vega-tts    │           │   vega-llm    │
│   Port 8000   │           │   Port 8001   │
├───────────────┤           ├───────────────┤
│ Chatterbox    │           │ Qwen3-0.6B    │
│ Voice Clone   │           │ Chat/Generate │
└───────────────┘           └───────────────┘
        │                           │
        └─────────────┬─────────────┘
                      │
               ┌──────┴──────┐
               │  RTX 3090   │
               │   24GB      │
               └─────────────┘
```

## Quick Start

### Prerequisites

- Docker with NVIDIA Container Toolkit
- NVIDIA GPU (RTX 3090 recommended)
- HuggingFace token for model downloads

### Deploy TTS Service

```powershell
cd vega-tts
Copy-Item .env.example .env
# Edit .env with your settings
.\deploy.ps1
```

### Deploy LLM Service

```powershell
cd vega-llm
Copy-Item .env.example .env
# Edit .env with your settings
.\deploy.ps1
```

## API Usage

### Text-to-Speech (vega-tts)

```python
import requests

response = requests.post("http://your-server:8000/synthesize", json={
    "text": "Hello, this is Vega speaking!"
})

with open("output.wav", "wb") as f:
    f.write(response.content)
```

### LLM Chat (vega-llm)

```python
import requests

response = requests.post("http://your-server:8001/chat", json={
    "message": "What is machine learning?"
})

print(response.json()["response"])

## Ingress Request Logging (vega-ingress)

`vega-ingress` writes one JSON file per request/response to a log directory, and automatically deletes log files older than 24 hours (configurable).

### What gets logged

- A unique request ID (also returned to the client as `x-request-id`)
- Request metadata: method, path, query, client IP, headers (with sensitive headers redacted)
- Request JSON body (only if request is JSON; capped by size)
- Response metadata: status, headers (redacted), content-type
- Response JSON body (only if response is JSON; capped by size)

Non-JSON bodies (e.g., WAV/audio) are not stored; only metadata is retained.

### Configuration (environment variables)

- `VEGA_INGRESS_LOG_ENABLED` (default `1`)
- `VEGA_INGRESS_LOG_DIR` (default `/var/log/vega-ingress`)
- `VEGA_INGRESS_LOG_RETENTION_HOURS` (default `24`)
- `VEGA_INGRESS_LOG_CLEANUP_INTERVAL_SECONDS` (default `600`)
- `VEGA_INGRESS_LOG_MAX_BODY_BYTES` (default `1048576`)

Optional protection for log retrieval endpoints:

- `VEGA_INGRESS_LOGS_TOKEN` (if set, clients must send header `x-vega-logs-token: <token>` to access `/logs` endpoints)

### Log APIs

- `GET /logs?limit=100`
    - Returns a list of recent log entries (metadata only)
- `GET /logs/{id}`
    - Returns the full JSON log record for the given ID

Example:

```bash
# Make any request and capture request id
curl -i http://your-server:8080/health

# List recent logs
curl http://your-server:8080/logs

# Fetch a specific log by id
curl http://your-server:8080/logs/<uuid>
```
```

## Server Requirements

- **GPU**: NVIDIA RTX 3090 (24GB VRAM) or equivalent
- **OS**: Ubuntu 22.04 LTS recommended
- **Docker**: 20.10+ with NVIDIA Container Toolkit
- **RAM**: 32GB recommended
- **Storage**: 50GB+ for models

## Project Structure

```
vega/
├── vega-tts/           # Text-to-Speech service
│   ├── api.py          # FastAPI server
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── deploy.ps1      # Deployment script
│   ├── client_sample.py
│   └── models/         # Voice conditioning files
│
├── vega-llm/           # LLM service
│   ├── api.py          # FastAPI server
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── deploy.ps1      # Deployment script
│   └── client_sample.py
│
└── README.md           # This file
```

## License

See [LICENSE](./LICENSE) for details.
