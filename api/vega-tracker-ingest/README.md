# Vega Tracker Ingest API

FastAPI service that receives radiation/GPS session uploads from the
Heltec field tracker (or any client speaking the same CSV schema as the
Android app) and persists them to MongoDB.

The Heltec firmware uploads its on-device sessions whenever it sees the
configured Wi-Fi network, so this API is the long-term store-of-record
for field walks.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET  | /health                  | Liveness + Mongo ping |
| GET  | /info                    | Counts, limits, build info |
| GET  | /sessions                | List uploaded sessions (newest first) |
| GET  | /sessions/{session_id}   | Page through samples for one session |
| POST | /ingest/csv              | Upload one session as raw CSV |

### `POST /ingest/csv`

Body is the raw CSV produced by the Heltec firmware (and the Android app):

```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
1777156444000,0.054272,3.605,34.3047238,-84.0843347,524306602024
...
```

Required headers:

| Header | Purpose |
|---|---|
| `X-Session-Id`  | e.g. `boot_328965` (used as Mongo dedup key) |
| `X-Device-Id`   | RadiaCode peer addr w/o colons (optional) |
| `X-Tracker-Id`  | ESP32 chipId/MAC of the uploader (optional) |
| `X-Firmware`    | firmware version string (optional) |

Returns:

```json
{
  "sessionId": "boot_328965",
  "received": 2935,
  "inserted": 2935,
  "duplicates": 0,
  "firstTsMs": 1777156444000,
  "lastTsMs":  1777159377000
}
```

`(sessionId, timestampMs)` is unique, so re-uploading the same session
is idempotent.

## MongoDB Schema

**Collection: `tracker_samples`** (one doc per sample)

```jsonc
{
  "sessionId":  "boot_328965",
  "deviceId":   "524306602024",
  "trackerId":  "esp32-aabbccddeeff",
  "firmware":   "0.1.0",
  "timestampMs": 1777156444000,
  "uSvPerHour":  0.054272,
  "cps":         3.605,
  "latitude":    34.3047238,
  "longitude":  -84.0843347,
  "loc": { "type": "Point", "coordinates": [-84.0843347, 34.3047238] }
}
```

`loc` is a GeoJSON Point that's only present when the row had a real fix.
A 2dsphere index is created over it automatically, so geo queries work.

**Collection: `tracker_sessions`** (one doc per session, upserted)

```jsonc
{
  "sessionId": "boot_328965",
  "deviceId":  "524306602024",
  "trackerId": "esp32-aabbccddeeff",
  "firmware":  "0.1.0",
  "samples":   2935,
  "uploads":   1,
  "firstTsMs": 1777156444000,
  "lastTsMs":  1777159377000,
  "firstIngestMs": 1777160000000,
  "lastIngestMs":  1777160000000,
  "createdMs":     1777160000000
}
```

## Deploy

```powershell
cp .env.example .env
# edit .env with SSH_USER, SSH_HOST, REMOTE_PATH, API_PORT, MONGO_URI, ...
.\deploy.ps1
```

`deploy.ps1` SCPs the project files, runs `docker compose up -d`, and waits
for `/health` to report healthy. Use `-TestOnly` to verify an existing deploy
or `-SkipCopy` to rebuild without re-copying.
