# Heltec HTIT-Tracker Agent Notes

Master reference for AI agents (and humans) working on the
`heltec_tracker/` firmware. This is the long-form file: everything we
have built, every dead end we hit, and every lesson learned. Read it
before making changes here. The repo-wide [AGENTS.md](../AGENTS.md) is
the short version.

> **Last major update:** May 2026 — GPS timestamp reliability fixes
> (pre-UTC sample skip, `bestEpochMs()` anchor for GPS-outage continuity);
> default build env corrected to `heltec_tracker_v2`.

---

## 1. What this firmware does

A standalone field tracker that:

1. Connects (BLE central) to a RadiaCode dosimeter (101/102/103/103G/110).
2. Polls dose rate + count rate at ~1 Hz.
3. Pairs each reading with a UC6580 GNSS fix.
4. Logs CSV samples to a micro-SD card (or LittleFS as an opt-in fallback).
5. Renders a live status TFT (STATS / GPS / STORAGE / PICKER / FIX-MAP).
6. Auto-uploads completed sessions to a `vega-tracker-ingest`
   FastAPI service when a known Wi-Fi network is in range.

It is the no-phone counterpart to the Android app: same CSV schema,
same isotope ID pipeline, same map viewer.

---

## 2. Hardware

### 2.1 Board

- **Heltec HTIT-Tracker V2** (ESP32-S3FN8 + SX1262 + UC6580 GNSS +
  ST7735 0.96" 160×80 TFT)
- PlatformIO env: **`heltec_tracker_v2`** (default; use this for all
  build/flash commands — do NOT use `heltec_tracker_v1_2` as that sets
  `TFT_INVERT = true` which produces a solid white screen on V2 hardware)
- Panel offsets: `XSTART=0 / YSTART=24`, `invertDisplay(false)`
- Arduino-ESP32 core: ESP-IDF 4.4 / Arduino-ESP32 2.0.14
  (set by `platform = espressif32 @ ^6.7.0`)
- Native USB-CDC on boot (`ARDUINO_USB_CDC_ON_BOOT=1`,
  `ARDUINO_USB_MODE=1`)
- Upload port: auto-detected (COM4 on this dev box)
- Flash partition: custom `partitions_tracker_v2.csv`

### 2.2 Reserved / in-use GPIOs (do **not** reuse)

| Pin(s)         | Function                                |
|----------------|-----------------------------------------|
| 38, 39, 40, 41, 42 | TFT (CS / DC / RST / SCLK / MOSI; backlight on 21) |
| 33, 34         | UC6580 GNSS UART RX / TX (UART1)        |
| 3              | VTFT/VGNSS rail enable (HIGH = on)      |
| 21             | TFT backlight (HIGH = on)               |
| 2              | VBAT divider enable (HIGH only when sampling) |
| 1              | VBAT ADC1_CH0                           |
| 0              | USER button (active LOW)                |
| 19, 20         | USB D+ / D-                             |
| Internal       | SX1262 LoRa SPI                         |

### 2.3 Free GPIOs we are using

| Pin | Function                |
|-----|-------------------------|
| 4   | SD MISO                 |
| 5   | SD SCK                  |
| 6   | SD MOSI                 |
| 7   | SD CS                   |

That's it. There are still plenty of free pins (9-18, 35-37, 45-48 with
caveats) for future expansion (e.g. a buzzer, a GPS PPS line).

### 2.4 SD card module — HiLetgo HW-125

- 4-bit SPI breakout with onboard **AMS1117-3.3V** LDO and
  **74LVC125A** level-shifter.
