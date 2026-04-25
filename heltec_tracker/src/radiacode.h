#pragma once
#include <Arduino.h>
#include <functional>

// RadiaCode BLE central client.
// Mirrors the Android `RadiacodeBleClient` / `RadiacodeProtocol` / `RadiacodeDataBuf`
// behaviour: SET_EXCHANGE -> SET_TIME -> WR_VIRT_SFR(DEVICE_TIME=0)
// then poll RD_VIRT_STRING(VS_DATA_BUF) at ~1 Hz and decode realtime records.

class RadiaCode {
public:
    enum class State : uint8_t {
        Idle,
        Scanning,
        Connecting,
        Initializing,
        Ready,
        Disconnected,
    };

    struct Reading {
        bool     valid = false;
        float    cps = 0.0f;
        float    uSvPerHour = 0.0f;
        float    cpsErrPct = 0.0f;
        float    doseErrPct = 0.0f;
        uint8_t  battery = 0;     // 0-100, 0xFF if unknown
        float    tempC = 0.0f;
        bool     hasMetadata = false;
        uint32_t timestampMs = 0; // millis() when received
    };

    using ReadingCb = std::function<void(const Reading&)>;
    using StateCb   = std::function<void(State, const String& addr)>;

    void begin(ReadingCb onReading, StateCb onState);
    void loop();

    // Trigger manual scan. Will replace any in-progress scan.
    void requestScan();
    void disconnectAndForget();

    State          state();
    const String&  peerAddress();
    const String&  peerName();
    int            rssi();
};
