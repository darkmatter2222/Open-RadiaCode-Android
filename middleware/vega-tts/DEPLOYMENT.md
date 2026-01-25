# Vega TTS API - Deployment Guide

## Quick Start (Local Testing)

```bash
# Test the API locally first
cd /path/to/vega
.\.venv\Scripts\Activate.ps1  # Windows
# source .venv/bin/activate   # Linux

pip install fastapi uvicorn[standard]
python api.py --port 8000
```

Test with curl:
```bash
curl -X POST http://localhost:8000/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello, this is a test."}' \
  --output test.wav
```

---

## Docker Deployment

### Prerequisites on your Linux server:

1. **Check Docker is installed:**
   ```bash
   docker --version
   docker compose version
   ```

2. **Check NVIDIA Docker runtime (for GPU):**
   ```bash
   nvidia-smi                    # Should show your GPU
   docker run --rm --gpus all nvidia/cuda:12.1-base nvidia-smi
   ```

   If nvidia-docker isn't working:
   ```bash
   # Install NVIDIA Container Toolkit
   distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
   curl -s -L https://nvidia.github.io/nvidia-docker/gpgkey | sudo apt-key add -
   curl -s -L https://nvidia.github.io/nvidia-docker/$distribution/nvidia-docker.list | sudo tee /etc/apt/sources.list.d/nvidia-docker.list
   sudo apt-get update
   sudo apt-get install -y nvidia-container-toolkit
   sudo systemctl restart docker
   ```

---

### Deploy to Server

**Option A: Build on server**

1. Copy the project to your server:
   ```bash
   # From your Windows machine
   scp -r C:\Users\ryans\source\repos\vega user@your-server:/home/user/vega
   ```

2. SSH into your server and build:
   ```bash
   ssh user@your-server
   cd /home/user/vega
   docker compose up -d --build
   ```

**Option B: Build locally, push to registry**

1. Build and tag:
   ```bash
   docker build -t your-registry/vega-tts-api:latest .
   docker push your-registry/vega-tts-api:latest
   ```

2. On server, pull and run:
   ```bash
   docker pull your-registry/vega-tts-api:latest
   docker compose up -d
   ```

---

### Verify Deployment

```bash
# Check container is running
docker ps

# Check logs
docker logs vega-api -f

# Test health endpoint
curl http://localhost:8000/health

# Test synthesis
curl -X POST http://localhost:8000/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Vega is online."}' \
  --output test.wav
```

---

## Port Forwarding & DNS

### Router Port Forward:
1. Log into your router admin panel
2. Forward external port (e.g., 8080 or 443) → internal IP:8000
3. Note your public IP

### DNS Setup:
1. Go to your DNS provider (Cloudflare, etc.)
2. Add an A record: `vega.yourdomain.com` → your public IP
3. Or use Dynamic DNS if your IP changes

### With HTTPS (recommended):
Use a reverse proxy like Caddy or nginx:

```bash
# Install Caddy
sudo apt install caddy

# /etc/caddy/Caddyfile
vega.yourdomain.com {
    reverse_proxy localhost:8000
}

sudo systemctl restart caddy
```
Caddy auto-provisions SSL certificates.

---

## API Usage Examples

### Python Client:
```python
import requests

response = requests.post(
    "https://vega.yourdomain.com/synthesize",
    json={"text": "Hello from Vega."},
)
with open("output.wav", "wb") as f:
    f.write(response.content)
```

### JavaScript/Node:
```javascript
const response = await fetch("https://vega.yourdomain.com/synthesize", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ text: "Hello from Vega." }),
});
const audioBlob = await response.blob();
```

### curl:
```bash
curl -X POST https://vega.yourdomain.com/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Radiation levels nominal."}' \
  -o output.wav
```

---

## Troubleshooting

**Container won't start:**
```bash
docker logs vega-api
```

**GPU not detected:**
```bash
docker run --rm --gpus all nvidia/cuda:12.1-base nvidia-smi
```

**Model not loading:**
- Ensure `models/vega_tuned/conds.pt` exists
- Check volume mounts in docker-compose.yml

**Out of GPU memory:**
- The model needs ~4-6GB VRAM
- Check with: `nvidia-smi`
