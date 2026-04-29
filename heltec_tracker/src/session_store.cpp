#include "session_store.h"
#include "config.h"

#include <LittleFS.h>
#include <SD.h>
#include <SPI.h>
#include <algorithm>
#include <time.h>

namespace {
// Dedicated SPI bus for the SD card (HSPI on the S3). The TFT lives on a
// separate set of GPIOs (38-42), so the buses don't collide.
SPIClass gSdSpi(HSPI);
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
    fs_ = nullptr;
    backend_ = Backend::None;
    cardSizeMb_ = 0;

    // ---- Try SD first ------------------------------------------------------
    if (cfg::SD_ENABLED) {
        Serial.printf("[SD] init: SCK=%u MISO=%u MOSI=%u CS=%u (init@1MHz)\n",
                      cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN,
                      cfg::SD_CS_PIN);
        // Cheap HW-125 modules + jumper wires often need an explicit pullup
        // on MISO so the line doesn't float when the card hasn't asserted it.
        pinMode(cfg::SD_MISO_PIN, INPUT_PULLUP);
        gSdSpi.begin(cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
        // Drive CS high before the first transaction so the card sees a clean
        // CS edge on its very first command.
        pinMode(cfg::SD_CS_PIN, OUTPUT);
        digitalWrite(cfg::SD_CS_PIN, HIGH);

        // Start at a conservative clock; jumper-wire setups rarely tolerate
        // 20MHz on first contact. We can raise it once mounted if we want.
        // Each attempt requires SD.end() between calls because the FATFS
        // driver caches state from failed mounts.
        auto tryMount = [&](uint32_t hz, bool formatIfEmpty) {
            SD.end();
            return SD.begin(cfg::SD_CS_PIN, gSdSpi, hz, "/sd", 5, formatIfEmpty);
        };
        bool mounted = tryMount(1000000, false);
        if (!mounted) {
            Serial.println("[SD] 1MHz init failed, retrying at 400kHz");
            mounted = tryMount(400000, false);
        }
        if (!mounted) {
            // Card may respond at SPI level but have an unreadable filesystem
            // (unformatted, exFAT on SDXC, corrupted). Let FATFS reformat.
            Serial.println("[SD] still failed, retrying at 400kHz with format-if-empty");
            mounted = tryMount(400000, true);
        }
        if (!mounted) {
            Serial.println("[SD] retrying at 1MHz with format-if-empty");
            mounted = tryMount(1000000, true);
        }
        if (!mounted) {
            Serial.println("[SD] retrying at 4MHz with format-if-empty");
            mounted = tryMount(4000000, true);
        }
        if (mounted) {
            uint8_t cardType = SD.cardType();
            const char* typeName = "UNKNOWN";
            switch (cardType) {
                case CARD_NONE:  typeName = "NONE";  break;
                case CARD_MMC:   typeName = "MMC";   break;
                case CARD_SD:    typeName = "SD";    break;
                case CARD_SDHC:  typeName = "SDHC";  break;
            }
            cardSizeMb_ = SD.cardSize() / (1024ULL * 1024ULL);
            Serial.printf("[SD] mounted: type=%s size=%lluMB total=%lluMB used=%lluMB\n",
                          typeName, (unsigned long long)cardSizeMb_,
                          (unsigned long long)(SD.totalBytes()  / (1024ULL*1024ULL)),
                          (unsigned long long)(SD.usedBytes()   / (1024ULL*1024ULL)));
            if (cardType == CARD_NONE) {
                Serial.println("[SD] CARD_NONE after mount, falling back to LittleFS");
                SD.end();
                gSdSpi.end();
            } else {
                fs_ = &SD;
                backend_ = Backend::Sd;
                if (!SD.exists(cfg::SESSIONS_DIR)) {
                    SD.mkdir(cfg::SESSIONS_DIR);
                }
                return true;
            }
        } else {
            Serial.println("[SD] mount failed; falling back to LittleFS");
            gSdSpi.end();
        }
    }

    // ---- Fallback: LittleFS -----------------------------------------------
    // Our partition table labels the LittleFS partition "littlefs" (subtype
    // "spiffs" because LittleFS reuses the SPIFFS subtype on ESP-IDF). The
    // Arduino LittleFS wrapper defaults to label "spiffs" — pass our actual
    // label so the mount succeeds.
    if (!LittleFS.begin(true, "/littlefs", 10, "littlefs")) {
        log_e("LittleFS mount failed even after format");
        return false;
    }
    if (!LittleFS.exists(cfg::SESSIONS_DIR)) {
        LittleFS.mkdir(cfg::SESSIONS_DIR);
    }
    fs_ = &LittleFS;
    backend_ = Backend::LittleFs;
    return true;
}

const char* SessionStore::backendName() const {
    switch (backend_) {
        case Backend::Sd:       return "SD";
        case Backend::LittleFs: return "LittleFS";
        default:                return "none";
    }
}

bool SessionStore::resumeIfActive() {
    if (!fs_) return false;
    if (!fs_->exists(cfg::ACTIVE_FILE)) return false;
    File f = fs_->open(cfg::ACTIVE_FILE, "r");
    if (!f) return false;
    activeId_ = f.readString();
    activeId_.trim();
    f.close();
    if (!activeId_.length()) return false;
    if (!fs_->exists(pathFor(activeId_))) {
        fs_->remove(cfg::ACTIVE_FILE);
        activeId_ = "";
        return false;
    }
    recording_ = true;

    // Recompute sample count by scanning lines (skip header)
    File data = fs_->open(pathFor(activeId_), "r");
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
    if (!fs_) { log_e("start: no backend"); return false; }
    if (recording_) stop();
    activeId_ = makeSessionId();
    sampleCount_ = 0;

    File f = fs_->open(pathFor(activeId_), "w", true);
    if (!f) { log_e("open session file failed"); activeId_ = ""; return false; }
    f.println(F("timestampMs,uSvPerHour,cps,latitude,longitude,deviceId"));
    f.close();

    File a = fs_->open(cfg::ACTIVE_FILE, "w", true);
    if (a) { a.print(activeId_); a.close(); }

    recording_ = true;
    log_i("Session started: %s", activeId_.c_str());
    return true;
}

bool SessionStore::stop() {
    if (!recording_) return false;
    recording_ = false;
    if (fs_) fs_->remove(cfg::ACTIVE_FILE);
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
    if (!recording_ || !activeId_.length() || !fs_) return;

    File f = fs_->open(pathFor(activeId_), "a");
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

size_t SessionStore::totalBytes() const {
    // SD reports values much larger than 32 bits; we clamp to size_t for the UI.
    if (backend_ == Backend::Sd)       return (size_t)std::min<uint64_t>(SD.totalBytes(),       SIZE_MAX);
    if (backend_ == Backend::LittleFs) return LittleFS.totalBytes();
    return 0;
}
size_t SessionStore::usedBytes() const {
    if (backend_ == Backend::Sd)       return (size_t)std::min<uint64_t>(SD.usedBytes(),        SIZE_MAX);
    if (backend_ == Backend::LittleFs) return LittleFS.usedBytes();
    return 0;
}

int SessionStore::percentUsed() const {
    const size_t t = totalBytes();
    if (!t) return 0;
    return (int)((usedBytes() * 100ULL) / t);
}

int SessionStore::sessionCount() const {
    if (!fs_) return 0;
    File dir = fs_->open(cfg::SESSIONS_DIR);
    if (!dir || !dir.isDirectory()) return 0;
    int n = 0;
    File f = dir.openNextFile();
    while (f) {
        if (!f.isDirectory()) ++n;
        f = dir.openNextFile();
    }
    return n;
}

// ---------------- export / wipe ------------------------------------------

namespace {
String stripCsvSuffix(const String& fname) {
    if (fname.endsWith(".csv")) return fname.substring(0, fname.length() - 4);
    return fname;
}
String fileBaseName(const String& path) {
    int slash = path.lastIndexOf('/');
    return (slash >= 0) ? path.substring(slash + 1) : path;
}
} // namespace

std::vector<SessionStore::SessionInfo> SessionStore::listSessions() const {
    std::vector<SessionInfo> out;
    if (!fs_) return out;
    File dir = fs_->open(cfg::SESSIONS_DIR);
    if (!dir || !dir.isDirectory()) return out;

    File f = dir.openNextFile();
    while (f) {
        if (!f.isDirectory()) {
            String name = fileBaseName(String(f.name()));
            if (name.endsWith(".csv")) {
                SessionInfo info;
                info.id        = stripCsvSuffix(name);
                info.sizeBytes = f.size();
                info.samples   = 0;
                // Cheap line count. Re-open for a separate read pass since
                // the directory's File handle is positioned at metadata.
                File data = fs_->open(String(cfg::SESSIONS_DIR) + "/" + name, "r");
                if (data) {
                    while (data.available()) {
                        data.readStringUntil('\n');
                        ++info.samples;
                    }
                    if (info.samples > 0) --info.samples;   // header
                    data.close();
                }
                out.push_back(info);
            }
        }
        f = dir.openNextFile();
    }
    return out;
}

bool SessionStore::dumpSession(const String& id, Stream& out) const {
    if (!fs_) {
        out.printf("[DUMP-ERR] id=%s reason=no-backend\n", id.c_str());
        return false;
    }
    String path = String(cfg::SESSIONS_DIR) + "/" + id + ".csv";
    File f = fs_->open(path, "r");
    if (!f) {
        out.printf("[DUMP-ERR] id=%s reason=open-failed\n", id.c_str());
        return false;
    }

    // Re-count samples for the header so the host can verify byte/sample
    // integrity after streaming.
    uint32_t samples = 0;
    File counter = fs_->open(path, "r");
    if (counter) {
        while (counter.available()) {
            counter.readStringUntil('\n');
            ++samples;
        }
        if (samples > 0) --samples;
        counter.close();
    }

    out.printf("[DUMP-BEGIN] id=%s bytes=%u samples=%u\n",
               id.c_str(), (unsigned)f.size(), (unsigned)samples);
    // Stream raw bytes verbatim. The host side reads until [DUMP-END].
    uint8_t buf[256];
    while (f.available()) {
        size_t n = f.read(buf, sizeof(buf));
        if (n > 0) out.write(buf, n);
        // Tiny yield so the BLE stack & WDT keep running on big files.
        yield();
    }
    f.close();
    // Make sure the final line has a terminating newline so the marker
    // appears on its own line regardless of CSV trailing state.
    out.print('\n');
    out.printf("[DUMP-END] id=%s\n", id.c_str());
    return true;
}

void SessionStore::dumpAll(Stream& out) const {
    auto sessions = listSessions();
    out.printf("[DUMP-ALL-BEGIN] count=%u\n", (unsigned)sessions.size());
    uint32_t ok = 0;
    for (const auto& s : sessions) {
        // Skip the active session's tail-of-write hazard by closing append
        // handles between rows -- our append() already does that, so dump
        // is safe to run concurrently with logging.
        if (dumpSession(s.id, out)) ++ok;
    }
    out.printf("[DUMP-DONE] ok=%u total=%u\n", (unsigned)ok, (unsigned)sessions.size());
}

uint32_t SessionStore::wipeAll() {
    if (recording_) stop();
    if (!fs_) return 0;
    fs_->remove(cfg::ACTIVE_FILE);

    uint32_t removed = 0;
    File dir = fs_->open(cfg::SESSIONS_DIR);
    if (!dir || !dir.isDirectory()) return 0;

    // Two-pass: collect names first, then remove. Removing while iterating
    // openNextFile() is undefined behaviour on LittleFS.
    std::vector<String> paths;
    File f = dir.openNextFile();
    while (f) {
        if (!f.isDirectory()) {
            paths.push_back(String(cfg::SESSIONS_DIR) + "/" + fileBaseName(String(f.name())));
        }
        f = dir.openNextFile();
    }
    for (const auto& p : paths) {
        if (fs_->remove(p)) ++removed;
    }

    activeId_    = "";
    sampleCount_ = 0;
    return removed;
}

bool SessionStore::removeSession(const String& id) {
    if (id.length() == 0 || !fs_) return false;
    if (recording_ && activeId_ == id) return false;   // refuse to delete active
    return fs_->remove(pathFor(id));
}

File SessionStore::openForRead(const String& id) const {
    if (!fs_) return File();
    return fs_->open(pathFor(id), "r");
}
