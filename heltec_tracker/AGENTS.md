# Heltec Tracker — Agent Operating Instructions

Full technical reference for AI agents working in this repo.
Read this **before** making any changes.

This repo is self-contained: firmware, ingest API, and web viewer all live here.

---

## Repository Structure

```
heltec-tracker/                   <- repo root (was heltec_tracker/ in monorepo)
├── .gitignore
├── AGENTS.md                     # this file
├── README.md                     # quick-start for humans
├── platformio.ini                # PlatformIO build config
├── partitions_tracker_v2.csv     # custom flash partition table
├── src/                          # ESP32-S3 firmware (C++)
│   ├── main.cpp                  # setup() / loop(), serial REPL
│   ├── config.h                  # ALL pin assignments + feature flags
│   ├── secrets.h                 # gitignored — WiFi/URL credentials
│   ├── secrets.h.example         # template for secrets.h
│   ├── button.{h,cpp}            # debounced GPIO 0 + long-press engine
│   ├── gps_module.{h,cpp}        # UC6580 GNSS: auto-baud, bestEpochMs()
│   ├── radiacode.{h,cpp}         # NimBLE central + RadiaCode protocol
│   ├── session_store.{h,cpp}     # SD/SdFat CSV writer + LittleFS fallback
│   ├── ui.{h,cpp}                # ST7735 TFT screens + button state machine
│   └── wifi_uploader.{h,cpp}     # FreeRTOS uploader task (core 0)
├── scripts/                      # Python dev-tools (serial, data, mapping)
│   ├── drive.py                  # serial console wrapper: cmd/listen/REPL
│   ├── download_sessions.py      # DUMPALL -> CSV files + optional wipe
│   ├── plot_session_map.py       # interactive Folium map from CSV
│   ├── overnight_watch.py        # log-tailing watchdog with reconnect
│   └── capture_boot.py           # trigger ESP32 reset via DTR/RTS and capture boot log
├── api/
│   └── vega-tracker-ingest/      # FastAPI ingest service (Docker)
│       ├── .env                  # real credentials — gitignored
│       ├── .env.example          # template
│       ├── tracker_ingest_api.py # main FastAPI app
│       ├── deploy.ps1            # deploy to remote server over SSH
│       ├── docker-compose.yml
│       ├── Dockerfile
│       ├── requirements.txt
│       └── client_sample.py
└── web/
    └── vega-tracker-viewer/      # React + Vite session map viewer (Docker/nginx)
        ├── .env                  # real credentials — gitignored
        ├── .env.example          # template
        ├── src/                  # React source
        ├── deploy.ps1            # deploy to remote server over SSH
        ├── docker-compose.yml
        ├── Dockerfile
        ├── nginx.conf
        └── package.json
```

---

## Server & Credentials

### Target server

| Item                  | Value                               |
|-----------------------|-------------------------------------|
| Server IP             | 192.168.86.48                       |
| SSH user              | darkmatter2222                      |
| SSH key               | ~/.ssh/id_rsa                       |
| API port              | 8030 (vega-tracker-ingest)          |
| Viewer port           | 8031 (vega-tracker-viewer)          |
| MongoDB port          | 27017 (host-native, not in Docker)  |
| MongoDB auth source   | admin                               |

### MongoDB connection

```
mongodb://ryan:Welcome123%21@host.docker.internal:27017/?authSource=admin
```

- User: `ryan`
- Password: `Welcome123!` (URL-encoded as `Welcome123%21`)
- Database: `radiacode`
- Collections: `tracker_samples`, `tracker_sessions`

Credentials are stored in:
- `api/vega-tracker-ingest/.env` (for the ingest API Docker container)
- Credentials are gitignored — copy from `.env.example` on a new machine.

### The .env files (gitignored, but committed credentials are below for setup)

`api/vega-tracker-ingest/.env`:
```
SSH_USER=darkmatter2222
SSH_HOST=192.168.86.48
SSH_KEY_PATH=~/.ssh/id_rsa
REMOTE_PATH=/home/darkmatter2222/vega-tracker-ingest
API_PORT=8030
MONGO_URI=mongodb://ryan:Welcome123%21@host.docker.internal:27017/?authSource=admin
MONGO_DB=radiacode
MONGO_SAMPLES_COLLECTION=tracker_samples
MONGO_SESSIONS_COLLECTION=tracker_sessions
```