- **Wire VCC to the Heltec `5V`/`Vin`/`USB` pin, NOT `3V3`.** The
  AMS1117 has ~1.1 V dropout; feeding it 3.3 V puts the card's Vdd
  at ~2.0-2.5 V and it will not respond. This cost us a full debug
  session — see [§7 Lessons Learned](#7-lessons-learned).
- Wiring table:

  | HW-125  | Heltec | Notes                              |
  |---------|--------|------------------------------------|
  | GND     | GND    |                                    |
  | VCC     | 5V     | feeds the onboard LDO              |
  | MISO    | GPIO 4 | data in (ESP <- card)              |
  | MOSI    | GPIO 6 | data out (ESP -> card)             |
  | SCK     | GPIO 5 | SPI clock                          |
  | CS      | GPIO 7 | chip-select                        |

- We use the **FSPI** controller (SPI2) because GPIOs 4-7 land on its
  IOMUX fast path on the S3, which is more reliable than the
  GPIO-matrix-only HSPI for SD-over-SPI. The TFT lives on a separate
  bus at GPIO 38-42 so they never collide.

### 2.5 Cards verified

- **HiLetgo 16 GB Class 10**, FAT32 — mounts at 8 MHz on first try via
  SdFat. Storage budget: ≈70 B per CSV row → ~7 years at 1 Hz.

---

## 3. Source layout

```
heltec_tracker/
├── platformio.ini              # build env, lib_deps
├── partitions_tracker.csv      # custom flash layout (1.5 MB littlefs)
├── AGENTS.md                   # this file
├── README.md
├── src/
│   ├── main.cpp                # setup() / loop(), serial console
│   ├── config.h                # ALL pin assignments, feature flags
│   ├── secrets.h               # gitignored - WIFI_SSID / INGEST_URL etc.
│   ├── secrets.h.example
│   ├── button.{h,cpp}          # debounced GPIO 0 input + long-press
│   ├── gps_module.{h,cpp}      # UC6580 + auto-baud + diagnostics
│   ├── radiacode.{h,cpp}       # NimBLE central + protocol + reconnect
│   ├── session_store.{h,cpp}   # SD/SdFat/LittleFS CSV writer
│   ├── ui.{h,cpp}              # ST7735 screens + state machine
│   └── wifi_uploader.{h,cpp}   # FreeRTOS task on core 0 for HTTP POST
└── scripts/
    ├── drive.py                # serial console wrapper (cmd/listen/REPL)
    ├── download_sessions.py    # DUMPALL -> CSV files, with auto-wipe
    ├── plot_session_map.py     # interactive folium map
    └── overnight_watch.py      # log-tailing watchdog with reconnect
```

`heltec_tracker/data/` holds older spectrogram captures from before
the BLE work and is unused at runtime.

---

## 4. Subsystems

### 4.1 BLE / RadiaCode protocol — `radiacode.{h,cpp}`

- Uses **NimBLE-Arduino 1.4.x** (`h2zero/NimBLE-Arduino`).
- Service UUID: `e63215e5-7003-49d8-96b0-b024798fb901`.
- Connect cycle: `Idle → Scanning → Connecting → Connected → Streaming`.
- **Critical build flags** (in `platformio.ini`) — without these the
  RC-110 silently drops connect requests:
  - `CONFIG_BT_NIMBLE_EXT_ADV=1` (BT5 extended advertising)
  - `CONFIG_BT_NIMBLE_MAX_EXT_ADV_INSTANCES=0`
  - `CONFIG_BT_NIMBLE_EXT_ADV_MAX_SIZE=255`
  - `CONFIG_BT_CTRL_SCAN_DUPL_TYPE_DATA_DEVICE=1`
  - `CONFIG_BT_CTRL_SCAN_DUPL_TYPE=2` (per-device dedup, not per-data)
- Pinned-target preference: once you `c <addr>` the address is stored
  in NVS so the device only reconnects to that exact peer (avoids
  pairing with whichever BT5 advertiser is loudest).
- Long-press on PRG button = picker (lists nearby BLE peers by RSSI).
- Quick-drop detection: if an RC-110 connects then disconnects within
  the first 750 ms we tear the link down completely instead of letting
  NimBLE keep the half-open state.
- Use `secureConnection()` immediately after connect for the 110 — it
  expects encryption before the first GATT read.
- Write notifications: write-without-response on the data char.

Serial console commands related to BLE (see `main.cpp`):

| Command         | Effect |
|-----------------|--------|
| `s [secs]`      | manual scan, default 8s, max ~60s |
| `c <mac> [pin]` | connect (and pin in NVS for next boot) |
| `D`             | disconnect but keep pinned target |
| `X`             | disconnect AND clear pinned target |

### 4.2 GPS — `gps_module.{h,cpp}`

- **UC6580** chip on UART1 (RX=33, TX=34).
- Auto-baud sweep: 115200 → 9600 → 38400 → 57600. Picks the first
  baud that produces real NMEA bytes. The factory default is 115200
  on this carrier but some refurb units come back at 9600.
- TinyGPSPlus parses; we expose `hasFix()`, `lat()`, `lng()`,
  `hdop()`, sat count, byte counter, age in ms.
- Serial commands:
  | Command           | Effect |
  |-------------------|--------|
  | `g <cmd>`         | send a raw NMEA `$P...` command |
  | `GPASSTHRU [secs]`| pass-through GPS UART to USB-CDC |
  | `GREBAUD <baud>`  | re-init at a specific baud and persist |
- Heartbeat row every 3 s prints
  `[HB] uptime=Xs fix=N sats=N hdop=H gpsB=N gpsAge=Xms baud=N rcState=N rec=0/1 samples=N`
  — leave this on for any session where you suspect link issues.

### 4.3 TFT UI — `ui.{h,cpp}`

- Adafruit ST7735 driver in **landscape** (rotation=1, ribbon at right).
- 160×80 panel uses `TFT_X_OFFSET=1, TFT_Y_OFFSET=26` (mini panel).
- Background `BLACK`, dose green `GREEN`, alarm `RED`, mute `DIM_GREY`.
- Screens (cycled by short-press, in order):
  1. **STATS**: dose rate, count rate, RC connection state, battery %
  2. **GPS**: fix, sats, HDOP, lat/lng, baud, byte count
  3. **STORAGE**: REC state, sample count, disk %, session count.
     Shows a hard-fail screen instead when storage init failed.
  4. **PICKER** (long-press): nearby BLE peers, sorted by RSSI
- Long-press on STATS = toggle recording.
- Forced-redraw flag avoids ST7735 flicker on per-frame redraws.

### 4.4 Storage — `session_store.{h,cpp}`

The most-iterated file in the tree. **Three** independent backends
attempted in this order:

1. **SdFat** (greiman/SdFat 2.2.x) — primary. Has its own SPI driver
   that bypasses ESP-IDF's `sd_diskio`. Mount sweep: 8 MHz → 4 MHz →
   1 MHz → 400 kHz, with a retry loop of `SD_INIT_RETRIES=6` and
   `SD_INIT_RETRY_GAP_MS=250` for cold-boot LDO ramp.
2. **SD_MMC 1-bit** — uses the dedicated SDMMC peripheral on the
   same physical wires (`SCK→CLK, MOSI→CMD, MISO→D0`, CS tied HIGH).
   Different driver, different DMA path. **Currently always fails on
   our cards** (returns `0x107 ESP_ERR_TIMEOUT`); kept as fallback for
   different cards / different breakouts.
3. **SD-over-SPI** — Arduino-ESP32 stock `sd_diskio.cpp`. Multi-clock
   retry with format-if-empty. Has the well-known
   [arduino-esp32#6081](https://github.com/espressif/arduino-esp32/issues/6081)
   "no token received" regression on some cards. Fails on ours.
4. **LittleFS** — only if `cfg::SD_REQUIRED == false`. Default is
   `true`, so a missing/dead SD card now produces a hard failure and
   the user is told to reboot.

Backends are exposed as `enum class Backend { None, LittleFs, Sd, SdFat, Failed }`.

`hasUsableBackend()` is the single guard used by every storage method.
When `backend_ == Failed`, recording is refused and the STORAGE screen
shows a red "STORAGE INIT FAILED — please reboot" message.

CSV row format (matches the Android app exactly):

```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
```

One file per session at `/sessions/<id>.csv`. `<id>` is
`YYYYMMDD_HHMMSS` if the system time is set, else `boot_<millis>`.
The `/active.txt` marker holds the active session id so we can resume
through a power loss.

#### Why SdFat exposes `FsFile` and not `fs::File`

SdFat does not derive from `fs::FS`, so we cannot just hand it to the
`fs::FS*` pointer the LittleFS path uses. Instead `session_store.cpp`
branches on `backend_ == Backend::SdFat` everywhere it touches files.
This is uglier than ideal but it works and we don't expect to add a
fourth backend. If a fifth lands, write a thin `fs::FS` shim instead.

#### Storage screen budget

70 B/sample × 1 Hz × 16 GB ≈ **7 years of continuous recording** on
the verified 16 GB card. Realistic per-day write volume is ≈6 MB.
You will fill the battery 1000× before you fill the card.

### 4.5 Wi-Fi uploader — `wifi_uploader.{h,cpp}`

- Pinned to **core 0** so HTTP timeouts and Wi-Fi connect retries
  cannot freeze the BLE stack or UI button polling on core 1.
- Stack 8192 B; priority lower than NimBLE/loop.
- Trigger: every `secrets::UPLOAD_INTERVAL_MS` (default 60 s) or via
  `xTaskNotifyGive` (e.g. on session stop).
- Reads each session as a single string via
  `SessionStore::readSessionToString(id, MAX_BODY_BYTES, body)` so the
  uploader is backend-agnostic.
- POSTs to `secrets::INGEST_URL` (FastAPI service in `middleware/vega-tracker-ingest`).
- Tracker id is derived from chip MAC: `esp32-aabbccddeeff`.

### 4.6 Battery monitor — `main.cpp`

- VBAT divider on GPIO 1, divider enable on GPIO 2 (HIGH only during
  sample). 12-bit ADC, full scale ~3.3 V, multiplier `5.05` (empirical).
- Segmented LiPo curve in `readBatteryPercent()` — not pretty but
  matches the pack's discharge knee.

---

## 5. Build / flash / test loop

Always do this from the project root **first**:

```powershell
cd c:\Users\ryans\source\repos\RadiaCodeAndroidDataCollection\heltec_tracker
```

### Build

```powershell
pio run -e heltec_tracker_v2
```

Clean output ends with `[SUCCESS]`. Warnings about
`CONFIG_BT_CTRL_SCAN_DUPL_TYPE` redefinition are expected and harmless.

### Flash

```powershell
pio run -e heltec_tracker_v2 -t upload
```

**Always specify `-e heltec_tracker_v2` explicitly.** Flashing the
V1.2 env on V2 hardware inverts the display and produces a white screen.

The Heltec V3 uses native USB-CDC, so PlatformIO can flash without
manual button presses.

### Capture boot

The native USB-CDC port does not respond to a plain RTS pulse the way
classic ESP32 boards do. Use the helper:

```powershell
python capture_boot.py        # at heltec_tracker/
```

The script drives DTR low + RTS low to assert the EN pin's reset, then
records 12 s of serial output.

### Live console

```powershell
python scripts\drive.py listen 30          # 30 s tail
python scripts\drive.py cmd "SDSTAT" --listen 4
python scripts\drive.py repl               # interactive
```

### Pull sessions off the device

```powershell
python scripts\download_sessions.py        # dumps + WIPES card by default
python scripts\download_sessions.py --no-wipe  # opt-out of the wipe
```

### Plot a captured session

```powershell
python scripts\plot_session_map.py path\to\session.csv
```

Opens an interactive Folium map (dose + count layers, heatmap,
satellite tiles).

---

## 6. Serial console reference

Connection: 115200 baud, USB-CDC. Type `?` or `HELP` for the live list.

Boot banner (success):

```
HTIT-Tracker firmware v0.1.0 starting...
[SD] trying SdFat on SCK=5 MISO=4 MOSI=6 CS=7
[SdFat] mounted at 8000000 Hz attempt=0: size=15193MB fatType=32
[STORE] backend=SdFat used=0 total=4294967295 cardSizeMB=15193
[WIFI] uploader armed; ssid='...' url='...' interval=60s trackerId=esp32-...
Setup complete
[RC] state=1 addr=
```

Boot banner (storage failure):

```
[STORE] FATAL: SD card required but not detected.
[STORE]        Recording is DISABLED until reboot.
[STORE]        Reseat the card / check 5V on HW-125 VCC, then power-cycle.
```

| Command          | Subsystem | Effect |
|------------------|-----------|--------|
| `?` / `HELP`     | meta      | command list |
| `HB`             | meta      | force a heartbeat row |
| `s [secs]`       | BLE       | manual scan |
| `c <mac> [pin]`  | BLE       | connect (and pin) |
| `D`              | BLE       | disconnect, keep pin |
| `X`              | BLE       | disconnect, clear pin |
| `START` / `STOP` | session   | toggle recording |
| `LS`             | session   | list session ids + sizes |
| `DUMP <id>`      | session   | stream one CSV with begin/end markers |
| `DUMPALL`        | session   | stream every CSV |
| `WIPE`           | session   | delete every CSV (refuses active) |
| `RM <id>`        | session   | delete one (refuses active) |
| `STATFS`         | storage   | backend / used / total / pct / sessions |
| `SDSTAT`         | storage   | backend / mounted / cardSizeMB |
| `g <nmea>`       | GPS       | inject NMEA command |
| `GPASSTHRU [s]`  | GPS       | passthrough mode |
| `GREBAUD <baud>` | GPS       | switch baud and persist |

The framing markers used by `DUMP*`:

```
[DUMP-BEGIN] id=<id> bytes=<n> samples=<m>
<raw csv body>
[DUMP-END] id=<id>
[DUMP-ALL-BEGIN] count=<k>
... per-session ...
[DUMP-DONE] ok=<j> total=<k>
```

`scripts/download_sessions.py` parses these.

---

## 7. Lessons learned

In rough chronological order. All of these were paid for in real
debug time; do not re-litigate them.

### 7.1 BLE — RadiaCode-110

- **Symptom**: scan finds peer, `CONNECT_REQ` issued, never completes.
  Serial logs show `status=13` from NimBLE.
- **Root cause**: RC-110 advertises with **BT5 extended advertising**
  on secondary channels. Stock NimBLE in our core won't follow the
  `AUX_ADV_IND` pointers without `CONFIG_BT_NIMBLE_EXT_ADV=1`.
- **Fix**: build flags listed in §4.1.
- See [NimBLE issue #572](https://github.com/h2zero/NimBLE-Arduino/issues/572).

- **Symptom**: connect succeeds, then disconnects within ~750 ms.
- **Root cause**: 110 expects `secureConnection()` immediately. Without
  it the peer drops the half-open link.
- **Fix**: call `secureConnection()` before any GATT read.

- **Symptom**: connect then immediately reconnects to a different
  peer (an "imposter" advertiser on the same service UUID).
- **Fix**: persistent pinned target in NVS (key `grab_pat`).

### 7.2 GPS

- **Symptom**: GPS module silent.
- **Root cause**: VTFT/VGNSS rail (GPIO 3) starts LOW after reset.
- **Fix**: drive GPIO 3 HIGH in `enablePeripherals()`.

- **Symptom**: bytes flow but TinyGPSPlus parses nothing.
- **Root cause**: factory default baud varies across UC6580 batches
  (115200 vs 9600).
- **Fix**: auto-baud sweep on first start, persist the working baud.

- **Symptom**: session spans 177 million seconds (56 years). One row
  with `timestampMs` in the low thousands poisons the session metadata.
- **Root cause**: before GPS UTC was acquired, the code fell back to
  `millis()` (e.g. 1777 ms since boot) as the timestamp. That tiny
  value made the session's `firstTsMs` look like 1970.
- **Fix** (`main.cpp`): skip samples entirely until `bestEpochMs()`
  returns a value >= `MIN_VALID_TS_MS` (2020-01-01). Never use
  `millis()` as a wall-clock fallback.

- **Symptom**: tracker shows "recording" and the sample counter
  increments, but no new rows appear in the server database — especially
  after walking indoors and losing GPS fix.
- **Root cause**: TinyGPS++ **latches** the last good date/time from
  the most recent NMEA sentence and never auto-advances those values.
  `hasUtc()` returns `true` (latched values are still "valid"), but
  `utcEpochMs()` returns the same frozen UTC for every sample. The
  ingest API has a unique index on `{sessionId, timestampMs}` and
  silently drops all duplicate-timestamp rows.
- **Fix** (`gps_module.{h,cpp}`): added `bestEpochMs()`. It anchors
  a `(utcAnchorMs_, millisAnchor_)` pair whenever a fresh GPS time fix
  is available (re-anchors at most every 30 s) and returns
  `utcAnchorMs_ + (millis() - millisAnchor_)` so timestamps keep
  advancing monotonically through GPS outages. `main.cpp` calls
  `bestEpochMs()` instead of `utcEpochMs()`.

### 7.3 V1.2 vs V2 panel — white-screen trap

- **Symptom**: after flashing, the device shows a solid white screen
  (including the boot splash).
- **Root cause**: the `heltec_tracker_v1_2` PlatformIO env sets
  `TFT_INVERT = true` and panel offsets `XSTART=1/YSTART=26`. The V2
  hardware needs `TFT_INVERT = false` and `XSTART=0/YSTART=24`. Flashing
  V1.2 firmware on V2 hardware produces a fully white display.
- **Fix**: always use `-e heltec_tracker_v2`. The `platformio.ini`
  `default_envs` is now set to `heltec_tracker_v2` to prevent accidents.
- **Diagnostic**: if you see a white screen, first ask "did I flash the
  right env?" before touching any firmware code.

### 7.4 SD card — the big one

A multi-day debug. The full triage:

1. **First wired up the HW-125 with VCC → 3V3.** Got
   `APP_OP_COND failed: 255` and later `no token received`.
2. **Tried swapping MISO/MOSI** in software. New errors (`crc error`,
   `GO_IDLE_STATE failed`) — original wiring was correct, reverted.
3. **Tried slower SPI clocks** (1 MHz, 400 kHz) and `INPUT_PULLUP`
   on MISO. Same failure pattern.
4. **Switched HSPI → FSPI bus.** No change.
5. **Added 100-cycle SPI-mode wakeup bit-bang** with CS HIGH (per the
   SD spec's 74-cycle init). No change.
6. **Multi-retry loop with format-if-empty.** No change.
7. **Tried SD_MMC 1-bit mode** (different peripheral entirely). New
   error: `sdmmc_init_ocr: send_op_cond returned 0x107` (timeout).
8. **Web search led to** [arduino-esp32#6081](https://github.com/espressif/arduino-esp32/issues/6081)
   and [ESP32-audioI2S#245](https://github.com/schreibfaul1/ESP32-audioI2S/issues/245).
   Documented workaround: `greiman/SdFat` library V2.
9. **Added SdFat preflight** with its own SPI driver. **Same
   failure**: `errCode=0x17` (CARD_INIT_NOT_RESPONSIVE).
10. **Conclusion**: three independent driver implementations on two
    different ESP32-S3 hardware peripherals all failing to receive
    a single byte means the issue is not software. It is electrical.
11. **Root cause (finally)**: the HW-125's **AMS1117-3.3V LDO has
    ~1.1 V dropout**. Wired VCC → 3V3 means the LDO input is 3.3 V
    and the output is ~2.0-2.5 V — well below the 2.7 V SD spec
    minimum. The card cannot run.
12. **Fix (one wire move, no soldering)**: VCC → **5V** on the Heltec.
    SdFat mounts at 8 MHz on the first try.

Lessons:
- **If three independent SPI/SDMMC drivers all fail to get any byte
  back from the card, stop debugging software.** The MCU is not the
  problem; the card is not powered or not wired right.
- **Always check the breakout's onboard regulator.** The HW-125
  *looks* like a passive 3.3 V module but it has an LDO that needs
  5 V input to do its job. Same goes for many cheap modules with
  AMS1117 / LM1117 / MIC5219 footprints.
- **Trust the boot logs over the schematic.** The boot log told us
  three different drivers were not seeing any response on MISO. That
  is the signature of "card not powered", not "software bug".

Then once SdFat worked but we still had cold-boot intermittence on
battery:

13. **Symptom**: USB boots fine, battery cold-boot drops to LittleFS.
14. **Root cause**: HW-125 LDO ramp on cold-start sometimes exceeds
    the original 50 ms `delay()` between `gSdSpi.begin()` and
    `gSdFat.begin()`.
15. **Fix**: outer retry loop (`SD_INIT_RETRIES=6, gap=250 ms`).

And a UX wart we fixed:

16. **Symptom**: when SD failed, the device silently used the 1.5 MB
    LittleFS partition. Users assumed they were writing to the SD
    card and lost hours of data.
17. **Fix**: `cfg::SD_REQUIRED = true` (default). Storage failure is
    now a hard error with an unmistakable red on-screen message.

### 7.5 Wi-Fi uploader

- **Symptom**: BLE link gets jittery during uploads.
- **Root cause**: HTTPClient blocks core 1 (the Arduino loop core)
  while POSTing.
- **Fix**: pin uploader to core 0 with `xTaskCreatePinnedToCore`.

### 7.6 PlatformIO / build

- **C++14 digit separators** (`20'000'000`) failed to compile on this
  toolchain. Use plain integers.
- **Default LittleFS label** is `spiffs`, but our partition is
  labelled `littlefs` (despite using the SPIFFS subtype because that
  is how Arduino-ESP32 maps the LittleFS driver). Pass the explicit
  label: `LittleFS.begin(true, "/littlefs", 10, "littlefs")`.
- **`monitor_speed = 115200`** must match `Serial.begin()`. The
  PlatformIO monitor can be flaky with native USB-CDC; prefer
  `scripts/drive.py listen` which we control end-to-end.

---

## 8. Configuration knobs

All in `src/config.h` under `namespace cfg`. Don't override these
elsewhere; tweak them here and recompile.

| Knob                    | Default     | Notes |
|-------------------------|-------------|-------|
| `SD_ENABLED`            | `true`      | master SD switch |
| `SD_REQUIRED`           | `true`      | refuse LittleFS fallback |
| `SD_INIT_RETRIES`       | `6`         | cold-boot retries |
| `SD_INIT_RETRY_GAP_MS`  | `250`       | gap between retries |
| `SD_SPI_HZ`             | `20000000`  | unused right now (SdFat sweeps) |
| `RADIACODE_POLL_MS`     | `1000`      | 110 needs <=1 Hz to keep link alive |
| `RADIACODE_SCAN_MS`     | `8000`      | default `s` duration |
| `RADIACODE_RECONNECT_MS`| `5000`      | post-disconnect wait |
| `UI_TICK_MS`            | `100`       | UI redraw tick |
| `HEARTBEAT_MS`          | `3000`      | `[HB]` log cadence |

`secrets.h` is gitignored. See `secrets.h.example` for the schema:

```cpp
namespace secrets {
constexpr const char* WIFI_SSID = "...";
constexpr const char* WIFI_PASS = "...";
constexpr const char* INGEST_URL = "http://host:8030/ingest/csv";
constexpr uint32_t    UPLOAD_INTERVAL_MS = 60000;
}
```

Empty `WIFI_SSID` or empty `INGEST_URL` disables the Wi-Fi uploader
silently.

---

## 9. Code style

- **No emojis** anywhere (UI, comments, logs).
- **No C++14 digit separators** — toolchain bites.
- Comments explain *why*, not *what*. Lean toward "for posterity"
  comments on weird build flags or hardware quirks.
- Serial output is the only debug surface — be loud and unambiguous
  at boot about what backend / link state we are in.
- One subsystem per file pair (`*.h` + `*.cpp`); keep `main.cpp` thin.
- Free GPIOs are precious — document any new pin in this file's
  §2.2 and §2.3 tables.

---

## 10. Repo / Git

- Feature branch in active development: `feature/heltec-tracker`.
- Atomic commits with short summary and bullet body.
- **Always push after a working change.** Cold-start work has lost
  state to crashed laptops; never leave a working firmware
  uncommitted.
- Never merge to `main` without explicit instruction.

---

## 11. When you get stuck

1. **Read the boot log first.** It tells you backend, BLE state,
   GPS baud, Wi-Fi state in 5 lines.
2. **Read this file's §7 Lessons Learned.** A surprising fraction
   of "new" bugs are repeats.
3. **If three independent drivers/peripherals fail the same way,
   stop coding and check the wires/power.**
4. **If a stock arduino-esp32 driver has the bug, check whether
   the upstream library author has a workaround** (SdFat ↔ SD,
   NimBLE ↔ ESP-BLE, etc.).
5. **Reach for `scripts/drive.py` early.** A 5 s `listen` is cheaper
   than 50 lines of guess-edit-flash-pray.
