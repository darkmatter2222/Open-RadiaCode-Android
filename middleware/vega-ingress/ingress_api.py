#!/usr/bin/env python3
"""
Vega Ingress - API Gateway / Reverse Proxy
Routes requests to appropriate backend services (TTS, LLM, Isotope Identification)
"""

import os
import logging
import argparse
import asyncio
import json
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Any
import httpx
from fastapi import FastAPI, Request, Response, HTTPException, Depends, Query, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
import uvicorn

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ==============================================================================
# Configuration
# ==============================================================================

# Backend service URLs (internal Docker network or localhost)
TTS_BACKEND = os.getenv("VEGA_TTS_BACKEND", "http://host.docker.internal:8010")
LLM_BACKEND = os.getenv("VEGA_LLM_BACKEND", "http://host.docker.internal:8001")
ISOTOPE_BACKEND = os.getenv("VEGA_ISOTOPE_BACKEND", "http://host.docker.internal:8020")

# Timeout for backend requests (seconds)
BACKEND_TIMEOUT = float(os.getenv("VEGA_BACKEND_TIMEOUT", "120"))

# Logging to disk (ingress request/response capture)
INGRESS_LOG_ENABLED = os.getenv("VEGA_INGRESS_LOG_ENABLED", "1").lower() in {"1", "true", "yes", "on"}
INGRESS_LOG_DIR = Path(os.getenv("VEGA_INGRESS_LOG_DIR", "/var/log/vega-ingress")).resolve()
INGRESS_LOG_RETENTION_HOURS = float(os.getenv("VEGA_INGRESS_LOG_RETENTION_HOURS", "24"))
INGRESS_LOG_CLEANUP_INTERVAL_SECONDS = int(os.getenv("VEGA_INGRESS_LOG_CLEANUP_INTERVAL_SECONDS", "600"))
INGRESS_LOG_MAX_BODY_BYTES = int(os.getenv("VEGA_INGRESS_LOG_MAX_BODY_BYTES", str(1024 * 1024)))

# Optional token required for log retrieval endpoints. If unset, endpoints are open.
INGRESS_LOGS_TOKEN = os.getenv("VEGA_INGRESS_LOGS_TOKEN")

# ==============================================================================
# FastAPI Application
# ==============================================================================

app = FastAPI(
    title="Vega Ingress",
    description="API Gateway for Vega services (TTS, LLM, Isotope Identification)",
    version="1.3.0"
)

# CORS - allow all for now
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# HTTP client for backend requests
http_client = httpx.AsyncClient(timeout=BACKEND_TIMEOUT)

# ============================================================================== 
# Ingress Logging Helpers
# ============================================================================== 

def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _is_json_content_type(content_type: Optional[str]) -> bool:
    if not content_type:
        return False
    ct = content_type.lower()
    return "application/json" in ct or ct.endswith("+json")


def _try_parse_json_bytes(payload: bytes) -> Optional[Any]:
    if not payload:
        return None
    try:
        return json.loads(payload.decode("utf-8"))
    except Exception:
        return None


def _redact_headers(headers: dict[str, str]) -> dict[str, str]:
    redacted = {}
    redact_keys = {
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "proxy-authorization",
    }
    for key, value in headers.items():
        if key.lower() in redact_keys:
            redacted[key] = "[redacted]"
        else:
            redacted[key] = value
    return redacted


async def _write_log_record(record: dict[str, Any]) -> None:
    if not INGRESS_LOG_ENABLED:
        return

    INGRESS_LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_id = record.get("id")
    if not log_id:
        return

    tmp_path = INGRESS_LOG_DIR / f".{log_id}.json.tmp"
    final_path = INGRESS_LOG_DIR / f"{log_id}.json"
    payload = json.dumps(record, ensure_ascii=False, indent=2)

    def _sync_write() -> None:
        tmp_path.write_text(payload, encoding="utf-8")
        tmp_path.replace(final_path)

    await asyncio.to_thread(_sync_write)


async def _cleanup_old_logs_once() -> int:
    if not INGRESS_LOG_ENABLED:
        return 0
    if INGRESS_LOG_RETENTION_HOURS <= 0:
        return 0

    cutoff_epoch = time.time() - (INGRESS_LOG_RETENTION_HOURS * 3600.0)
    deleted = 0

    try:
        if not INGRESS_LOG_DIR.exists():
            return 0

        for path in INGRESS_LOG_DIR.glob("*.json"):
            try:
                if path.stat().st_mtime < cutoff_epoch:
                    path.unlink(missing_ok=True)
                    deleted += 1
            except Exception:
                continue
    except Exception:
        return deleted

    return deleted