`web/vega-tracker-viewer/.env`:
```
SSH_USER=darkmatter2222
SSH_HOST=192.168.86.48
SSH_KEY_PATH=~/.ssh/id_rsa
REMOTE_PATH=/home/darkmatter2222/vega-tracker-viewer
WEB_PORT=8031
API_BASE=http://192.168.86.48:8030
```

---

## Hardware

### Board: Heltec HTIT-Tracker V2

- **MCU**: ESP32-S3FN8 + SX1262 LoRa + UC6580 GNSS + ST7735 0.96" TFT (160x80)
- **PlatformIO env**: always `heltec_tracker_v2` — NEVER `heltec_tracker_v1_2`
  (V1.2 env inverts the display; produces a solid white screen on V2 hardware)
- **Panel offsets**: `XSTART=0 / YSTART=24`, `invertDisplay(false)`
- **Upload port**: COM4 (this dev machine); auto-detected by PlatformIO
- **Flash partition**: `partitions_tracker_v2.csv`

### GPIO assignments (do NOT reuse these)

| Pin(s)         | Function                                  |
|----------------|-------------------------------------------|
| 38,39,40,41,42 | TFT CS/DC/RST/SCLK/MOSI                  |
| 21             | TFT backlight (HIGH = on)                 |
| 33, 34         | UC6580 GNSS UART RX/TX (UART1)            |
| 3              | VTFT/VGNSS rail enable (HIGH = on)        |
| 2              | VBAT divider enable (HIGH only on sample) |
| 1              | VBAT ADC1_CH0                             |
| 0              | USER button (active LOW)                  |
| 19, 20         | USB D+/D-                                 |
| 4,5,6,7        | SD MISO/SCK/MOSI/CS (FSPI/SPI2)          |
| Internal       | SX1262 LoRa SPI                           |

### SD card: HiLetgo HW-125

**Critical**: wire VCC to Heltec 5V, NOT 3V3. The onboard AMS1117-3.3V LDO
needs at least 4.4 V input. At 3V3 the card gets ~2 V and will not respond.
This burned a full debug session — see Lessons Learned.

| HW-125 | Heltec | Notes              |
|--------|--------|--------------------|
| GND    | GND    |                    |
| VCC    | 5V     | feeds onboard LDO  |
| MISO   | GPIO 4 |                    |
| MOSI   | GPIO 6 |                    |
| SCK    | GPIO 5 |                    |
| CS     | GPIO 7 |                    |

---

## Firmware Build & Flash

```powershell
# Always run from the repo root
cd <repo-root>

# Build
pio run -e heltec_tracker_v2

# Flash (device on COM4)
pio run -e heltec_tracker_v2 -t upload

# Flash with explicit port
pio run -e heltec_tracker_v2 -t upload --upload-port COM4

# Capture boot sequence (native USB-CDC needs this helper)
python capture_boot.py

# Live serial tail
python scripts\drive.py listen 30

# Interactive serial REPL
python scripts\drive.py repl
```

**Firmware version**: tracked in `src/config.h` as `FW_VERSION`.
Current: `0.2.0`.

---

## Secrets (Firmware)

`src/secrets.h` is gitignored. Create it from `src/secrets.h.example`:

```cpp
namespace secrets {
constexpr const char* WIFI_SSID        = "YourNetwork";
constexpr const char* WIFI_PASS        = "YourPassword";
constexpr const char* INGEST_URL       = "http://192.168.86.48:8030/ingest/csv";
constexpr uint32_t    UPLOAD_INTERVAL_MS = 60000;
}
```

Empty `WIFI_SSID` or `INGEST_URL` disables the Wi-Fi uploader silently.

---

## Firmware Subsystems

### BLE / RadiaCode — `radiacode.{h,cpp}`

- NimBLE-Arduino 1.4.x; service UUID `e63215e5-7003-49d8-96b0-b024798fb901`
- States: `Idle → Scanning → Connecting → Connected → Streaming`
- **Critical build flags** (in `platformio.ini`) for RC-110 BT5 extended advertising:
  - `CONFIG_BT_NIMBLE_EXT_ADV=1`
  - `CONFIG_BT_NIMBLE_MAX_EXT_ADV_INSTANCES=0`
  - `CONFIG_BT_NIMBLE_EXT_ADV_MAX_SIZE=255`
  - `CONFIG_BT_CTRL_SCAN_DUPL_TYPE_DATA_DEVICE=1`
  - `CONFIG_BT_CTRL_SCAN_DUPL_TYPE=2`
