# ==============================================================================
# Vega Voice Synthesis API - Docker Image
# ==============================================================================
# Multi-stage build for smaller final image
# Requires NVIDIA GPU runtime for inference

FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime AS base

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libsndfile1 \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# ==============================================================================
# Dependencies stage
# ==============================================================================
FROM base AS dependencies

# Copy requirements first for better caching
COPY requirements-api.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements-api.txt

# ==============================================================================
# Final stage
# ==============================================================================
FROM dependencies AS final

# Copy application code
COPY tts_api.py .

# Copy model files (these should be mounted or baked in)
# For production, mount these as volumes instead
COPY models/vega_tuned /app/models/vega_tuned

# Environment variables
ENV VEGA_MODEL_DIR=/app/models/vega_tuned
ENV VEGA_DEVICE=cuda
ENV VEGA_MAX_TEXT_LENGTH=5000

# Expose port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# Run the API
CMD ["python", "tts_api.py", "--host", "0.0.0.0", "--port", "8000"]
