"""Vega Tracker Ingest API.

Accepts radiation/GPS session uploads from the Heltec field tracker
(or any client that produces the same CSV schema as the Android app)
and writes them into MongoDB.

Wire formats
------------

POST /ingest/csv
    Content-Type: text/csv (or application/octet-stream)
    Headers:
        X-Session-Id    required, e.g. "boot_328965" or "20260426_104210"
        X-Device-Id     optional, raw RadiaCode peer addr (no colons)
        X-Tracker-Id    optional, ESP32 chipId / MAC of the uploader
        X-Firmware      optional, firmware version string
    Body:
        timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
        ...rows...

    The first row may be a header (auto-detected by the literal
    "timestampMs" prefix) and is skipped.

Endpoints
---------
GET  /health               liveness + mongo ping
GET  /info                 collection counts, sample-rate stats, build info
GET  /sessions             list (sessionId, deviceId, samples, first/last ts)
POST /ingest/csv           upload one session (see above)
"""
from __future__ import annotations

import csv
import io
import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Any

import pymongo
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pymongo import MongoClient
from pymongo.errors import BulkWriteError, PyMongoError

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("tracker-ingest")

MONGO_URI         = os.getenv("MONGO_URI", "mongodb://mongo:27017")
MONGO_DB          = os.getenv("MONGO_DB", "radiacode")
SAMPLES_COLL      = os.getenv("MONGO_SAMPLES_COLLECTION", "tracker_samples")
SESSIONS_COLL     = os.getenv("MONGO_SESSIONS_COLLECTION", "tracker_sessions")
API_VERSION       = "0.1.0"
MAX_BODY_BYTES    = int(os.getenv("MAX_BODY_BYTES", str(8 * 1024 * 1024)))   # 8 MB
INGEST_BATCH_SIZE = int(os.getenv("INGEST_BATCH_SIZE", "1000"))


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("connecting to mongo at %s (db=%s)", MONGO_URI, MONGO_DB)
    client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=5000)
    # Force connect now so startup fails fast if mongo is unreachable.
    client.admin.command("ping")
    db = client[MONGO_DB]
    samples = db[SAMPLES_COLL]
    sessions = db[SESSIONS_COLL]

    # Indexes (safe to call on every boot).
    samples.create_index([("sessionId", 1), ("timestampMs", 1)], name="session_ts")
    samples.create_index([("deviceId", 1), ("timestampMs", -1)], name="device_ts")
    samples.create_index([("loc", "2dsphere")], name="loc_2dsphere",
                         partialFilterExpression={"loc": {"$type": "object"}})
    # Per-row idempotency: same session+timestamp is the same sample.
    samples.create_index([("sessionId", 1), ("timestampMs", 1)],
                         name="session_ts_unique", unique=True)
    sessions.create_index([("sessionId", 1)], name="session_id_unique", unique=True)

    app.state.mongo  = client
    app.state.db     = db
    app.state.samples  = samples
    app.state.sessions = sessions
    log.info("mongo ready; collections=%s,%s", SAMPLES_COLL, SESSIONS_COLL)
    try:
        yield
    finally:
        client.close()


app = FastAPI(title="Vega Tracker Ingest", version=API_VERSION, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_credentials=False,
    allow_methods=["*"], allow_headers=["*"],
)


# ---------- helpers ---------------------------------------------------------

def _safe_float(v: str) -> float | None:
    if v is None or v == "":
        return None
    try:
        return float(v)
    except ValueError:
        return None


def _safe_int(v: str) -> int | None:
    if v is None or v == "":
        return None
    try:
        return int(v)
    except ValueError:
        try:
            f = float(v)
            return int(f)
        except ValueError:
            return None


def _parse_csv(body: str, session_id: str, header_device_id: str | None,
               tracker_id: str | None, firmware: str | None) -> list[dict[str, Any]]:
    """Parse a CSV upload into Mongo-ready docs.

    Schema (per row):
        sessionId, deviceId, trackerId, firmware,
        timestampMs (int64), uSvPerHour, cps,
        latitude, longitude, loc {type:Point, coordinates:[lng,lat]}  (only if non-zero)
    """
    out: list[dict[str, Any]] = []
    rdr = csv.reader(io.StringIO(body))
    for row in rdr:
        if not row:
            continue
        # Skip header row.
        if row[0].strip().lower() == "timestampms":
            continue
        # Pad to 6 cols defensively.
        while len(row) < 6:
            row.append("")
        ts   = _safe_int(row[0])
        usv  = _safe_float(row[1])
        cps  = _safe_float(row[2])
        lat  = _safe_float(row[3])
        lng  = _safe_float(row[4])
        dev  = row[5].strip() or (header_device_id or None)

        if ts is None:
            continue
        doc: dict[str, Any] = {
            "sessionId":  session_id,
            "deviceId":   dev,
            "trackerId":  tracker_id,
            "firmware":   firmware,
            "timestampMs": ts,
            "uSvPerHour": usv,
            "cps":        cps,
            "latitude":   lat,
            "longitude":  lng,
        }
        if lat is not None and lng is not None and not (lat == 0.0 and lng == 0.0):
            doc["loc"] = {"type": "Point", "coordinates": [lng, lat]}
        out.append(doc)
    return out