- Pinned-target address (NVS key `grab_pat`) prevents reconnect to wrong peer
- Call `secureConnection()` immediately after connect for RC-110

### GPS — `gps_module.{h,cpp}`

- UC6580 on UART1 (RX=33, TX=34); auto-baud sweep 115200→9600→38400→57600
- **`bestEpochMs()`**: anchors UTC+millis pair on each GPS fix, advances
  monotonically through GPS outages so timestamps never stall or duplicate
- Samples skipped until `bestEpochMs() >= MIN_VALID_TS_MS` (2020-01-01)
  to prevent `millis()`-since-boot from poisoning session timestamps
- Serial log: `[GPS] UTC anchor set: ...` / `[GPS] UTC anchor refreshed: ... (drift Xms)`

### Session Storage — `session_store.{h,cpp}`

- Primary: SdFat on FSPI (GPIO 4-7)
- Fallback: LittleFS (5.9 MB partition) — currently disabled
  (`cfg::SD_REQUIRED = true`), failure is a hard error on screen
- CSV schema: `timestampMs,uSvPerHour,cps,latitude,longitude,deviceId`
- Sessions are append-only; `removeSession()` refuses to delete active session
- Serial log on start/stop: `[REC] START: id=...` / `[REC] STOP: id=... samples=N`

### TFT UI — `ui.{h,cpp}`

- ST7735 landscape rotation=1; 160×80; colors: GREEN `#00E676`, RED, DIM_GREY
- Screens cycled by short-press: STATS → GPS → STORAGE → PICKER (long-press)
- **Stop-recording requires DOUBLE long-press** (added v0.2.0):
  - First long-press on STORAGE while recording: shows red "HOLD AGAIN: STOP REC"
  - Second long-press within 5 seconds: stops recording
  - Single press, short press, or 5-second timeout cancels — recording continues
  - Starting recording still requires only one long-press (no confirmation)
- This prevents accidental stop from road vibration bumping the button

### Wi-Fi Uploader — `wifi_uploader.{h,cpp}`

- FreeRTOS task pinned to core 0 (keeps BLE/GPS on core 1 uninterrupted)
- Uploads completed sessions via `POST /ingest/csv` every 60 seconds
- Deletes session file from SD after successful 2xx response
- Headers: `X-Session-Id`, `X-Device-Id`, `X-Tracker-Id`, `X-Firmware`

---

## Serial Console Reference

Connection: 115200 baud, USB-CDC. Type `?` or `HELP` for the live list.

| Command          | Effect |
|------------------|--------|
| `?` / `HELP`     | command list |
| `HB`             | force a heartbeat row |
| `s [secs]`       | BLE scan (default 8s) |
| `c <mac> [pin]`  | connect + pin target |
| `D`              | disconnect, keep pin |
| `X`              | disconnect, clear pin |
| `START` / `STOP` | toggle recording |
| `LS`             | list sessions |
| `DUMP <id>`      | stream one CSV |
| `DUMPALL`        | stream all CSVs |
| `WIPE`           | delete all CSVs (refuses active) |
| `RM <id>`        | delete one session |
| `STATFS`         | storage stats |
| `SDSTAT`         | SD card status |
| `g <nmea>`       | inject NMEA command |
| `GPASSTHRU [s]`  | GPS UART passthrough |
| `GREBAUD <baud>` | switch GPS baud |

Heartbeat format (every 3 s):
```
[HB] uptime=Xs fix=N sats=N hdop=H gpsB=N gpsAge=Xms baud=N rcState=N rec=0/1 samples=N
```

---

## Deploy API (vega-tracker-ingest)

```powershell
cd api\vega-tracker-ingest
.\deploy.ps1              # copy + build + start container
.\deploy.ps1 -TestOnly    # health check only
.\deploy.ps1 -SkipCopy    # rebuild container without re-copying files
```

What `deploy.ps1` does:
1. Reads `.env` for SSH creds, remote path, port, Mongo URI
2. `rsync`/`scp` source to `darkmatter2222@192.168.86.48:/home/darkmatter2222/vega-tracker-ingest`
3. `docker compose up --build -d` on the remote
4. Polls `GET /health` until healthy

Verify manually:
```powershell
ssh darkmatter2222@192.168.86.48 "curl -s http://localhost:8030/health"
ssh darkmatter2222@192.168.86.48 "curl -s http://localhost:8030/info"
```

### API Endpoints

