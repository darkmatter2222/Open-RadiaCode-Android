#include "session_store.h"
#include "config.h"

#include <LittleFS.h>
#include <time.h>

namespace {
String makeSessionId() {
    // YYYYMMDD_HHMMSS using system time, falling back to millis if unset.
    time_t now = time(nullptr);
    if (now > 1700000000) {
        struct tm tmv;
        gmtime_r(&now, &tmv);
        char buf[24];
        snprintf(buf, sizeof(buf), "%04d%02d%02d_%02d%02d%02d",
                 tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                 tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
        return String(buf);
    }
    char buf[24];
    snprintf(buf, sizeof(buf), "boot_%lu", (unsigned long)millis());
    return String(buf);
}

String pathFor(const String& id) {
    return String(cfg::SESSIONS_DIR) + "/" + id + ".csv";
}
} // namespace

bool SessionStore::begin() {
    if (!LittleFS.begin(true)) {
        log_e("LittleFS mount failed even after format");
        return false;
    }
    if (!LittleFS.exists(cfg::SESSIONS_DIR)) {
        LittleFS.mkdir(cfg::SESSIONS_DIR);
    }
    return true;
}

bool SessionStore::resumeIfActive() {
    if (!LittleFS.exists(cfg::ACTIVE_FILE)) return false;
    File f = LittleFS.open(cfg::ACTIVE_FILE, "r");
    if (!f) return false;
    activeId_ = f.readString();
    activeId_.trim();
    f.close();
    if (!activeId_.length()) return false;
    if (!LittleFS.exists(pathFor(activeId_))) {
        LittleFS.remove(cfg::ACTIVE_FILE);
        activeId_ = "";
        return false;
    }
    recording_ = true;

    // Recompute sample count by scanning lines (skip header)
    File data = LittleFS.open(pathFor(activeId_), "r");
    if (data) {
        sampleCount_ = 0;
        while (data.available()) {
            data.readStringUntil('\n');
            ++sampleCount_;
        }
        if (sampleCount_ > 0) --sampleCount_; // header
        data.close();
    }
    return true;
}

bool SessionStore::start() {
    if (recording_) stop();
    activeId_ = makeSessionId();
    sampleCount_ = 0;

    File f = LittleFS.open(pathFor(activeId_), "w", true);
    if (!f) { log_e("open session file failed"); activeId_ = ""; return false; }
    f.println(F("timestampMs,uSvPerHour,cps,latitude,longitude,deviceId"));
    f.close();

    File a = LittleFS.open(cfg::ACTIVE_FILE, "w", true);
    if (a) { a.print(activeId_); a.close(); }

    recording_ = true;
    log_i("Session started: %s", activeId_.c_str());
    return true;
}

bool SessionStore::stop() {
    if (!recording_) return false;
    recording_ = false;
    LittleFS.remove(cfg::ACTIVE_FILE);
    log_i("Session stopped: %s (%u samples)", activeId_.c_str(), (unsigned)sampleCount_);
    return true;
}

bool SessionStore::toggle() {
    if (recording_) { stop(); return false; }
    return start();
}

void SessionStore::append(uint32_t /*tsLow*/, uint64_t timestampMsFull,
                          float uSvPerHour, float cps,
                          bool hasGps, double lat, double lng,
                          const String& deviceId) {
    if (!recording_ || !activeId_.length()) return;

    File f = LittleFS.open(pathFor(activeId_), "a");
    if (!f) { log_w("append: open failed"); return; }

    char line[cfg::MAX_LINE_BYTES];
    if (hasGps) {
        snprintf(line, sizeof(line), "%llu,%.6f,%.3f,%.7f,%.7f,%s\n",
                 (unsigned long long)timestampMsFull,
                 uSvPerHour, cps, lat, lng, deviceId.c_str());
    } else {
        snprintf(line, sizeof(line), "%llu,%.6f,%.3f,,,%s\n",
                 (unsigned long long)timestampMsFull,
                 uSvPerHour, cps, deviceId.c_str());
    }
    f.print(line);
    f.close();
    ++sampleCount_;
}

size_t SessionStore::totalBytes() const { return LittleFS.totalBytes(); }
size_t SessionStore::usedBytes() const  { return LittleFS.usedBytes(); }

int SessionStore::percentUsed() const {
    const size_t t = totalBytes();
    if (!t) return 0;
    return (int)((usedBytes() * 100ULL) / t);
}

int SessionStore::sessionCount() const {
    File dir = LittleFS.open(cfg::SESSIONS_DIR);
    if (!dir || !dir.isDirectory()) return 0;
    int n = 0;
    File f = dir.openNextFile();
    while (f) {
        if (!f.isDirectory()) ++n;
        f = dir.openNextFile();
    }
    return n;
}
