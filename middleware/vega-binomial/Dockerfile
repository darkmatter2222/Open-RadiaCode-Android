# ==============================================================================
# Vega Bulk Binomial Isotope Detection API - Docker Image
# ==============================================================================
# Multi-stage build for smaller final image
# Supports both GPU and CPU inference
# Loads ALL per-isotope binary models onto GPU at startup

FROM pytorch/pytorch:2.4.0-cuda12.1-cudnn9-runtime AS base

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# ==============================================================================
# Dependencies stage
# ==============================================================================
FROM base AS dependencies

# Copy requirements first for better caching
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# ==============================================================================
# Final stage
# ==============================================================================
FROM dependencies AS final

# Copy application code
COPY binomial_api.py .

# Create models directory (mount as volume for production)
RUN mkdir -p /app/models

# Environment variables
ENV VEGA_BINOMIAL_MODELS_DIR=/app/models
ENV VEGA_DEVICE=cuda
ENV VEGA_DEFAULT_THRESHOLD=0.5

# Expose port
EXPOSE 8021

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:8021/health || exit 1

# Run the API
CMD ["python", "binomial_api.py", "--host", "0.0.0.0", "--port", "8021"]
