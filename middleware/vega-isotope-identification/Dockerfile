# ==============================================================================
# Vega 2D Isotope Identification API - Docker Image
# ==============================================================================
# Multi-stage build for smaller final image
# Supports both GPU and CPU inference
# Model: 2D CNN (default 300 time intervals × 1023 energy channels)

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
COPY isotope_api.py .

# Copy 2D model file (mount as volume for production)
COPY models/vega_2d_v3_best.pt /app/models/vega_2d_v3_best.pt

# Environment variables for 2D model
ENV VEGA_ISOTOPE_MODEL=/app/models/vega_2d_v3_best.pt
ENV VEGA_DEVICE=cuda
ENV VEGA_DEFAULT_THRESHOLD=0.5

# Expose port
EXPOSE 8020

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8020/health || exit 1

# Run the API
CMD ["python", "isotope_api.py", "--host", "0.0.0.0", "--port", "8020"]
