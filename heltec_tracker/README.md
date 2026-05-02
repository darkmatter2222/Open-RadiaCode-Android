# Heltec Tracker — RadiaCode Field Logger

Standalone radiation logging system built around the **Heltec HTIT-Tracker V2** (ESP32-S3).
No phone required. The device connects to a RadiaCode dosimeter via BLE, pairs each reading
with a GPS fix, writes to SD card, and auto-uploads to a self-hosted API when on Wi-Fi.

---

## What This Repo Contains

| Directory | What it is |
|-----------|-----------|
| `src/` | ESP32-S3 firmware (C++, PlatformIO) |
| `scripts/` | Python dev-tools: serial console, data download, map plotting |
| `api/vega-tracker-ingest/` | FastAPI ingest service + MongoDB (Docker) |
| `web/vega-tracker-viewer/` | React session map viewer (Docker/nginx) |

---

## System Overview

```
RadiaCode dosimeter
       | BLE
       v
Heltec HTIT-Tracker V2
- polls dose rate + CPS at 1 Hz
- pairs with GPS fix (UC6580 GNSS)
- writes CSV to SD card
- uploads over Wi-Fi when available
       | HTTP POST /ingest/csv
       v
vega-tracker-ingest (FastAPI)    port 8030
- validates timestamps (reject pre-2020)
- writes to MongoDB (radiacode DB)
- exposes session list + detail endpoints
       |
       v
vega-tracker-viewer (React/nginx) port 8031
- interactive session map (Leaflet)
- dose rate heatmap, GPS track
- session list with duration + sample count
```

---

## Quick Start

### 1. Firmware

Install PlatformIO, then:

```powershell
# Copy secrets template and fill in your credentials
cp src\secrets.h.example src\secrets.h
# Edit src\secrets.h with your WiFi SSID/password and ingest URL

# Build
pio run -e heltec_tracker_v2

# Flash (device appears as COM4 on this machine)
pio run -e heltec_tracker_v2 -t upload --upload-port COM4

# Watch serial output
python scripts\drive.py listen 30
```

> **IMPORTANT:** Always use `-e heltec_tracker_v2`. The `v1_2` env inverts the display,
> producing a solid white screen on V2 hardware.

### 2. Ingest API

```powershell
cd api\vega-tracker-ingest

# Copy and fill in credentials (see AGENTS.md for exact values)
cp .env.example .env
# Edit .env

# Deploy to server
.\deploy.ps1

# Test
curl http://192.168.86.48:8030/health
curl http://192.168.86.48:8030/info
```

### 3. Web Viewer

```powershell
cd web\vega-tracker-viewer

# Copy and fill in credentials
cp .env.example .env
# Edit .env

# Deploy to server
.\deploy.ps1
# Then open: http://192.168.86.48:8031/
```

---

## Hardware

| Component | Part | Notes |
|-----------|------|-------|
| Tracker board | Heltec HTIT-Tracker V2 | ESP32-S3 + UC6580 GNSS + ST7735 TFT |
| SD card module | HiLetgo HW-125 | VCC must be 5V, NOT 3V3 |
| SD card | 16 GB Class 10 FAT32 | 70 B/row; years of capacity at 1 Hz |
| Dosimeter | RadiaCode 102 | BLE MAC: 52:43:06:60:20:24 |

---

## Server

- IP: `192.168.86.48`
- SSH: `darkmatter2222@192.168.86.48` (key auth)
- MongoDB on host at port 27017 (auth: see AGENTS.md)
- API: `http://192.168.86.48:8030`
- Viewer: `http://192.168.86.48:8031`

---

## Firmware Version

Current: **v0.2.0**

What changed in v0.2.0:
- Double long-press required to stop recording (prevents accidental stops from vibration)
- `[REC] START` / `[REC] STOP` serial log events
- GPS UTC anchor logging

---

## Button Reference

| Press | Screen | Action |
|-------|--------|--------|
| Short | any | Cycle to next screen |
| Long | STATS | Force BLE rescan |
| Long | STORAGE (not recording) | Start recording |
| Long | STORAGE (recording) | Show confirmation prompt |
| Long again (within 5s) | STORAGE (confirming) | Stop recording |
| Short | STORAGE (confirming) | Cancel stop, keep recording |

---

## CSV Schema

```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
1746114660123,0.142,12.0,47.6062,-122.3321,5243066020F4
```

---

## Secrets

Two secret files are gitignored — create them from the `.example` templates:

| File | Template | Contains |
|------|----------|---------|
| `src/secrets.h` | `src/secrets.h.example` | WiFi credentials, ingest URL |
| `api/vega-tracker-ingest/.env` | `api/vega-tracker-ingest/.env.example` | SSH creds, MongoDB URI |
| `web/vega-tracker-viewer/.env` | `web/vega-tracker-viewer/.env.example` | SSH creds, API base URL |

See `AGENTS.md` for the exact credential values.

---

## Detailed Documentation

See [AGENTS.md](AGENTS.md) for:
- Full hardware wiring tables and GPIO map
- All subsystem internals (BLE, GPS, session store, TFT, uploader)
- Serial console command reference
- API endpoint reference
- MongoDB admin commands
- All lessons learned (SD power, GPS timestamp bugs, BT5 flags, etc.)
