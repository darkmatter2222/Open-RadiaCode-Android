// Heltec HTIT-Tracker board config (supports V1.2 and V2 via build flags).
// Pin assignments mirror darkmatter2222/External-GPS-Receiver-Heltec-HTIT-Tracker-V1.2
// for the V1.2 layout (known-working reference). V2-specific overrides are
// selected by TRACKER_HW_V2 in platformio.ini.

#pragma once
#include <Arduino.h>

namespace cfg {

// ---------------- Hardware revision -------------------------------------------
// Selected by build_flags in platformio.ini.  Default to V1.2 if neither flag
// is supplied so legacy build commands keep working.
#if !defined(TRACKER_HW_V2) && !defined(TRACKER_HW_V1_2)
#define TRACKER_HW_V1_2 1
#endif

// ---------------- TFT (ST7735, 0.96" 160x80) ---------------------------------
// Same physical pins on both V1.2 and V2 -- only the panel init differs.
constexpr uint8_t  TFT_CS    = 38;
constexpr uint8_t  TFT_RST   = 39;
constexpr uint8_t  TFT_DC    = 40;
constexpr uint8_t  TFT_SCLK  = 41;
constexpr uint8_t  TFT_MOSI  = 42;
constexpr uint8_t  TFT_ROTATION = 1;       // landscape, ribbon at right
constexpr uint16_t TFT_W = 160;
constexpr uint16_t TFT_H = 80;
#if defined(TRACKER_HW_V2)
// HTIT-Tracker V2: Heltec's HT_st7735.h sets XSTART=0/YSTART=24 and calls
// st7735_invert_colors(false) when WIRELESS_TRACKER_V2 is defined.
constexpr uint8_t  TFT_X_OFFSET = 0;
constexpr uint8_t  TFT_Y_OFFSET = 24;
constexpr bool     TFT_INVERT   = false;
#else
// HTIT-Tracker V1.2: 160x80 mini panel offsets, color inversion enabled.
constexpr uint8_t  TFT_X_OFFSET = 1;
constexpr uint8_t  TFT_Y_OFFSET = 26;
constexpr bool     TFT_INVERT   = true;
#endif

// ---------------- GPS (UC6580 over UART2) -------------------------------------
constexpr int      GPS_UART_NUM = 1;       // HardwareSerial(1)
constexpr uint8_t  GPS_RX_PIN = 33;        // ESP RX  <-- GPS TX
constexpr uint8_t  GPS_TX_PIN = 34;        // ESP TX  --> GPS RX
constexpr uint32_t GPS_BAUD   = 115200;    // UC6580 default after auto-detect
constexpr uint32_t GPS_FALLBACK_BAUDS[] = {115200, 9600, 38400, 57600};

// ---------------- Power / peripheral rails (HTIT-Tracker V1.2) -----------------
// On the HTIT-Tracker V1.2 carrier the GNSS module and TFT share a 3.3V rail
// gated by VTFT_CTRL on GPIO 3. The Heltec HT_st7735 library drives this pin
// HIGH inside st7735_init() to enable the rail, and the working reference
// firmware (darkmatter2222) relies on that behaviour. So: HIGH = powered.
// The battery divider is on GPIO 2 (HIGH = enable). Backlight on GPIO 21.
constexpr uint8_t  VGNSS_CTRL_PIN = 3;     // HIGH = GPS+TFT powered
constexpr uint8_t  BL_CTRL_PIN    = 21;    // HIGH = backlight on
constexpr uint8_t  VBAT_EN_PIN    = 2;     // HIGH during ADC read
constexpr uint8_t  VBAT_ADC_PIN   = 1;     // ADC1_CH0
constexpr float    VBAT_DIV_MULT  = 5.05f; // empirically tuned (Heltec V3)

// ---------------- Button (PRG) ------------------------------------------------
constexpr uint8_t  BUTTON_PIN = 0;         // active LOW
constexpr uint16_t BUTTON_DEBOUNCE_MS = 30;
constexpr uint16_t BUTTON_LONG_PRESS_MS = 800;

// ---------------- RadiaCode polling -------------------------------------------
// Poll interval. Matches Android (~1 Hz). Going slower (3s) was observed to
// make the RadiaCode-110 drop the link almost immediately after Ready --
// the peer apparently expects continuous client activity to keep the
// connection alive.
constexpr uint32_t RADIACODE_POLL_MS = 1000;
constexpr uint32_t RADIACODE_SCAN_MS = 8000;
constexpr uint32_t RADIACODE_RECONNECT_MS = 5000;

// ---------------- Storage -----------------------------------------------------
constexpr const char* SESSIONS_DIR    = "/sessions";
constexpr const char* ACTIVE_FILE     = "/active.txt";   // current session id
constexpr size_t      MAX_LINE_BYTES  = 160;

// ---------------- SD card (HW-125 micro-SD breakout, SPI mode) ----------------
// Wiring (see heltec_tracker/AGENTS.md for the full table):
//   HW-125 GND  -> Heltec GND
//   HW-125 VCC  -> Heltec 5V   (NOT 3V3 -- AMS1117 LDO needs 5V input;
//                                card gets ~2V at 3V3 and will not respond)
//   HW-125 MISO -> GPIO 4
//   HW-125 MOSI -> GPIO 6
//   HW-125 SCK  -> GPIO 5
//   HW-125 CS   -> GPIO 7
// Dedicated SPI bus (HSPI), independent from the TFT bus on GPIO 38-42.
//
// V1.2 boards in the field have an HW-125 micro-SD breakout wired to
// GPIO 4/5/6/7. V2 boards in this project ship without an SD breakout, so
// we skip the SD probe entirely on V2 builds and go straight to the
// on-chip LittleFS partition. Otherwise the boot stalls for ~60 s in the
// SdFat retry loop before the UI ever paints.
#if defined(TRACKER_HW_V2)
constexpr bool     SD_ENABLED   = false;
constexpr bool     SD_REQUIRED  = false;
#else
constexpr bool     SD_ENABLED   = true;
// If true (default), the firmware REFUSES to fall back to on-chip LittleFS
// when the SD card can't be mounted. Recording stays disabled and the UI
// shows "STORAGE INIT FAILED -- REBOOT" until the user power-cycles. This
// is the safe choice for field collection: a silent fallback to the 1.5MB
// internal partition has historically caused users to lose hours of data
// thinking the SD card was being written. Set to false only if you want
// internal-only operation as a deliberate fallback.
constexpr bool     SD_REQUIRED  = true;
#endif
// On cold-boot from battery the HW-125's onboard LDO sometimes needs a few
// hundred ms longer than on USB power before the card responds. Retry the
// SdFat mount up to this many times with a short gap between attempts.
constexpr uint8_t  SD_INIT_RETRIES = 6;
constexpr uint16_t SD_INIT_RETRY_GAP_MS = 250;
// Wiring: HW-125 MISO -> GPIO 4, MOSI -> GPIO 6 (see heltec_tracker/AGENTS.md).
constexpr uint8_t  SD_MISO_PIN  = 4;
constexpr uint8_t  SD_MOSI_PIN  = 6;
constexpr uint8_t  SD_SCK_PIN   = 5;
constexpr uint8_t  SD_CS_PIN    = 7;
constexpr uint32_t SD_SPI_HZ    = 20000000;     // 20 MHz; back off to 4 MHz on poor cards

// ---------------- App ---------------------------------------------------------
constexpr uint32_t UI_TICK_MS = 100;
constexpr uint32_t HEARTBEAT_MS = 3000;
constexpr const char* FW_VERSION = "0.2.0";

} // namespace cfg
