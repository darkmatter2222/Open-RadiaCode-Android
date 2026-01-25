# ==============================================================================
# Vega Ingress - Docker Image
# ==============================================================================
# Lightweight API Gateway / Reverse Proxy for Vega services

FROM python:3.11-slim AS base

# Prevent interactive prompts
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
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

COPY ingress_api.py .

# Environment - backends are on host network
ENV VEGA_TTS_BACKEND=http://host.docker.internal:8000
ENV VEGA_LLM_BACKEND=http://host.docker.internal:8001
ENV VEGA_BACKEND_TIMEOUT=120

# Port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run
CMD ["python", "ingress_api.py", "--host", "0.0.0.0", "--port", "8080"]