async def _cleanup_loop() -> None:
    while True:
        try:
            await _cleanup_old_logs_once()
        except Exception:
            pass
        await asyncio.sleep(max(10, INGRESS_LOG_CLEANUP_INTERVAL_SECONDS))


def _require_logs_token(x_vega_logs_token: Optional[str] = Header(default=None, alias="x-vega-logs-token")) -> None:
    if not INGRESS_LOGS_TOKEN:
        return
    if x_vega_logs_token != INGRESS_LOGS_TOKEN:
        raise HTTPException(status_code=401, detail="Missing or invalid logs token")


async def _capture_response_json_body(response: Response) -> tuple[Optional[Any], Optional[int], bool, Response]:
    """Return (parsed_json, bytes_logged, truncated, response_to_return)."""
    content_type = response.headers.get("content-type")
    if not _is_json_content_type(content_type):
        return None, None, False, response

    # Fast path for non-streaming responses.
    body_bytes: Optional[bytes] = getattr(response, "body", None)
    if body_bytes is not None and not isinstance(response, StreamingResponse):
        truncated = len(body_bytes) > INGRESS_LOG_MAX_BODY_BYTES
        body_for_log = body_bytes[:INGRESS_LOG_MAX_BODY_BYTES]
        return _try_parse_json_bytes(body_for_log), len(body_for_log), truncated, response

    # StreamingResponse: buffer to return the same content.
    full_body = b""
    async for chunk in response.body_iterator:
        full_body += chunk

    # Rebuild response; drop content-length to avoid mismatch.
    headers = dict(response.headers)
    headers.pop("content-length", None)
    rebuilt = Response(
        content=full_body,
        status_code=response.status_code,
        headers=headers,
        media_type=response.media_type,
        background=getattr(response, "background", None),
    )

    truncated = len(full_body) > INGRESS_LOG_MAX_BODY_BYTES
    body_for_log = full_body[:INGRESS_LOG_MAX_BODY_BYTES]
    return _try_parse_json_bytes(body_for_log), len(body_for_log), truncated, rebuilt


# ============================================================================== 
# Ingress Logging Middleware
# ============================================================================== 


@app.middleware("http")
async def ingress_disk_logger(request: Request, call_next):
    if not INGRESS_LOG_ENABLED:
        return await call_next(request)

    request_id = request.headers.get("x-request-id") or str(uuid.uuid4())
    start = time.perf_counter()

    request_headers = _redact_headers(dict(request.headers))
    request_content_type = request.headers.get("content-type")

    request_json: Optional[Any] = None
    request_body_len: Optional[int] = None
    request_truncated = False
    if _is_json_content_type(request_content_type):
        body = await request.body()
        request_body_len = len(body)
        request_truncated = request_body_len > INGRESS_LOG_MAX_BODY_BYTES
        request_json = _try_parse_json_bytes(body[:INGRESS_LOG_MAX_BODY_BYTES])

    response = await call_next(request)
    duration_ms = (time.perf_counter() - start) * 1000.0

    # Ensure request ID is visible to clients.
    response.headers["x-request-id"] = request_id

    response_json, response_bytes_logged, response_truncated, response_to_return = await _capture_response_json_body(response)
    response_content_type = response.headers.get("content-type")

    record = {
        "id": request_id,
        "ts_utc": _utc_now_iso(),
        "duration_ms": round(duration_ms, 3),
        "request": {
            "method": request.method,
            "path": request.url.path,
            "query": str(request.url.query) if request.url.query else "",
            "client": getattr(request.client, "host", None),
            "headers": request_headers,
            "content_type": request_content_type,
            "json": request_json,
            "body_bytes": request_body_len,
            "body_truncated": request_truncated,
        },
        "response": {
            "status_code": response.status_code,
            "content_type": response_content_type,
            "headers": _redact_headers(dict(response.headers)),
            "json": response_json,
            "body_bytes_logged": response_bytes_logged,
            "body_truncated": response_truncated,
        },
    }

    try:
        await _write_log_record(record)
    except Exception as e:
        logger.warning(f"Failed to write ingress log: {e}")

    return response_to_return


@app.on_event("startup")
async def _on_startup() -> None:
    if INGRESS_LOG_ENABLED:
        INGRESS_LOG_DIR.mkdir(parents=True, exist_ok=True)
        app.state._ingress_cleanup_task = asyncio.create_task(_cleanup_loop())


@app.on_event("shutdown")
async def _on_shutdown() -> None:
    try:
        task = getattr(app.state, "_ingress_cleanup_task", None)
        if task:
            task.cancel()
    except Exception:
        pass
    try:
        await http_client.aclose()
    except Exception:
        pass

# ==============================================================================
# Health & Info
# ==============================================================================

