// Heltec HTIT-Tracker V1.2 board config.
// Pin assignments mirror darkmatter2222/External-GPS-Receiver-Heltec-HTIT-Tracker-V1.2,
// which is a known-working layout for this exact carrier board.

#pragma once
#include <Arduino.h>

namespace cfg {

// ---------------- TFT (ST7735, 0.96" 160x80 on HTIT-Tracker V1.2) -------------
// Pin map matches Heltec HT_st7735 defaults for this carrier board.
constexpr uint8_t  TFT_CS    = 38;
constexpr uint8_t  TFT_RST   = 39;
constexpr uint8_t  TFT_DC    = 40;
constexpr uint8_t  TFT_SCLK  = 41;
constexpr uint8_t  TFT_MOSI  = 42;
constexpr uint8_t  TFT_ROTATION = 1;       // landscape, ribbon at right
constexpr uint16_t TFT_W = 160;
constexpr uint16_t TFT_H = 80;
constexpr uint8_t  TFT_X_OFFSET = 1;       // ST7735S 160x80 mini panel offset
constexpr uint8_t  TFT_Y_OFFSET = 26;

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
//   HW-125 VCC  -> Heltec 3V3
//   HW-125 MISO -> GPIO 4
//   HW-125 MOSI -> GPIO 6
//   HW-125 SCK  -> GPIO 5
//   HW-125 CS   -> GPIO 7
// Dedicated SPI bus (HSPI), independent from the TFT bus on GPIO 38-42.
constexpr bool     SD_ENABLED   = true;
// Wiring: HW-125 MISO -> GPIO 4, MOSI -> GPIO 6 (see heltec_tracker/AGENTS.md).
constexpr uint8_t  SD_MISO_PIN  = 4;
constexpr uint8_t  SD_MOSI_PIN  = 6;
constexpr uint8_t  SD_SCK_PIN   = 5;
constexpr uint8_t  SD_CS_PIN    = 7;
constexpr uint32_t SD_SPI_HZ    = 20000000;     // 20 MHz; back off to 4 MHz on poor cards

// ---------------- App ---------------------------------------------------------
constexpr uint32_t UI_TICK_MS = 100;
constexpr uint32_t HEARTBEAT_MS = 3000;
constexpr const char* FW_VERSION = "0.1.0";

} // namespace cfg
