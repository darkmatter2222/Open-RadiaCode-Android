# ==============================================================================
# Vega LLM API - Docker Image
# ==============================================================================
# Qwen3-0.6B Language Model API
# Requires NVIDIA GPU runtime for inference

FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime AS base

# Prevent interactive prompts
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ==============================================================================
# Dependencies
# ==============================================================================
FROM base AS dependencies

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ==============================================================================
# Final
# ==============================================================================
FROM dependencies AS final

COPY llm_api.py .

# Environment
ENV VEGA_MODEL_ID=Qwen/Qwen2.5-0.5B-Instruct
ENV VEGA_DEVICE=cuda
ENV VEGA_MAX_TOKENS=2048

# Port
EXPOSE 8001

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8001/health || exit 1

# Run
CMD ["python", "llm_api.py", "--host", "0.0.0.0", "--port", "8001"]