@app.get("/health")
async def health_check():
    """Health check - also checks backend connectivity."""
    backends = {}
    
    # Check TTS
    try:
        resp = await http_client.get(f"{TTS_BACKEND}/health", timeout=5)
        backends["tts"] = resp.json() if resp.status_code == 200 else {"status": "error", "code": resp.status_code}
    except Exception as e:
        backends["tts"] = {"status": "unreachable", "error": str(e)}
    
    # Check LLM
    try:
        resp = await http_client.get(f"{LLM_BACKEND}/health", timeout=5)
        backends["llm"] = resp.json() if resp.status_code == 200 else {"status": "error", "code": resp.status_code}
    except Exception as e:
        backends["llm"] = {"status": "unreachable", "error": str(e)}
    
    # Check Isotope Identification
    try:
        resp = await http_client.get(f"{ISOTOPE_BACKEND}/health", timeout=5)
        backends["isotope"] = resp.json() if resp.status_code == 200 else {"status": "error", "code": resp.status_code}
    except Exception as e:
        backends["isotope"] = {"status": "unreachable", "error": str(e)}
    
    all_healthy = all(
        b.get("status") in ["healthy", "ok"] 
        for b in backends.values()
    )
    
    return {
        "status": "healthy" if all_healthy else "degraded",
        "backends": backends
    }

@app.get("/info")
async def get_info():
    """Get information about available services."""
    return {
        "service": "vega-ingress",
        "version": "1.3.0",
        "routes": {
            "/tts/*": "Text-to-Speech service",
            "/llm/*": "Language Model service",
            "/isotope/*": "Isotope Identification service (v2.0 - 2D model)",
            "/api/tts/*": "Alias for TTS",
            "/api/llm/*": "Alias for LLM",
            "/api/isotope/*": "Alias for Isotope Identification",
            "/logs": "List recent ingress request logs",
            "/logs/{id}": "Retrieve a specific ingress request log by ID",
        },
        "backends": {
            "tts": TTS_BACKEND,
            "llm": LLM_BACKEND,
            "isotope": ISOTOPE_BACKEND
        },
        "logging": {
            "enabled": INGRESS_LOG_ENABLED,
            "log_dir": str(INGRESS_LOG_DIR),
            "retention_hours": INGRESS_LOG_RETENTION_HOURS,
            "max_body_bytes": INGRESS_LOG_MAX_BODY_BYTES,
            "logs_token_required": bool(INGRESS_LOGS_TOKEN),
        },
    }


# ============================================================================== 
# Logs API
# ============================================================================== 