| Method | Path                     | Description |
|--------|--------------------------|-------------|
| GET    | /health                  | liveness + mongo ping |
| GET    | /info                    | version, collection counts, sample rate |
| GET    | /sessions                | list sessions (id, device, samples, first/last ts) |
| GET    | /session/{id}            | session detail (up to 5000 samples) |
| POST   | /ingest/csv              | upload one session CSV |
| POST   | /admin/recompute-sessions | purge pre-2020 rows, recompute all session metadata |

### Ingest CSV Headers

```
X-Session-Id    required   "boot_362620" or "20260426_104210"
X-Device-Id     optional   RadiaCode BLE MAC without colons
X-Tracker-Id    optional   ESP32 chip ID / MAC
X-Firmware      optional   firmware version string
```

### MIN_VALID_TS_MS

All rows with `timestampMs < 1_577_836_800_000` (2020-01-01 UTC) are rejected
by the API. Old firmware used `millis()`-since-boot (e.g. 1777) as timestamps
before GPS UTC was acquired — these would corrupt session metadata.

### MongoDB Admin

```powershell
# Get a Mongo shell on the server
ssh darkmatter2222@192.168.86.48

# Mongo shell (server-side)
mongosh "mongodb://ryan:Welcome123!@localhost:27017/?authSource=admin"

# Useful queries
use radiacode
db.tracker_sessions.find().sort({firstTsMs:-1}).limit(10).pretty()
db.tracker_samples.countDocuments({sessionId:"boot_362620"})
db.tracker_samples.find({sessionId:"boot_362620"}).sort({timestampMs:1}).limit(5)

# Recompute sessions after data fix
curl -X POST http://192.168.86.48:8030/admin/recompute-sessions
```

---

## Deploy Web Viewer (vega-tracker-viewer)

```powershell
cd web\vega-tracker-viewer
.\deploy.ps1              # copy + build Docker image + start container
.\deploy.ps1 -TestOnly    # check container health only
.\deploy.ps1 -SkipCopy    # rebuild image without re-copying source
```

The React app is built inside the Docker image at build time (Vite build).
`API_BASE` env var is injected at container runtime via `nginx.conf`
substitution so the compiled JS references the right API URL.

Viewer URL: `http://192.168.86.48:8031/`

---

## Python Dev Tools (scripts/)

```powershell
# Tail serial for 30 seconds
python scripts\drive.py listen 30

# Send a command and tail output
python scripts\drive.py cmd "LS" --listen 4

# Interactive REPL
python scripts\drive.py repl

# Download all sessions from device to local CSVs
python scripts\download_sessions.py
python scripts\download_sessions.py --no-wipe   # keep files on SD card

# Plot a session on an interactive map
python scripts\plot_session_map.py path\to\session.csv

# Watch overnight logging
python scripts\overnight_watch.py

# Trigger device reset and capture the first 12 seconds of boot output
python scripts\capture_boot.py
```

---

## Data Schema

CSV on device (and what gets POSTed to the API):
```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
1746114660123,0.142,12.0,47.6062,-122.3321,5243066020F4
```

- `timestampMs`: Unix epoch ms (GPS-derived via `bestEpochMs()`)
- `uSvPerHour`: dose rate in micro-Sieverts per hour (raw from RadiaCode)
- `cps`: counts per second (raw from RadiaCode)
- `latitude` / `longitude`: decimal degrees (0.0,0.0 = no GPS fix)
- `deviceId`: RadiaCode BLE MAC without colons (e.g. `5243066020F4`)

MongoDB stores the same fields plus:
- `sessionId`: from `X-Session-Id` header
- `trackerId`: from `X-Tracker-Id` header
- `firmware`: from `X-Firmware` header
- `loc`: GeoJSON Point `{type:"Point", coordinates:[lng,lat]}` (non-zero GPS only)

---

## Configuration Knobs

All in `src/config.h` under `namespace cfg`. Edit here and recompile.

| Knob                    | Default  | Notes |
|-------------------------|----------|-------|
| `SD_ENABLED`            | `true`   | master SD on/off switch |
| `SD_REQUIRED`           | `true`   | refuse LittleFS fallback; make failure visible |
| `SD_INIT_RETRIES`       | `6`      | cold-boot retries (LDO ramp time) |
| `SD_INIT_RETRY_GAP_MS`  | `250`    | ms between SD init retries |
| `RADIACODE_POLL_MS`     | `1000`   | ~1 Hz BLE poll; keeps RC-110 link alive |
| `RADIACODE_SCAN_MS`     | `8000`   | default BLE scan duration |
| `UI_TICK_MS`            | `100`    | TFT redraw cadence |
| `HEARTBEAT_MS`          | `3000`   | `[HB]` serial heartbeat cadence |

