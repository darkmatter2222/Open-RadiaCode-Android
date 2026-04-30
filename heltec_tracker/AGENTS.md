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

### Init sequence (current)

1. **SdFat preflight (greiman)** — independent SPI driver. Try at 1 MHz,
   4 MHz, 400 kHz. If this can't even talk to the card, no other backend
   will either (it's electrical, not software).
2. **SD_MMC 1-bit mode** — uses the dedicated SDMMC peripheral (not SPI),
   reusing the same wires (`SCK`->CLK, `MOSI`->CMD, `MISO`->D0). Different
   silicon block, different driver — succeeds on some cards that reject
   SPI mode.
3. **SD over SPI** (Espressif `sd_diskio`, on FSPI / SPI2) — the stock
   Arduino-ESP32 driver. Try 1 MHz -> 400 kHz -> 400 kHz with
   format-if-empty -> 1 MHz with format -> 4 MHz with format. Each retry
   re-issues the 100-cycle SPI-mode wakeup sequence with CS high.
4. **LittleFS fallback** — always works, ~1.5 MB partition.

The SPI-mode 74-cycle wakeup is bit-banged in software (`sdBitBangWakeup`)
because cheap cards often won't transition from native SD mode into SPI
mode without it.

### Triple-driver diagnostic

The boot log will tell you which backend mounted:

```
[SD] preflight: SdFat on SCK=5 MISO=4 MOSI=6 CS=7
[SdFat] mounted at 1000000 Hz: size=15193MB fatType=32   <- card OK
```

vs.

```
[SdFat] 1000000 Hz failed: errCode=0x17 errData=0xFF
[SdFat] 4000000 Hz failed: errCode=0x01 errData=0xFF
[SdFat] 400000 Hz failed: errCode=0x01 errData=0xFF
[SdFat] preflight FAILED across all clocks - card not responding ...
```

If **all three** drivers fail (SdFat, SD_MMC, SD-SPI) the issue is
guaranteed to be electrical: power, wiring, or card. The microcontroller
is sending CMD0 correctly but nothing is coming back on MISO.

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

**First**: check the SdFat preflight result in the boot log. If SdFat
fails too, the issue is below the software layer.

#### When all three drivers fail (SdFat + SD_MMC + SD-SPI)

This is electrical. Most likely causes, in order:

1. **HW-125 power.** The HiLetgo HW-125 has an onboard AMS1117-3.3V LDO
   regulator. AMS1117 has ~1.1 V dropout — it needs **at least ~4.4 V**
   on its input pin to deliver a stable 3.3 V output. **Wiring VCC to the
   ESP32's 3V3 pin starves the LDO**, and the card receives ~2.0-2.5 V
   on its Vdd, well below the 2.7 V minimum. Fix: move VCC from the
   `3V3` pin to a `5V` / `Vin` / `USB` pin on the Heltec board. (The
   HW-125's level-shifter circuit handles 3.3 V logic fine even with 5 V
   on Vcc — that's what it was designed for.)
2. **Cold/loose Dupont contacts.** Reseat MISO especially.
3. **Bad card.** Try a known-good card formatted FAT32 in a PC reader.
4. **Damaged module.** HW-125 clones occasionally ship with shorted or
   missing components.

#### When SdFat mounts but the stock SD driver fails

That's the documented arduino-esp32 regression
([#6081](https://github.com/espressif/arduino-esp32/issues/6081),
[ESP32-audioI2S#245](https://github.com/schreibfaul1/ESP32-audioI2S/issues/245)).
Rare on core 2.0.14 (which we use) but still possible. Fix: plumb SdFat
through `session_store` instead of falling through to the stock driver
(currently we tear SdFat down after preflight because SdFat doesn't
expose the `fs::FS` interface our code uses).

#### Older notes

- `APP_OP_COND failed: 255` (specific): card answered CMD0/CMD8 but
  never finished init. Usually a power droop on inrush. Same fix:
  cleaner 5 V on HW-125 VCC.
- `no token received` on every command: bus integrity / floating MISO /
  card not powered.

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