@app.get("/logs")
async def list_logs(
    limit: int = Query(default=100, ge=1, le=1000),
    _: None = Depends(_require_logs_token),
):
    """List recent ingress logs (metadata only)."""
    if not INGRESS_LOG_ENABLED:
        raise HTTPException(status_code=404, detail="Ingress logging is disabled")

    if not INGRESS_LOG_DIR.exists():
        return {"count": 0, "logs": []}

    # Sort newest-first by mtime.
    paths = sorted(INGRESS_LOG_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    logs = []
    for path in paths[:limit]:
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
            logs.append(
                {
                    "id": record.get("id"),
                    "ts_utc": record.get("ts_utc"),
                    "method": record.get("request", {}).get("method"),
                    "path": record.get("request", {}).get("path"),
                    "status_code": record.get("response", {}).get("status_code"),
                    "duration_ms": record.get("duration_ms"),
                }
            )
        except Exception:
            continue

    return {"count": len(logs), "logs": logs}


@app.get("/logs/{log_id}")
async def get_log(
    log_id: str,
    _: None = Depends(_require_logs_token),
):
    """Retrieve a single ingress log record by ID."""
    if not INGRESS_LOG_ENABLED:
        raise HTTPException(status_code=404, detail="Ingress logging is disabled")

    # Basic sanity check; IDs are expected to be UUIDs.
    try:
        uuid.UUID(log_id)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid log ID")

    path = INGRESS_LOG_DIR / f"{log_id}.json"
    if not path.exists():
        raise HTTPException(status_code=404, detail="Log not found")

    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        raise HTTPException(status_code=500, detail="Failed to read log")

    return JSONResponse(record)

# ==============================================================================
# Proxy Helper
# ==============================================================================

async def proxy_request(
    request: Request,
    backend_url: str,
    path: str
) -> Response:
    """Proxy a request to a backend service."""
    
    # Build target URL
    target_url = f"{backend_url}/{path}"
    if request.query_params:
        target_url += f"?{request.query_params}"
    
    # Get request body
    body = await request.body()
    
    # Forward headers (excluding hop-by-hop headers)
    headers = {}
    for key, value in request.headers.items():
        if key.lower() not in ["host", "connection", "keep-alive", "transfer-encoding"]:
            headers[key] = value
    
    try:
        # Make request to backend
        response = await http_client.request(
            method=request.method,
            url=target_url,
            content=body,
            headers=headers,
        )
        
        # Build response headers
        response_headers = {}
        for key, value in response.headers.items():
            if key.lower() not in ["transfer-encoding", "connection", "content-encoding"]:
                response_headers[key] = value
        
        return Response(
            content=response.content,
            status_code=response.status_code,
            headers=response_headers,
            media_type=response.headers.get("content-type")
        )
        
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="Backend timeout")
    except httpx.ConnectError:
        raise HTTPException(status_code=502, detail="Backend unavailable")
    except Exception as e:
        logger.error(f"Proxy error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ==============================================================================
# TTS Routes
# ==============================================================================

@app.api_route("/tts/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_tts(request: Request, path: str):
    """Proxy requests to TTS service."""
    return await proxy_request(request, TTS_BACKEND, path)

@app.api_route("/api/tts/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_api_tts(request: Request, path: str):
    """Alias: Proxy requests to TTS service."""
    return await proxy_request(request, TTS_BACKEND, path)

# ==============================================================================
# LLM Routes
# ==============================================================================

@app.api_route("/llm/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_llm(request: Request, path: str):
    """Proxy requests to LLM service."""
    return await proxy_request(request, LLM_BACKEND, path)

@app.api_route("/api/llm/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_api_llm(request: Request, path: str):
    """Alias: Proxy requests to LLM service."""
    return await proxy_request(request, LLM_BACKEND, path)

# ==============================================================================
# Convenience Routes (direct access to common endpoints)
# ==============================================================================

@app.post("/synthesize")
async def synthesize(request: Request):
    """Direct route to TTS synthesize."""
    return await proxy_request(request, TTS_BACKEND, "synthesize")

@app.post("/synthesize/b64")
async def synthesize_b64(request: Request):
    """Direct route to TTS synthesize base64."""
    return await proxy_request(request, TTS_BACKEND, "synthesize/b64")

@app.post("/chat")
async def chat(request: Request):
    """Direct route to LLM chat."""
    return await proxy_request(request, LLM_BACKEND, "chat")

@app.post("/generate")
async def generate(request: Request):
    """Direct route to LLM generate."""
    return await proxy_request(request, LLM_BACKEND, "generate")

@app.post("/spectrogram")
async def spectrogram(request: Request):
    """
    Direct route to VEGA spectrogram analysis.
    
    VEGA Radiological Assistant - analyzes gamma radiation data
    and provides expert guidance using the VEGA persona.
    """
    return await proxy_request(request, LLM_BACKEND, "spectrogram")

# ==============================================================================
# Isotope Identification Routes
# ==============================================================================

@app.api_route("/isotope/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_isotope(request: Request, path: str):
    """Proxy requests to Isotope Identification service."""
    return await proxy_request(request, ISOTOPE_BACKEND, path)

@app.api_route("/api/isotope/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy_api_isotope(request: Request, path: str):
    """Alias: Proxy requests to Isotope Identification service."""
    return await proxy_request(request, ISOTOPE_BACKEND, path)

# Direct convenience routes for isotope identification
@app.post("/identify")
async def identify(request: Request):
    """
    Direct route to isotope identification (2D model).
    
    Accepts a 2D gamma spectrum (60 time intervals × 1023 channels) and returns
    identified isotopes with probabilities and estimated activities.
    
    API v2.0: Input shape is now (60, 1023) for improved accuracy.
    """
    return await proxy_request(request, ISOTOPE_BACKEND, "identify")

@app.post("/identify/1d")
async def identify_1d(request: Request):
    """
    Direct route to isotope identification (legacy 1D support).
    
    Accepts a 1D gamma spectrum (1023 channels) which is automatically
    expanded to 2D by the backend.
    """
    return await proxy_request(request, ISOTOPE_BACKEND, "identify/1d")

@app.post("/identify/b64")
async def identify_b64(request: Request):
    """Direct route to isotope identification with base64-encoded numpy array."""
    return await proxy_request(request, ISOTOPE_BACKEND, "identify/b64")

@app.post("/identify/batch")
async def identify_batch(request: Request):
    """Direct route to batch isotope identification."""
    return await proxy_request(request, ISOTOPE_BACKEND, "identify/batch")

@app.get("/isotopes")
async def list_isotopes(request: Request):
    """Direct route to list all supported isotopes."""
    return await proxy_request(request, ISOTOPE_BACKEND, "isotopes")

# ==============================================================================
# Main
# ==============================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Vega Ingress API Gateway")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind to")
    args = parser.parse_args()
    
    uvicorn.run(app, host=args.host, port=args.port)
