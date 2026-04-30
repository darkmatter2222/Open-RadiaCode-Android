#pragma once
#include <Arduino.h>
#include <FS.h>
#include <vector>

// CSV session writer.
// File format matches Android `SessionDataPoint.toCsv()`:
//   timestampMs,uSvPerHour,cps,latitude,longitude,deviceId
// One file per session at /sessions/<id>.csv on LittleFS.

class SessionStore {
public:
    enum class Backend { None, LittleFs, Sd, SdFat, Failed };

    bool begin();   // mounts SD if enabled+detected, falls back to LittleFS
                    // unless cfg::SD_REQUIRED -- in which case begin()
                    // returns false and backend() is Backend::Failed.

    Backend       backend()     const { return backend_; }
    const char*   backendName() const;
    bool          sdMounted()   const { return backend_ == Backend::Sd || backend_ == Backend::SdFat; }
    // True when SD was required but couldn't be initialized. Recording is
    // refused; UI displays a "please reboot" message.
    bool          storageFailed() const { return backend_ == Backend::Failed; }
    uint64_t      cardSizeMb()  const { return cardSizeMb_; }   // SD only; 0 otherwise

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

    // Delete one session by id. Returns true on success. The active session
    // can NOT be removed (returns false); stop() it first.
    bool removeSession(const String& id);

    // True if `id` is the currently-recording active session.
    bool isActive(const String& id) const { return recording_ && activeId_ == id; }

    // Read a session into a String. Returns false if the file is missing
    // or larger than maxBytes. Backend-aware (SdFat / SD / LittleFS).
    bool readSessionToString(const String& id, size_t maxBytes, String& out) const;

private:
    // True iff a usable backend is mounted (excludes None and Failed).
    bool hasUsableBackend() const {
        return backend_ == Backend::SdFat
            || backend_ == Backend::Sd
            || backend_ == Backend::LittleFs;
    }

    bool   recording_   = false;
    String activeId_;
    uint32_t sampleCount_ = 0;
    Backend  backend_     = Backend::None;
    fs::FS*  fs_          = nullptr;     // -> SD or LittleFS, set in begin()
    uint64_t cardSizeMb_  = 0;            // populated when SD mounts
    bool     sdFatPreflightOk_ = false;   // true if SdFat managed to mount
                                          // the card during the preflight
                                          // diagnostic, even if the stock
                                          // driver subsequently failed
};
