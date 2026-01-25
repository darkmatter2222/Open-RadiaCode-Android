# RadiaCode Communication Protocol Reference

> **Source**: Derived from [cdump/radiacode](https://github.com/cdump/radiacode) Python library  
> **Last Updated**: January 2026  
> **Devices**: RadiaCode-101, RadiaCode-102, RadiaCode-103, RadiaCode-103G, RadiaCode-110

This document is the definitive reference for the RadiaCode BLE/USB communication protocol used in this Android app.

---

## Table of Contents

1. [Overview](#overview)
2. [Connection Methods](#connection-methods)
3. [Protocol Basics](#protocol-basics)
4. [Commands (Outgoing)](#commands-outgoing)
5. [Data Types & Structures](#data-types--structures)
6. [Virtual Strings (VS)](#virtual-strings-vs)
7. [Virtual Special Function Registers (VSFR)](#virtual-special-function-registers-vsfr)
8. [DATA_BUF Record Types](#data_buf-record-types)
9. [Events](#events)
10. [Device Configuration](#device-configuration)
11. [Alarm System](#alarm-system)
12. [Spectrum Data](#spectrum-data)
13. [Calibration](#calibration)
14. [Implementation Notes](#implementation-notes)

---

## Overview

The RadiaCode is a portable gamma spectrometer and dosimeter that communicates via:
- **Bluetooth Low Energy (BLE)** - Primary method for Android
- **USB** - Supported on desktop platforms

All communication uses a request-response pattern with binary messages.

### Supported Device Models

| Model | Crystal | Volume | Notes |
|-------|---------|--------|-------|
| RadiaCode-101 | CsI(Tl) | 1 cm³ | Original model |
| RadiaCode-102 | CsI(Tl) | 1 cm³ | Updated variant |
| RadiaCode-103 | CsI(Tl) | 1 cm³ | Latest 10x series |
| RadiaCode-103G | GAGG | 1 cm³ | Gadolinium aluminum gallium garnet |
| RadiaCode-110 | CsI(Tl) | 2.74 cm³ | Larger crystal, higher sensitivity |

---

## Connection Methods

### Bluetooth LE

**Service UUID**: Uses standard BLE GATT

**Characteristics**:
- **Write**: `e63215e6-7003-49d8-96b0-b024798fb901`
- **Notify**: `e63215e7-7003-49d8-96b0-b024798fb901`

**Protocol**:
1. Connect to device
2. Discover services
3. Enable notifications on notify characteristic
4. Write commands to write characteristic
5. Receive responses via notifications

**Message Framing**:
- Messages are split into 18-byte chunks for BLE
- Response length is prefixed as little-endian U32

### USB

**USB Configuration**:
- Write endpoint: `0x01`
- Read endpoint: `0x81`
- Max packet size: 256 bytes
- Timeout: 3000ms typical

---

## Protocol Basics

### Request Format

```
+------------+--------+--------+--------+
| Length (4) | CmdID  | Zero   | SeqNo  |  + Optional Args
+------------+--------+--------+--------+
   U32 LE      U16 LE    U8    U8 (0x80-0x9F)
```

- **Length**: Total length of message (excluding length field itself)
- **CmdID**: Command identifier (see COMMAND enum)
- **Zero**: Always 0x00
- **SeqNo**: Sequence number (0x80 + counter mod 32)

### Response Format

```
+------------+--------+--------+--------+
| Length (4) | CmdID  | Zero   | SeqNo  |  + Response Data
+------------+--------+--------+--------+
```

Response header echoes the request header.

---

## Commands (Outgoing)

These are commands you can send TO the RadiaCode device.

### COMMAND Enum (Hex Values)

| Command | Value | Description |
|---------|-------|-------------|
| `GET_STATUS` | `0x0005` | Get device status flags |
| `SET_EXCHANGE` | `0x0007` | Initialize communication (send `\x01\xff\x12\xff`) |
| `GET_VERSION` | `0x000A` | Get firmware version info |
| `GET_SERIAL` | `0x000B` | Get hardware serial number |
| `FW_IMAGE_GET_INFO` | `0x0012` | Get firmware image info |
| `FW_SIGNATURE` | `0x0101` | Get firmware signature |
| `RD_HW_CONFIG` | `0x0807` | Read hardware configuration |
| `RD_VIRT_SFR` | `0x0824` | Read single virtual SFR |
| `WR_VIRT_SFR` | `0x0825` | Write single virtual SFR |
| `RD_VIRT_STRING` | `0x0826` | Read virtual string (VS) |
| `WR_VIRT_STRING` | `0x0827` | Write virtual string (VS) |
| `RD_VIRT_SFR_BATCH` | `0x082A` | Read multiple VSFRs at once |
| `WR_VIRT_SFR_BATCH` | `0x082B` | Write multiple VSFRs at once |
| `RD_FLASH` | `0x081C` | Read flash memory |
| `SET_TIME` | `0x0A04` | Set device local time |

### Initialization Sequence

When connecting to a RadiaCode device:

```kotlin
1. execute(SET_EXCHANGE, bytes("\x01\xff\x12\xff"))
2. set_local_time(current_datetime)
3. device_time(0)  // Write 0 to DEVICE_TIME VSFR
```

---

## Data Types & Structures

### Byte Order

All multi-byte values are **little-endian**.

### Struct Format Codes (Python struct notation)

| Code | Type | Size | Kotlin Equivalent |
|------|------|------|-------------------|
| `B` | Unsigned byte | 1 | `readU8()` |
| `b` | Signed byte | 1 | `readI8()` |
| `H` | Unsigned short | 2 | `readU16LE()` |
| `h` | Signed short | 2 | `readI16LE()` |
| `I` | Unsigned int | 4 | `readU32LE()` |
| `i` | Signed int | 4 | `readI32LE()` |
| `f` | Float | 4 | `readF32LE()` |

---

## Virtual Strings (VS)

Virtual strings are large data buffers read/written via `RD_VIRT_STRING` / `WR_VIRT_STRING`.

### VS Identifiers

| VS | Value | Description |
|----|-------|-------------|
| `CONFIGURATION` | `0x002` | Device configuration string (CP1251 encoded) |
| `FW_DESCRIPTOR` | `0x003` | Firmware descriptor |
| `SERIAL_NUMBER` | `0x008` | Device serial number (ASCII) |
| `TEXT_MESSAGE` | `0x00F` | Text message buffer |
| `MEM_SNAPSHOT` | `0x0E0` | Memory snapshot |
| `DATA_BUF` | `0x100` | **Real-time measurement data buffer** |
| `SFR_FILE` | `0x101` | SFR file descriptor (lists available VSFRs) |
| `SPECTRUM` | `0x200` | Current spectrum data |
| `ENERGY_CALIB` | `0x202` | Energy calibration coefficients |
| `SPEC_ACCUM` | `0x205` | Accumulated spectrum |
| `SPEC_DIFF` | `0x206` | Differential spectrum |
| `SPEC_RESET` | `0x207` | Spectrum reset command |

### Reading VS.DATA_BUF

This is the primary way to get real-time radiation data:

```kotlin
// Send: RD_VIRT_STRING with VS.DATA_BUF (0x100)
val response = execute(RD_VIRT_STRING, packU32LE(0x100))
val retcode = response.readU32LE()  // Should be 1
val dataLen = response.readU32LE()
val dataBuf = response.readBytes(dataLen)
// Parse dataBuf using DATA_BUF record format
```

---

## Virtual Special Function Registers (VSFR)

VSFRs are single-value registers for device configuration and status.

### Device Control VSFRs (0x05xx)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `DEVICE_CTRL` | `0x0500` | `3xB` | Device control flags |
| `DEVICE_LANG` | `0x0502` | `3xB` | Language: 0=Russian, 1=English |
| `DEVICE_ON` | `0x0503` | `3x?` | Device power state |
| `DEVICE_TIME` | `0x0504` | `I` | Device time (seconds) |

### Display VSFRs (0x051x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `DISP_CTRL` | `0x0510` | `3xB` | Display control |
| `DISP_BRT` | `0x0511` | `3xB` | Brightness (0-9) |
| `DISP_CONTR` | `0x0512` | `3xB` | Contrast |
| `DISP_OFF_TIME` | `0x0513` | `I` | Auto-off time: 0=5s, 1=10s, 2=15s, 3=30s |
| `DISP_ON` | `0x0514` | `3x?` | Display on/off |
| `DISP_DIR` | `0x0515` | `3xB` | Direction: 0=Auto, 1=Right, 2=Left |
| `DISP_BACKLT_ON` | `0x0516` | `3x?` | Backlight on/off |

### Sound VSFRs (0x052x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `SOUND_CTRL` | `0x0520` | `2xH` | Sound control bitmask |
| `SOUND_VOL` | `0x0521` | `3xB` | Volume level |
| `SOUND_ON` | `0x0522` | `3x?` | Sound enabled |
| `SOUND_BUTTON` | `0x0523` | `3xB` | Button sound |

### Vibration VSFRs (0x053x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `VIBRO_CTRL` | `0x0530` | `3xB` | Vibration control |
| `VIBRO_ON` | `0x0531` | `3x?` | Vibration enabled |

### LED VSFRs (0x054x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `LEDS_CTRL` | `0x0540` | - | LED control |
| `LED0_BRT` | `0x0541` | `3xB` | LED 0 brightness |
| `LED1_BRT` | `0x0542` | `3xB` | LED 1 brightness |
| `LED2_BRT` | `0x0543` | `3xB` | LED 2 brightness |
| `LED3_BRT` | `0x0544` | `3xB` | LED 3 brightness |
| `LEDS_ON` | `0x0545` | - | LEDs enabled |

### Alarm VSFRs (0x05Ex)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `ALARM_MODE` | `0x05E0` | `3xB` | Alarm mode |
| `PLAY_SIGNAL` | `0x05E1` | `3xB` | Play signal type |

### Measurement Mode VSFRs (0x060x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `MS_CTRL` | `0x0600` | - | Measurement control |
| `MS_MODE` | `0x0601` | - | Measurement mode |
| `MS_SUB_MODE` | `0x0602` | - | Sub-mode |
| `MS_RUN` | `0x0603` | `3x?` | Measurement running |

### Bluetooth VSFR

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `BLE_TX_PWR` | `0x0700` | `3xB` | BLE transmit power |

### Alarm Limit VSFRs (0x80xx)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `DR_LEV1_uR_h` | `0x8000` | `I` | Dose rate alarm level 1 (µR/h) |
| `DR_LEV2_uR_h` | `0x8001` | `I` | Dose rate alarm level 2 (µR/h) |
| `DS_LEV1_100uR` | `0x8002` | `I` | Dose alarm level 1 (100µR) |
| `DS_LEV2_100uR` | `0x8003` | `I` | Dose alarm level 2 (100µR) |
| `DS_UNITS` | `0x8004` | `3x?` | Dose units: 0=Roentgen, 1=Sievert |
| `CPS_FILTER` | `0x8005` | `3xB` | CPS filter setting |
| `RAW_FILTER` | `0x8006` | `3xB` | Raw data filter |
| `DOSE_RESET` | `0x8007` | `3x?` | Reset accumulated dose |
| `CR_LEV1_cp10s` | `0x8008` | `I` | Count rate alarm 1 (counts/10s) |
| `CR_LEV2_cp10s` | `0x8009` | `I` | Count rate alarm 2 (counts/10s) |
| `USE_nSv_h` | `0x800C` | `3x?` | Use nSv/h instead of µSv/h |
| `CR_UNITS` | `0x8013` | `3x?` | Count rate units: 0=CPS, 1=CPM |
| `DS_LEV1_uR` | `0x8014` | `I` | Dose level 1 (µR) |
| `DS_LEV2_uR` | `0x8015` | `I` | Dose level 2 (µR) |

### Calibration VSFRs (0x801x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `CHN_TO_keV_A0` | `0x8010` | `f` | Energy calibration a0 (offset) |
| `CHN_TO_keV_A1` | `0x8011` | `f` | Energy calibration a1 (linear) |
| `CHN_TO_keV_A2` | `0x8012` | `f` | Energy calibration a2 (quadratic) |

### Real-time Reading VSFRs (0x802x)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `CPS` | `0x8020` | `I` | Current counts per second |
| `DR_uR_h` | `0x8021` | `I` | Current dose rate (µR/h) |
| `DS_uR` | `0x8022` | `I` | Accumulated dose (µR) |
| `TEMP_degC` | `0x8024` | `f` | Temperature (°C) |
| `ACC_X` | `0x8025` | `2xh` | Accelerometer X |
| `ACC_Y` | `0x8026` | `2xh` | Accelerometer Y |
| `ACC_Z` | `0x8027` | `2xh` | Accelerometer Z |
| `OPT` | `0x8028` | `2xH` | Optical sensor |
| `RAW_TEMP_degC` | `0x8033` | `f` | Raw temperature |
| `TEMP_UP_degC` | `0x8034` | `f` | High temp threshold |
| `TEMP_DN_degC` | `0x8035` | `f` | Low temp threshold |

### Hardware VSFRs (0xC0xx)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `VBIAS_mV` | `0xC000` | `2xH` | Bias voltage (mV) |
| `COMP_LEV` | `0xC001` | `2xh` | Comparator level |
| `CALIB_MODE` | `0xC002` | `3x?` | Calibration mode |
| `DPOT_RDAC` | `0xC004` | `3xB` | Digital potentiometer RDAC |
| `DPOT_RDAC_EEPROM` | `0xC005` | `3xB` | DPOT EEPROM value |
| `DPOT_TOLER` | `0xC006` | `3xB` | DPOT tolerance |

### System VSFRs (0xFFFFxxxx)

| VSFR | Value | Format | Description |
|------|-------|--------|-------------|
| `SYS_MCU_ID0` | `0xFFFF0000` | `I` | MCU ID part 0 |
| `SYS_MCU_ID1` | `0xFFFF0001` | `I` | MCU ID part 1 |
| `SYS_MCU_ID2` | `0xFFFF0002` | `I` | MCU ID part 2 |
| `SYS_DEVICE_ID` | `0xFFFF0005` | `I` | Device ID |
| `SYS_SIGNATURE` | `0xFFFF0006` | `I` | Firmware signature |
| `SYS_RX_SIZE` | `0xFFFF0007` | `2xH` | RX buffer size |
| `SYS_TX_SIZE` | `0xFFFF0008` | `2xH` | TX buffer size |
| `SYS_BOOT_VERSION` | `0xFFFF0009` | `I` | Bootloader version |
| `SYS_TARGET_VERSION` | `0xFFFF000A` | `I` | Target firmware version |
| `SYS_STATUS` | `0xFFFF000B` | `I` | System status |
| `SYS_MCU_VREF` | `0xFFFF000C` | `i` | MCU reference voltage |
| `SYS_MCU_TEMP` | `0xFFFF000D` | `i` | MCU temperature |
| `SYS_FW_VER_BT` | `0xFFFF0010` | - | BT firmware version |

---

## DATA_BUF Record Types

The DATA_BUF contains multiple records of various types. Each record has a header:

### Record Header (7 bytes)

```
+-----+-----+-----+------------+
| Seq | EID | GID | TS_Offset  |
+-----+-----+-----+------------+
  U8    U8    U8     I32 LE
```

- **Seq**: Sequence number (0-255, wraps)
- **EID**: Event ID (usually 0)
- **GID**: Group ID (determines record type)
- **TS_Offset**: Timestamp offset in 10ms units from base_time

### Base Time Calculation

```kotlin
base_time = current_time + 128_seconds
record_time = base_time + (ts_offset * 10ms)
```

### GRP_RealTimeData (EID=0, GID=0) - 15 bytes

**This is the primary real-time radiation data record.**

```
+------------+------------+-------------+-------------+-------+----------+
| CountRate  | DoseRate   | CountRateErr| DoseRateErr | Flags | RT_Flags |
+------------+------------+-------------+-------------+-------+----------+
   F32 LE       F32 LE       U16 LE        U16 LE      U16 LE   U8
```

| Field | Type | Description |
|-------|------|-------------|
| CountRate | F32 | Counts per second (CPS) |
| DoseRate | F32 | Dose rate in Sv/h (multiply by 10000 for µSv/h) |
| CountRateErr | U16 | Count rate error × 10 (divide by 10 for %) |
| DoseRateErr | U16 | Dose rate error × 10 (divide by 10 for %) |
| Flags | U16 | Status flags |
| RT_Flags | U8 | Real-time flags |

**Dose Rate Conversion**:
```kotlin
val uSvPerHour = doseRate * 10000.0f
```

### GRP_RawData (EID=0, GID=1) - 8 bytes

```
+------------+------------+
| CountRate  | DoseRate   |
+------------+------------+
   F32 LE       F32 LE
```

Raw measurement without error calculation.

### GRP_DoseRateDB (EID=0, GID=2) - 16 bytes

```
+-------+------------+------------+-------------+-------+
| Count | CountRate  | DoseRate   | DoseRateErr | Flags |
+-------+------------+------------+-------------+-------+
 U32 LE    F32 LE       F32 LE       U16 LE      U16 LE
```

Database record with accumulated count.

### GRP_RareData (EID=0, GID=3) - 14 bytes ⚠️ CRITICAL

**Contains battery level and temperature!**

```
+----------+------+-------------+--------------+-------+
| Duration | Dose | Temperature | ChargeLevel  | Flags |
+----------+------+-------------+--------------+-------+
  U32 LE    F32 LE    U16 LE        U16 LE      U16 LE
```

| Field | Type | Raw Value | Decoded Value |
|-------|------|-----------|---------------|
| Duration | U32 | seconds | Dose accumulation time |
| Dose | F32 | Sv | Accumulated radiation dose |
| Temperature | U16 | (temp_C × 100) + 2000 | `(raw - 2000) / 100.0` °C |
| ChargeLevel | U16 | percent × 100 | `raw / 100` (0-100%) |
| Flags | U16 | bitmask | Status flags |

**Temperature Decoding**:
```kotlin
val temperature = (temperatureRaw - 2000) / 100.0f  // Celsius
```

**Battery Decoding**:
```kotlin
val batteryPercent = chargeLevelRaw / 100  // 0-100%
```

### GRP_UserData (EID=0, GID=4) - 16 bytes

```
+-------+------------+------------+-------------+-------+
| Count | CountRate  | DoseRate   | DoseRateErr | Flags |
+-------+------------+------------+-------------+-------+
 U32 LE    F32 LE       F32 LE       U16 LE      U16 LE
```

User-triggered data record.

### GRP_ScheduleData (EID=0, GID=5) - 16 bytes

Same format as GRP_UserData. Scheduled measurement.

### GRP_AccelData (EID=0, GID=6) - 6 bytes

```
+-------+-------+-------+
| Acc_X | Acc_Y | Acc_Z |
+-------+-------+-------+
 U16 LE  U16 LE  U16 LE
```

Accelerometer readings.

### GRP_Event (EID=0, GID=7) - 4 bytes

```
+-------+--------+-------+
| Event | Param1 | Flags |
+-------+--------+-------+
   U8      U8     U16 LE
```

See [Events](#events) section for event types.

### GRP_RawCountRate (EID=0, GID=8) - 6 bytes

```
+------------+-------+
| CountRate  | Flags |
+------------+-------+
   F32 LE     U16 LE
```

### GRP_RawDoseRate (EID=0, GID=9) - 6 bytes

```
+------------+-------+
| DoseRate   | Flags |
+------------+-------+
   F32 LE     U16 LE
```

### Sample Blocks (EID=1, GID=1-3)

Variable-length sample data:

```
+------------+------------+---------------+
| SamplesNum | SmplTimeMs | Sample Data   |
+------------+------------+---------------+
   U16 LE       U32 LE      Variable
```

| GID | Bytes per Sample |
|-----|------------------|
| 1 | 8 |
| 2 | 16 |
| 3 | 14 |

---

## Events

Events are transmitted via GRP_Event (EID=0, GID=7).

### Event Types (EventId Enum)

| Event | Value | Description |
|-------|-------|-------------|
| `POWER_OFF` | 0 | Device powered off |
| `POWER_ON` | 1 | Device powered on |
| `LOW_BATTERY_SHUTDOWN` | 2 | Shutdown due to low battery |
| `CHANGE_DEVICE_PARAMS` | 3 | Device parameters changed |
| `DOSE_RESET` | 4 | Accumulated dose was reset |
| `USER_EVENT` | 5 | User-triggered event |
| `BATTERY_EMPTY_ALARM` | 6 | Battery empty warning |
| `CHARGE_START` | 7 | Charging started |
| `CHARGE_STOP` | 8 | Charging stopped |
| `DOSE_RATE_ALARM1` | 9 | Dose rate exceeded level 1 |
| `DOSE_RATE_ALARM2` | 10 | Dose rate exceeded level 2 |
| `DOSE_RATE_OFFSCALE` | 11 | Dose rate off scale |
| `DOSE_ALARM1` | 12 | Accumulated dose exceeded level 1 |
| `DOSE_ALARM2` | 13 | Accumulated dose exceeded level 2 |
| `DOSE_OFFSCALE` | 14 | Accumulated dose off scale |
| `TEMPERATURE_TOO_LOW` | 15 | Temperature below safe range |
| `TEMPERATURE_TOO_HIGH` | 16 | Temperature above safe range |
| `TEXT_MESSAGE` | 17 | Text message received |
| `MEMORY_SNAPSHOT` | 18 | Memory snapshot taken |
| `SPECTRUM_RESET` | 19 | Spectrum data was reset |
| `COUNT_RATE_ALARM1` | 20 | Count rate exceeded level 1 |
| `COUNT_RATE_ALARM2` | 21 | Count rate exceeded level 2 |
| `COUNT_RATE_OFFSCALE` | 22 | Count rate off scale |

---

## Device Configuration

### Setting Language

```kotlin
// 0 = Russian, 1 = English
write_vsfr(VSFR.DEVICE_LANG, packU32LE(if (lang == "en") 1 else 0))
```

### Setting Display Brightness

```kotlin
// 0-9
write_vsfr(VSFR.DISP_BRT, packU32LE(brightness))
```

### Setting Display Auto-Off Time

```kotlin
// 5, 10, 15, or 30 seconds
val v = when (seconds) {
    5 -> 0
    10 -> 1
    15 -> 2
    30 -> 3
    else -> error("Invalid")
}
write_vsfr(VSFR.DISP_OFF_TIME, packU32LE(v))
```

### Setting Display Direction

```kotlin
// 0 = Auto, 1 = Right, 2 = Left
write_vsfr(VSFR.DISP_DIR, packU32LE(direction))
```

### Enabling/Disabling Sound

```kotlin
write_vsfr(VSFR.SOUND_ON, packU32LE(if (enabled) 1 else 0))
```

### Enabling/Disabling Vibration

```kotlin
write_vsfr(VSFR.VIBRO_ON, packU32LE(if (enabled) 1 else 0))
```

### Setting Device Time

```kotlin
// Format: day, month, year-2000, 0, second, minute, hour, 0
val data = byteArrayOf(
    day.toByte(),
    month.toByte(),
    (year - 2000).toByte(),
    0,
    second.toByte(),
    minute.toByte(),
    hour.toByte(),
    0
)
execute(SET_TIME, data)
```

### Powering Off Device

```kotlin
write_vsfr(VSFR.DEVICE_ON, packU32LE(0))
```

---

## Alarm System

### Alarm Control Flags (CTRL Enum)

Used with `SOUND_CTRL` and `VIBRO_CTRL`:

| Flag | Value | Description |
|------|-------|-------------|
| `BUTTONS` | 0x01 | Button press sounds |
| `CLICKS` | 0x02 | Radiation click sounds |
| `DOSE_RATE_ALARM_1` | 0x04 | Sound on DR alarm 1 |
| `DOSE_RATE_ALARM_2` | 0x08 | Sound on DR alarm 2 |
| `DOSE_RATE_OUT_OF_SCALE` | 0x10 | Sound on DR off-scale |
| `DOSE_ALARM_1` | 0x20 | Sound on dose alarm 1 |
| `DOSE_ALARM_2` | 0x40 | Sound on dose alarm 2 |
| `DOSE_OUT_OF_SCALE` | 0x80 | Sound on dose off-scale |

### Reading Alarm Limits

```kotlin
val limits = batch_read_vsfrs([
    VSFR.CR_LEV1_cp10s,
    VSFR.CR_LEV2_cp10s,
    VSFR.DR_LEV1_uR_h,
    VSFR.DR_LEV2_uR_h,
    VSFR.DS_LEV1_uR,
    VSFR.DS_LEV2_uR,
    VSFR.DS_UNITS,
    VSFR.CR_UNITS
])
```

### Setting Alarm Limits

Write to individual VSFRs or use batch write.

**Note**: Dose rate is in µR/h or µSv/h depending on DS_UNITS setting. The device uses a fixed 100 Sv/R conversion.

---

## Spectrum Data

### Reading Spectrum

```kotlin
val response = read_request(VS.SPECTRUM)
// Format: <Ifff> + counts
val duration_seconds = response.readU32LE()
val a0 = response.readF32LE()  // Calibration offset
val a1 = response.readF32LE()  // Calibration linear
val a2 = response.readF32LE()  // Calibration quadratic
// Remaining bytes are counts (format depends on spectrum version)
```

### Spectrum Format Versions

**Version 0**: Simple array of U32 counts

```kotlin
val counts = mutableListOf<Int>()
while (remaining > 0) {
    counts.add(readU32LE())
}
```

**Version 1**: Run-length encoded with delta compression

Complex format - see Python library for full implementation.

### Channel to Energy Conversion

```kotlin
fun channelToEnergy(channel: Int, a0: Float, a1: Float, a2: Float): Float {
    return a0 + a1 * channel + a2 * channel * channel
}
```

### Resetting Spectrum

```kotlin
execute(WR_VIRT_STRING, packU32LE(VS.SPECTRUM) + packU32LE(0))
```

### Resetting Accumulated Dose

```kotlin
write_vsfr(VSFR.DOSE_RESET)
```

---

## Calibration

### Reading Energy Calibration

```kotlin
val response = read_request(VS.ENERGY_CALIB)
val a0 = response.readF32LE()  // keV (offset)
val a1 = response.readF32LE()  // keV/channel (linear)
val a2 = response.readF32LE()  // keV/channel² (quadratic)
```

### Setting Energy Calibration

```kotlin
val data = packF32LE(a0) + packF32LE(a1) + packF32LE(a2)
execute(WR_VIRT_STRING, packU32LE(VS.ENERGY_CALIB) + packU32LE(data.size) + data)
```

---

## Implementation Notes

### Firmware Version Compatibility

The Python library requires firmware version ≥ 4.8. Check via:

```kotlin
val (bootVer, targetVer) = fw_version()
val (major, minor, date) = targetVer
if (major < 4 || (major == 4 && minor < 8)) {
    error("Firmware too old")
}
```

### Polling Strategy

For real-time updates:
- Poll DATA_BUF every 1-2 seconds
- DATA_BUF returns all records since last read (automatically clears)
- Look for most recent GRP_RealTimeData and GRP_RareData records

### Handling Disconnections

- BLE connections can drop unexpectedly
- Implement automatic reconnection with exponential backoff
- Device remains operational and accumulates data when disconnected

### Unit Conversions

| From | To | Multiply By |
|------|-----|-------------|
| Sv/h (DoseRate) | µSv/h | 10,000 |
| µR/h | µSv/h | 0.01 (approximately) |
| counts/10s | CPS | 0.1 |
| counts/10s | CPM | 6 |

### Battery/Temperature Availability

GRP_RareData records are sent less frequently than GRP_RealTimeData (hence "rare"). Your app should:
1. Store the latest values when received
2. Display cached values between updates
3. Handle null/missing metadata gracefully

---

## Appendix: Quick Reference

### Most Common Operations

| Task | Method |
|------|--------|
| Get real-time radiation | Read VS.DATA_BUF, parse GRP_RealTimeData |
| Get battery/temperature | Read VS.DATA_BUF, parse GRP_RareData |
| Get spectrum | Read VS.SPECTRUM |
| Reset dose | Write VSFR.DOSE_RESET |
| Reset spectrum | Write to VS.SPECTRUM with length 0 |
| Set brightness | Write VSFR.DISP_BRT |
| Set language | Write VSFR.DEVICE_LANG |
| Power off | Write VSFR.DEVICE_ON = 0 |

### Byte Sizes Summary

| Record Type | Header | Data | Total |
|-------------|--------|------|-------|
| GRP_RealTimeData | 7 | 15 | 22 |
| GRP_RawData | 7 | 8 | 15 |
| GRP_DoseRateDB | 7 | 16 | 23 |
| GRP_RareData | 7 | 14 | 21 |
| GRP_Event | 7 | 4 | 11 |
| GRP_AccelData | 7 | 6 | 13 |

---

## References

- [cdump/radiacode](https://github.com/cdump/radiacode) - Official Python library
- RadiaCode official app and documentation

---

*This document is part of the Open RadiaCode Android project.*
