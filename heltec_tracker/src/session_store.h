#pragma once
#include <Arduino.h>
#include <vector>

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

    // Export / wipe -----------------------------------------------------
    struct SessionInfo {
        String   id;          // e.g. "20260426_104210" or "boot_1234567"
        size_t   sizeBytes;
        uint32_t samples;     // line count minus header (if present)
    };
    // Enumerate every CSV under /sessions. Order is whatever LittleFS
    // returns from openNextFile (effectively insertion order).
    std::vector<SessionInfo> listSessions() const;

    // Stream one session over the supplied Stream. Frames the body with
    //   [DUMP-BEGIN] id=<id> bytes=<n> samples=<m>\n
    //   <raw csv...>
    //   [DUMP-END] id=<id>\n
    // Returns true on success.
    bool dumpSession(const String& id, Stream& out) const;

    // Stream every session in turn. Emits a [DUMP-DONE] count=N marker
    // when finished.
    void dumpAll(Stream& out) const;

    // Delete every CSV under /sessions. If the active session is open it
    // is stopped first. Returns number of files removed.
    uint32_t wipeAll();

private:
    bool   recording_   = false;
    String activeId_;
    uint32_t sampleCount_ = 0;
};