def _bulk_insert(coll, docs: list[dict[str, Any]]) -> tuple[int, int]:
    """Insert in batches with ordered=False so duplicates don't abort.
    Returns (inserted, duplicate_skipped)."""
    inserted = 0
    duplicates = 0
    for i in range(0, len(docs), INGEST_BATCH_SIZE):
        batch = docs[i:i + INGEST_BATCH_SIZE]
        try:
            res = coll.insert_many(batch, ordered=False)
            inserted += len(res.inserted_ids)
        except BulkWriteError as bwe:
            wr_err = bwe.details.get("writeErrors", [])
            for e in wr_err:
                if e.get("code") == 11000:  # duplicate key
                    duplicates += 1
                else:
                    log.warning("bulk-write error code=%s: %s", e.get("code"), e.get("errmsg"))
            inserted += bwe.details.get("nInserted", len(batch) - len(wr_err))
    return inserted, duplicates


# ---------- routes ----------------------------------------------------------

@app.get("/health")
def health():
    try:
        app.state.mongo.admin.command("ping")
        mongo_ok = True
    except PyMongoError as e:
        log.error("mongo ping failed: %s", e)
        mongo_ok = False
    return {
        "status":   "healthy" if mongo_ok else "degraded",
        "mongo":    mongo_ok,
        "version":  API_VERSION,
    }


@app.get("/info")
def info():
    samples = app.state.samples
    sessions = app.state.sessions
    return {
        "version":  API_VERSION,
        "mongo": {
            "uri":       MONGO_URI,
            "db":        MONGO_DB,
            "samples":   samples.estimated_document_count(),
            "sessions":  sessions.estimated_document_count(),
            "collections": [SAMPLES_COLL, SESSIONS_COLL],
        },
        "limits": {
            "max_body_bytes":    MAX_BODY_BYTES,
            "ingest_batch_size": INGEST_BATCH_SIZE,
        },
    }


@app.get("/sessions")
def list_sessions(limit: int = 200):
    """List ingested sessions, newest first."""
    cur = app.state.sessions.find({}, sort=[("lastIngestMs", -1)], limit=limit)
    return [
        {
            "sessionId":     d.get("sessionId"),
            "deviceId":      d.get("deviceId"),
            "trackerId":     d.get("trackerId"),
            "firmware":      d.get("firmware"),
            "samples":       d.get("samples"),
            "firstTsMs":     d.get("firstTsMs"),
            "lastTsMs":      d.get("lastTsMs"),
            "firstIngestMs": d.get("firstIngestMs"),
            "lastIngestMs":  d.get("lastIngestMs"),
            "uploads":       d.get("uploads", 1),
        }
        for d in cur
    ]


@app.get("/sessions/{session_id}")
def session_detail(session_id: str, limit: int = 1000, skip: int = 0):
    """Return raw samples for a session (paged)."""
    cur = (app.state.samples
           .find({"sessionId": session_id}, sort=[("timestampMs", 1)])
           .skip(skip).limit(limit))
    rows = []
    for d in cur:
        d["_id"] = str(d["_id"])
        rows.append(d)
    return {"sessionId": session_id, "skip": skip, "limit": limit, "rows": rows}


@app.post("/ingest/csv")
async def ingest_csv(
    request: Request,
    x_session_id: str = Header(..., alias="X-Session-Id"),
    x_device_id:  str | None = Header(None, alias="X-Device-Id"),
    x_tracker_id: str | None = Header(None, alias="X-Tracker-Id"),
    x_firmware:   str | None = Header(None, alias="X-Firmware"),
):
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="empty body")
    if len(body) > MAX_BODY_BYTES:
        raise HTTPException(status_code=413, detail=f"body > {MAX_BODY_BYTES} bytes")

    text = body.decode("utf-8", errors="replace")
    docs = _parse_csv(text, x_session_id, x_device_id, x_tracker_id, x_firmware)
    if not docs:
        raise HTTPException(status_code=400, detail="no parseable rows")

    inserted, duplicates = _bulk_insert(app.state.samples, docs)

    now_ms = int(time.time() * 1000)
    first_ts = min(d["timestampMs"] for d in docs)
    last_ts  = max(d["timestampMs"] for d in docs)

    # Upsert session metadata.
    app.state.sessions.update_one(
        {"sessionId": x_session_id},
        {
            "$set": {
                "sessionId":     x_session_id,
                "deviceId":      x_device_id,
                "trackerId":     x_tracker_id,
                "firmware":      x_firmware,
                "lastIngestMs":  now_ms,
            },
            "$min": {"firstTsMs": first_ts, "firstIngestMs": now_ms},
            "$max": {"lastTsMs":  last_ts},
            "$inc": {"samples":  inserted, "uploads": 1},
            "$setOnInsert": {"createdMs": now_ms},
        },
        upsert=True,
    )

    log.info("ingest sessionId=%s rows=%d inserted=%d dup=%d",
             x_session_id, len(docs), inserted, duplicates)
    return JSONResponse({
        "sessionId":   x_session_id,
        "received":    len(docs),
        "inserted":    inserted,
        "duplicates":  duplicates,
        "firstTsMs":   first_ts,
        "lastTsMs":    last_ts,
    })
