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

class SessionStore;

class WifiUploader {
public:
    void begin(SessionStore* store);
    void tick();   // call every loop iteration

    // Force an upload attempt on the next tick (e.g. when the user presses
    // a "sync now" button or runs the SYNC serial command).
    void requestNow() { forceNow_ = true; }

    // Manually run one full cycle right now (blocks for the duration of the
    // upload). Returns the number of sessions successfully uploaded.
    uint32_t runOnce();

    // Diagnostic accessors.
    bool      enabled()        const { return enabled_; }
    bool      busy()           const { return busy_; }
    uint32_t  uploadedCount()  const { return uploadedCount_; }
    uint32_t  failedCount()    const { return failedCount_; }
    uint32_t  lastAttemptMs()  const { return lastAttempt_; }
    uint32_t  lastSuccessMs()  const { return lastSuccess_; }
    int       lastHttpStatus() const { return lastHttpStatus_; }

private:
    bool connectWifi();
    void disconnectWifi();
    bool uploadOne(const String& sessionId, size_t expectedBytes, uint32_t expectedSamples);
    String trackerId() const;

    SessionStore* store_         = nullptr;
    bool          enabled_       = false;
    bool          busy_          = false;
    bool          forceNow_      = false;
    uint32_t      lastAttempt_   = 0;
    uint32_t      lastSuccess_   = 0;
    uint32_t      uploadedCount_ = 0;
    uint32_t      failedCount_   = 0;
    int           lastHttpStatus_ = 0;
};
