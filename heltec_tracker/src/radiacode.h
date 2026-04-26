#pragma once
#include <Arduino.h>
#include <functional>
#include <vector>
#include <string>

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

    struct ScanResult {
        std::string address;     // e.g. "52:43:06:60:20:24"
        std::string name;        // advertised name (may be empty)
        int         rssi = 0;
        uint8_t     addrType = 0;          // BLE_ADDR_PUBLIC=0 / RANDOM=1 / etc.
        bool        likelyMatch = false;  // name matched RadiaCode* or RadiaCode service UUID present
    };

    using ReadingCb = std::function<void(const Reading&)>;
    using StateCb   = std::function<void(State, const String& addr)>;

    void begin(ReadingCb onReading, StateCb onState);
    void loop();

    // Picker flow:
    //   1) Caller invokes startManualScan(durMs). State -> Scanning. Auto-connect
    //      is suppressed for this scan; results accumulate in getScanResults().
    //   2) When millis() >= scanDeadline (poll via isManualScanComplete()),
    //      caller fetches getScanResults() and shows a picker UI.
    //   3) Caller invokes connectTo(address) to start the connect flow.
    //   4) cancelManualScan() reverts to normal auto-reconnect behaviour.
    void startManualScan(uint32_t durMs);
    bool isManualScanComplete() const;
    bool isManualScanActive()   const;
    const std::vector<ScanResult>& getScanResults() const;
    bool connectTo(const std::string& address);
    bool connectTo(const std::string& address, uint8_t addrType);
    void cancelManualScan();

    // Trigger an automatic scan-and-connect (auto-pick strongest match).
    // Used as the default behaviour when there's no saved peer.
    void requestScan();
    void disconnectAndForget();
    // Disconnect current peer but keep the pinned address so auto-reconnect resumes.
    void disconnectKeepPin();
    // Auto-grab: scanner will immediately pin+connect to ANY connectable
    // peer whose advertised local-name contains this substring (case-
    // insensitive). Designed to race the brief connectable window of
    // bonded RadiaCode-110 units. Empty pattern = clear the grab.
    void setNameGrabPattern(const std::string& pattern);

    State          state();
    const String&  peerAddress();
    const String&  peerName();
    int            rssi();
};
