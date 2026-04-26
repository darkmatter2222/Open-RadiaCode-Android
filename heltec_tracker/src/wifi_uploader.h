// =============================================================================
// HTIT-Tracker Wi-Fi uploader.
//
// On a fixed cadence (secrets::UPLOAD_INTERVAL_MS) this module:
//   1. If the configured Wi-Fi SSID is empty, does nothing.
//   2. If there are no completed sessions to upload, does nothing.
//   3. Otherwise associates with the SSID, POSTs each session as raw CSV
//      to secrets::INGEST_URL, and on HTTP 2xx removes the session from
//      LittleFS. The currently-recording session (if any) is left alone.
//   4. Disconnects Wi-Fi when finished to save power and avoid Wi-Fi/BLE
//      coexistence noise.
//
// Public surface is intentionally tiny: begin() once in setup(), tick()
// every loop iteration. Everything is non-blocking aside from the actual
// HTTPClient.POST which runs on the main thread for ~1-3s per session.
// =============================================================================
#pragma once
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

class SessionStore;

class WifiUploader {
public:
    void begin(SessionStore* store);

    // Backwards-compatible no-op. The actual work runs on a dedicated
    // FreeRTOS task so the main Arduino loop is never blocked by Wi-Fi
    // connects or HTTP POSTs.
    void tick() {}

    // Force an upload cycle as soon as possible. Safe to call from any task.
    void requestNow();

    // Run one full upload cycle synchronously on the calling task. Blocks.
    // Use sparingly (e.g. from the SYNC serial command).
    uint32_t runOnce();

    // Diagnostic accessors. Reads/writes of these 32-bit fields are atomic
    // on Xtensa, so no lock is needed for diagnostics.
    bool      enabled()        const { return enabled_; }
    bool      busy()           const { return busy_; }
    uint32_t  uploadedCount()  const { return uploadedCount_; }
    uint32_t  failedCount()    const { return failedCount_; }
    uint32_t  lastAttemptMs()  const { return lastAttempt_; }
    uint32_t  lastSuccessMs()  const { return lastSuccess_; }
    int       lastHttpStatus() const { return lastHttpStatus_; }

private:
    static void taskTrampoline(void* arg);
    void taskLoop();

    bool connectWifi();
    void disconnectWifi();
    bool uploadOne(const String& sessionId, size_t expectedBytes, uint32_t expectedSamples);

    SessionStore* store_          = nullptr;
    TaskHandle_t  task_           = nullptr;
    volatile bool enabled_        = false;
    volatile bool busy_           = false;
    volatile uint32_t lastAttempt_   = 0;
    volatile uint32_t lastSuccess_   = 0;
    volatile uint32_t uploadedCount_ = 0;
    volatile uint32_t failedCount_   = 0;
    volatile int      lastHttpStatus_ = 0;
};