---

## Code Style Rules

- **No emojis** — anywhere (comments, serial logs, UI strings, docs)
- No C++14 digit separators (`20'000'000`) — this toolchain rejects them
- Comments explain *why*, not *what*; lean toward "for posterity" on weird knobs
- Serial output is the only debug surface — be loud and unambiguous at boot
- One subsystem per `.h`/`.cpp` pair; keep `main.cpp` thin

---

## Stop Conditions (when to ask the user)

Pause and ask only if:
1. Credentials / secrets are needed that are not in `.env` or `secrets.h`
2. Production impact — live services that receive real device data
3. Destructive operations — session data is append-only, never delete rows from MongoDB
4. Ambiguous requirements

Otherwise, iterate to completion.

---

## Lessons Learned — Do Not Re-Litigate

### BLE — RadiaCode-110

- **Silent connect failures**: RC-110 needs `CONFIG_BT_NIMBLE_EXT_ADV=1`
  (BT5 extended advertising on secondary channels). Without this flag the
  device scans but never connects. See `platformio.ini` build_flags.
- **Disconnect within 750ms**: RC-110 requires `secureConnection()` immediately
  after connect or the peer drops the link.
- **Reconnects to wrong peer**: use NVS-pinned target MAC (`grab_pat` NVS key).

### GPS Timestamps

- **millis()-since-boot as timestamp**: if GPS UTC has not been acquired yet,
  old code used raw `millis()` (e.g. 177 ms) as the timestamp. One such row
  makes `firstTsMs` look like 1970 and the session span 56 years.
  Fix: `MIN_VALID_TS_MS` gate (2020-01-01) in both firmware and API;
  `bestEpochMs()` projects forward via `millis()` delta until GPS locks.
- **TinyGPS++ latches last fix**: `utcEpochMs()` returns the same frozen
  timestamp when GPS signal is lost indoors. All rows become identical
  timestamps and get deduplicated by the unique index on `{sessionId, timestampMs}`.
  Fix: `bestEpochMs()` advances via `millis()` delta through GPS outages so
  each row gets a unique, monotonically increasing timestamp.

### Double Long-Press Stop (v0.2.0)

- Road vibration while cycling: a bump would short-press to STORAGE screen,
  then a sustained bounce = long-press = recording silently stopped.
  The Wi-Fi uploader uploads and deletes the session file within ~60 seconds.
  Fix: first long-press shows "HOLD AGAIN: STOP REC" confirmation (5-second
  window). Second long-press stops. Any other input or timeout cancels.

### V1.2 vs V2 Panel

- Flashing the `heltec_tracker_v1_2` environment on V2 hardware inverts the
  display and produces a solid white screen. Always use `heltec_tracker_v2`.
  `default_envs = heltec_tracker_v2` is set in `platformio.ini`.

### SD Card Power (the big one)

- HW-125 SD module VCC must be **5V**, not 3V3. The AMS1117 LDO on the module
  has ~1.1V dropout; at 3V3 input the card gets ~2V and will not respond to
  any SPI command. Three different driver implementations all failed for the
  same root cause.
  Fix: one wire moved from the 3V3 pin to the 5V pin.
- Cold-boot intermittence: retry loop (6 attempts, 250ms gap) handles LDO
  power-rail ramp time.
- `SD_REQUIRED=true` makes storage failure a hard, visible error on the TFT
  instead of a silent downgrade to LittleFS.

### Wi-Fi Uploader Jitter

- Running `HTTPClient` on the Arduino loop core (core 1) caused BLE packet
  jitter, dropping RadiaCode readings.
  Fix: pin the uploader `xTaskCreatePinnedToCore` to core 0.

---

## Data Schema

CSV on device (and what gets POSTed to the API):
```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
1746114660123,0.142,12.0,47.6062,-122.3321,5243066020F4
```

- `timestampMs`: Unix epoch ms (GPS-derived via `bestEpochMs()`)
- `uSvPerHour`: dose rate in micro-Sieverts per hour (raw from RadiaCode)
- `cps`: counts per second (raw from RadiaCode)
- `latitude` / `longitude`: decimal degrees (0.0,0.0 = no GPS fix)
- `deviceId`: RadiaCode BLE MAC without colons (e.g. `5243066020F4`)

