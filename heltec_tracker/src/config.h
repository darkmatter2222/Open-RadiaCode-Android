// Heltec HTIT-Tracker V1.2 board config.
// Pin assignments mirror darkmatter2222/External-GPS-Receiver-Heltec-HTIT-Tracker-V1.2,
// which is a known-working layout for this exact carrier board.

#pragma once
#include <Arduino.h>

namespace cfg {

// ---------------- TFT (ST7735, 128x160 portrait native; we use landscape) -----
constexpr uint8_t  TFT_CS   = 5;
constexpr uint8_t  TFT_DC   = 27;
constexpr uint8_t  TFT_RST  = 26;
// SPI uses default ESP32-S3 HW SPI pins (SCK/MOSI handled by Adafruit_ST7735).
constexpr uint8_t  TFT_ROTATION = 3;       // landscape
constexpr uint16_t TFT_W = 160;
constexpr uint16_t TFT_H = 128;

// ---------------- GPS (UC6580 over UART2) -------------------------------------
constexpr int      GPS_UART_NUM = 1;       // HardwareSerial(1)
constexpr uint8_t  GPS_RX_PIN = 33;        // ESP RX  <-- GPS TX
constexpr uint8_t  GPS_TX_PIN = 34;        // ESP TX  --> GPS RX
constexpr uint32_t GPS_BAUD   = 115200;    // UC6580 default after auto-detect
constexpr uint32_t GPS_FALLBACK_BAUDS[] = {115200, 9600, 38400, 57600};

// ---------------- Power / peripheral rails (Heltec V3) ------------------------
constexpr uint8_t  VEXT_CTRL_PIN = 36;     // active LOW = peripherals powered
constexpr uint8_t  VBAT_ADC_CTRL = 37;     // active LOW = enable VBat divider
constexpr uint8_t  VBAT_ADC_PIN  = 1;      // ADC1_CH0
constexpr float    VBAT_DIV_MULT = 5.05f;  // Heltec recommended

// ---------------- Button (PRG) ------------------------------------------------
constexpr uint8_t  BUTTON_PIN = 0;         // active LOW
constexpr uint16_t BUTTON_DEBOUNCE_MS = 30;
constexpr uint16_t BUTTON_LONG_PRESS_MS = 800;

// ---------------- RadiaCode polling -------------------------------------------
constexpr uint32_t RADIACODE_POLL_MS = 1000;     // matches Android (~1 Hz)
constexpr uint32_t RADIACODE_SCAN_MS = 8000;
constexpr uint32_t RADIACODE_RECONNECT_MS = 5000;

// ---------------- Storage -----------------------------------------------------
constexpr const char* SESSIONS_DIR    = "/sessions";
constexpr const char* ACTIVE_FILE     = "/active.txt";   // current session id
constexpr size_t      MAX_LINE_BYTES  = 160;

// ---------------- App ---------------------------------------------------------
constexpr uint32_t UI_TICK_MS = 100;
constexpr uint32_t HEARTBEAT_MS = 3000;
constexpr const char* FW_VERSION = "0.1.0";

} // namespace cfg
