# Heltec Tracker Agent Notes

Per-subproject instructions for AI agents working in `heltec_tracker/`.

---

## Hardware

- **Board:** Heltec HTIT-Tracker V1.2 (ESP32-S3 + SX1262 + UC6580 GNSS + ST7735 TFT)
- **PlatformIO env:** `heltec_wifi_lora_32_V3`
- **Upload port:** `COM3` (Windows)

### Reserved / in-use GPIOs (do NOT reuse)

| Pin(s)        | Function                  |
|---------------|---------------------------|
| 38, 39, 40, 41, 42 | TFT (CS/DC/RST/BL/MOSI/SCLK on the TFT bus) |
| 33, 34        | UC6580 GNSS UART RX / TX  |
| 3, 36         | Vext / VGNSS_CTRL (varies by sub-rev) |
| 0             | USER button                |
| 19, 20        | USB D+/D-                  |
| Internal      | SX1262 LoRa SPI (handled by Heltec lib) |

### Free GPIOs used in this project

| Pin | Function                  |
|-----|---------------------------|
| 4   | SD MISO                    |
| 5   | SD SCK                     |
| 6   | SD MOSI                    |
| 7   | SD CS                      |

---

## SD card (HiLetgo HW-125 micro-SD breakout)

Sessions are written to a micro-SD card when present, with automatic
fallback to the on-chip LittleFS partition (~1.5 MB) if the card cannot
be mounted.

### Wiring

| HW-125 pin | Heltec pin | Notes |
|------------|------------|-------|
| GND        | GND        | any GND pad |
| VCC        | 3V3        | module's onboard LDO/level-shifter is happy at 3.3V; gives you SD power even on battery |
| MISO       | GPIO 4     | SD -> ESP (data in)  |
| MOSI       | GPIO 6     | ESP -> SD (data out) |
| SCK        | GPIO 5     | SPI clock |
| CS         | GPIO 7     | chip-select, any free GPIO works |

The SD bus is a **dedicated HSPI** instance (`SPIClass(HSPI)`) so it never
collides with the TFT's bus on GPIO 38-42.

### Init sequence

1. Configure pins, drive CS high, set MISO `INPUT_PULLUP` (cheap modules
   often need this when MISO would otherwise float between transactions).
2. `SD.begin(CS, hspi, 1 MHz)` — start slow; jumper-wire setups rarely
   tolerate 20 MHz on first contact.
3. On failure, retry at 400 kHz.
4. On failure, fall back to LittleFS.

After mount succeeds we currently leave the bus at the init clock; the
session-write workload is tiny (a few hundred bytes/sec), so there's no
need to renegotiate to a faster rate.

### Diagnostics

| Serial command | What it does |
|----------------|--------------|
| `STATFS`       | dumps backend, used/total bytes, and (on SD) cardSizeMB |
| `SDSTAT`       | prints `backend=`, `mounted=`, `cardSizeMB=`, used, total |
| `?` or `HELP`  | command list |

Boot banner format:

```
[STORE] backend=SD used=<n> total=<n> cardSizeMB=<n>
```

or

```
[STORE] backend=LittleFS used=<n> total=<n>
```

### Troubleshooting "no token received" / "APP_OP_COND failed: 255"

Symptoms come in two flavours, both from `sd_diskio.cpp`:

- `APP_OP_COND failed: 255` — card answered CMD0/CMD8 but never finishes
  init. Almost always a **power** problem: the 3V3 rail droops during the
  card's inrush spike. Try a different USB cable (data-quality matters,
  not just power), reseat VCC, or solder the VCC jumper instead of using
  a Dupont contact.
- `no token received` on every command — bus integrity. Either MISO is
  not actually connected (cold solder, broken jumper) or there's enough
  capacitance on the line that the pull-up can't drive it. Shorten leads
  to ~10 cm, push them firmly into the headers, or solder.

If the card never mounts at all:

1. Try the card in a PC reader to confirm it's healthy (FAT32, <=32 GB).
2. Swap to a different micro-SD card; some cheap cards don't honour CMD55
   timing and never finish ACMD41 init.
3. Inspect the HW-125 module: the cheaper clones occasionally ship with
   an unpopulated MISO pull-up resistor.
4. The firmware will fall back to LittleFS automatically; data is never
   lost in this case, just stored on-chip.

---

## Build / flash / verify loop

```powershell
cd c:\Users\ryans\source\repos\RadiaCodeAndroidDataCollection\heltec_tracker

# Build
pio run -e heltec_wifi_lora_32_V3

# Flash
pio run -e heltec_wifi_lora_32_V3 -t upload --upload-port COM3

# Listen
python scripts\drive.py listen 8

# One-shot command + capture
python scripts\drive.py cmd "SDSTAT" --listen 4
```

To force a hard reset (DTR/RTS held off by `drive.py`):

```powershell
python -c "import serial,time; s=serial.Serial('COM3',115200); s.rts=True; time.sleep(0.2); s.rts=False; time.sleep(8); print(s.read(s.in_waiting).decode('utf8','replace')); s.close()"
```

---

## Secrets

`src/secrets.h` is gitignored. Real Wi-Fi credentials go there. Do not
commit. There is no `secrets.h.example`; the file is created on first
boot from defaults compiled into the auto-uploader if missing — see
`wifi_uploader.cpp`.

---

## Code style

- No emojis in serial output, UI text, or comments.
- Avoid C++14 digit separators (e.g. `20'000'000`); the toolchain has
  bitten us on this. Use plain `20000000`.
- The serial console is the only debug surface — be explicit about
  what backend / state is in use at boot.