MongoDB stores the same fields plus:
- `sessionId`: from `X-Session-Id` header
- `trackerId`: from `X-Tracker-Id` header
- `firmware`: from `X-Firmware` header
- `loc`: GeoJSON Point `{type:"Point", coordinates:[lng,lat]}` (if non-zero)

---

## Configuration Knobs

All in `src/config.h` under `namespace cfg`. Edit here and recompile.

| Knob                    | Default     | Notes |
|-------------------------|-------------|-------|
| `SD_ENABLED`            | `true`      | master SD switch |
| `SD_REQUIRED`           | `true`      | refuse LittleFS fallback |
| `SD_INIT_RETRIES`       | `6`         | cold-boot retries |
| `SD_INIT_RETRY_GAP_MS`  | `250`       | ms between retries |
| `RADIACODE_POLL_MS`     | `1000`      | ~1 Hz keeps RC-110 link alive |
| `RADIACODE_SCAN_MS`     | `8000`      | default scan duration |
| `UI_TICK_MS`            | `100`       | TFT redraw tick |
| `HEARTBEAT_MS`          | `3000`      | `[HB]` log cadence |

---

## Code Style Rules

- **No emojis** — anywhere (comments, logs, UI strings, docs)
- No C++14 digit separators (`20'000'000`) — toolchain bites
- Comments explain *why*, not *what*; lean toward "for posterity" on weird knobs
- Serial output is the only debug surface — be loud and unambiguous at boot
- One subsystem per .h/.cpp pair; keep `main.cpp` thin

---

## Stop Conditions (when to ask the user)

Pause and ask only if:
1. Credentials / secrets are needed that are not in `.env` or `secrets.h`
2. Production impact — live services that receive real device data
3. Destructive operations — session data is append-only, never delete rows from MongoDB
4. Ambiguous requirements

Otherwise, iterate to completion.

---

## Lessons Learned — Do Not Re-Litigate

### BLE — RadiaCode-110

- **connect silently fails**: needs `CONFIG_BT_NIMBLE_EXT_ADV=1`
  (BT5 extended adv on secondary channels). See build flags above.
- **disconnect within 750ms**: RC-110 requires `secureConnection()` immediately.
- **reconnects to wrong peer**: use NVS pinned target (`grab_pat`).

### GPS Timestamps

- **millis()-since-boot as timestamp**: if GPS UTC not yet acquired the old
  code used `millis()` (e.g. 177 ms) as the timestamp. One such row makes
  `firstTsMs` look like 1970 and the session span 56 years.
  Fix: `MIN_VALID_TS_MS` gate in firmware + API; `bestEpochMs()` projects forward.
- **TinyGPS++ latches last fix**: `utcEpochMs()` returns the same frozen
  timestamp when GPS is lost indoors. All rows get identical timestamps,
  deduplicated by the unique index on `{sessionId, timestampMs}` in MongoDB.
  Fix: `bestEpochMs()` advances via `millis()` delta through outages.

### Double Long-Press Stop (v0.2.0)

- Road vibration while cycling: short-press scrolled to STORAGE screen,
  then sustained bump = long-press = session silently stopped.
  The WiFi uploader uploads and deletes the file within 60 seconds.
  Fix: first long-press shows "HOLD AGAIN: STOP REC" (5-second window).
  Second long-press actually stops. Any other input cancels.

### V1.2 vs V2 Panel

- Flashing `heltec_tracker_v1_2` env on V2 hardware = solid white screen.
  Always use `heltec_tracker_v2`. `default_envs` in `platformio.ini` is set.

### SD Card Power (the big one)

- HW-125 VCC must be 5V, not 3V3. AMS1117 LDO has ~1.1V dropout.
  At 3V3 input the card gets ~2V and will not respond to any driver.
  Three different driver implementations all failed for the same reason.
  Fix: one wire move from 3V3 pin to 5V pin.
- Cold-boot intermittence: retry loop (6 attempts, 250ms gap) handles LDO ramp time.
- Silent LittleFS fallback surprises users: `SD_REQUIRED=true` makes
  storage failure a hard, visible error instead of a silent downgrade.

### Wi-Fi Uploader Jitter

- HTTPClient on the Arduino loop core (core 1) caused BLE jitter.
  Fix: pin uploader task to core 0 with `xTaskCreatePinnedToCore`.
