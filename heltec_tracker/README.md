# HTIT-Tracker — RadiaCode Field Logger

PlatformIO firmware for the **Heltec HTIT-Tracker V1.2** (Heltec WiFi LoRa 32 V3 + UC6580 GPS + ST7735 1.8" TFT).

The phone is **not involved**. The board:

1. Scans BLE for a nearby RadiaCode dosimeter (auto-reconnects to the last-seen one).
2. Performs the same `SET_EXCHANGE → SET_TIME → DEVICE_TIME=0` init that the Android app uses.
3. Polls `RD_VIRT_STRING(VS_DATA_BUF)` at ~1 Hz, decodes the realtime record (CPS, dose rate, errors, battery, temperature) — same code path as the Android app.
4. Reads UC6580 NMEA over UART2 (TinyGPSPlus).
5. Logs each sample to **LittleFS** as CSV in the **identical schema** to the Android `SessionDataPoint`:

   ```
   timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
   ```

   `timestampMs` is GPS UTC epoch ms once a fix is acquired; `millis()` since boot before that. `deviceId` is the RadiaCode's BLE MAC (no colons).

Wi-Fi/API offload is intentionally **not** implemented — see TODO at the bottom.

---

## Hardware

| Function | Pin | Notes |
|---|---|---|
| GPS UART RX (ESP RX) | 33 | from UC6580 TX |
| GPS UART TX (ESP TX) | 34 | to UC6580 RX |
| TFT CS | 5 | ST7735 |
| TFT DC | 27 | |
| TFT RST | 26 | |
| Vext rail enable | 36 | active LOW |
| VBat ADC enable | 37 | active LOW during read |
| VBat ADC read | 1 | divider × 5.05 |
| PRG button | 0 | active LOW |

Pins mirror [darkmatter2222/External-GPS-Receiver-Heltec-HTIT-Tracker-V1.2](https://github.com/darkmatter2222/External-GPS-Receiver-Heltec-HTIT-Tracker-V1.2), which is verified working on this exact carrier.

---

## Build & flash

```powershell
cd heltec_tracker
pio run                    # build
pio run -t upload          # flash
pio device monitor -b 115200
```

First boot will format LittleFS automatically.

---

## UI (single PRG button)

| Screen | Shows |
|---|---|
| **STATS** | Big nSv/h reading, CPS, error %, RC link state |
| **GPS** | Fix, sats, HDOP, lat/lon, alt, speed |
| **STORAGE** | Recording state, active session ID, sample count, disk %, session count |

Button mapping:

- **Short press** → cycle to next screen.
- **Long press (>800 ms)**:
  - on STATS → force RadiaCode rescan.
  - on STORAGE → toggle recording on/off.

A red dot in the top-right header indicates recording is active.

Header at top of every screen: current screen, `RC:<state>`, `GPS:OK/--`, battery %, recording dot.

---

## CSV file layout

`/sessions/<YYYYMMDD_HHMMSS>.csv`:

```
timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
1745580001234,0.124000,42.500,40.7589123,-73.9851234,A1B2C3D4E5F6
1745580002234,0.126000,43.100,40.7589188,-73.9851272,A1B2C3D4E5F6
```

This is byte-compatible with rows produced by the Android `SessionManager.SessionDataPoint.toCsv()`, so any future ingest endpoint will accept both sources.

`/active.txt` records the in-progress session ID so a power loss doesn't lose state — recording resumes on next boot.

---

## RadiaCode protocol (matches Android)

- Service `e63215e5-7003-49d8-96b0-b024798fb901`
- Write   `e63215e6-7003-49d8-96b0-b024798fb901` (write-without-response, 18-byte chunks)
- Notify  `e63215e7-7003-49d8-96b0-b024798fb901` (length-prefixed reassembly)
- Init: `SET_EXCHANGE 0x01 0xFF 0x12 0xFF` → `SET_TIME` → `WR_VIRT_SFR(VSFR_DEVICE_TIME=0)`
- Poll: `RD_VIRT_STRING(0x0100 = VS_DATA_BUF)` → decode records (`gid=0` realtime, `gid=3` rare/battery, others skipped exactly like the Android decoder)
- `uSvPerHour = doseRate * 10000.0f` — same conversion as Android.

---

## TODO (not in v1)

- Wi-Fi configuration screen + push CSVs to an HTTP endpoint when reachable, retain in LittleFS otherwise.
- Session list / delete UI.
- Mass-storage USB or BLE NUS export so files can be pulled without removing the board.
- Configurable display units (currently fixed to nSv/h + CPS by spec).
