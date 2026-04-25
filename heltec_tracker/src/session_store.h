#pragma once
#include <Arduino.h>

// CSV session writer.
// File format matches Android `SessionDataPoint.toCsv()`:
//   timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
// One file per session at /sessions/<id>.csv on LittleFS.

class SessionStore {
public:
    bool begin();   // mounts LittleFS (formats on first failure)

    bool isRecording() const { return recording_; }
    const String& activeId() const { return activeId_; }
    uint32_t sampleCount() const { return sampleCount_; }

    bool start();           // creates new session, sets active
    bool stop();            // ends recording
    bool toggle();          // flips state, returns new state
    bool resumeIfActive();  // re-opens last active session if marked active

    // Append one row. Drops silently if not recording.
    void append(uint32_t timestampMsLow,    // for legacy tests
                uint64_t timestampMsFull,
                float uSvPerHour,
                float cps,
                bool hasGps, double lat, double lng,
                const String& deviceId);

    // Storage stats
    size_t totalBytes() const;
    size_t usedBytes() const;
    int    percentUsed() const;
    int    sessionCount() const;

private:
    bool   recording_   = false;
    String activeId_;
    uint32_t sampleCount_ = 0;
};
