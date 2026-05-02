#include "session_store.h"
#include "config.h"

#include <LittleFS.h>
#include <SD.h>
#include <SD_MMC.h>
#include <SdFat.h>
#include <SPI.h>
#include <algorithm>
#include <time.h>

namespace {
// Dedicated SPI bus for the SD card. On ESP32-S3 the FSPI controller (SPI2)
// has IOMUX fast paths for low-numbered GPIOs (we're on 4-7), and tends to
// be more reliable for SD-over-SPI than the GPIO-matrix-only HSPI (SPI3).
// The TFT, when present, lives on GPIOs 38-42, so buses don't collide.
SPIClass gSdSpi(FSPI);

// SdFat instance for the diagnostic preflight. Templated on FAT16/32/exFAT
// so it handles modern SDHC/SDXC cards.
SdFs gSdFat;

// Send the SD-spec power-up sequence: with CS held HIGH, clock at least 74
// cycles so the card transitions from native-mode into SPI-mode. Many cheap
// cards don't latch onto SPI mode reliably without this. We bit-bang it
// since the SPI peripheral hasn't been configured for transactions yet.
void sdBitBangWakeup(uint8_t sckPin, uint8_t mosiPin, uint8_t csPin) {
    pinMode(sckPin,  OUTPUT);
    pinMode(mosiPin, OUTPUT);
    pinMode(csPin,   OUTPUT);
    digitalWrite(csPin,  HIGH);
    digitalWrite(mosiPin, HIGH);
    digitalWrite(sckPin,  LOW);
    delayMicroseconds(2000);  // Vdd ramp settle
    // 100 cycles, well over the 74 required.
    for (int i = 0; i < 100; ++i) {
        digitalWrite(sckPin, HIGH);
        delayMicroseconds(2);
        digitalWrite(sckPin, LOW);
        delayMicroseconds(2);
    }
    digitalWrite(csPin,  HIGH);
    digitalWrite(mosiPin, HIGH);
}
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
        // ===== Preflight: SdFat (greiman) ===================================
        // SdFat ships its own SPI driver that bypasses ESP-IDF's sd_diskio
        // entirely. The stock arduino-esp32 SD driver has a long history of
        // 'no token received' regressions (see espressif/arduino-esp32#6081
        // and schreibfaul1/ESP32-audioI2S#245). On real-world testing both
        // SD and SD_MMC fail on cards that SdFat handles fine, even with
        // identical wiring and good 5V power. So SdFat is now the primary
        // backend - if it mounts we adopt it directly and skip the broken
        // stock drivers entirely.
        Serial.printf("[SD] trying SdFat on SCK=%u MISO=%u MOSI=%u CS=%u\n",
                      cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN,
                      cfg::SD_CS_PIN);
        pinMode(cfg::SD_MISO_PIN, INPUT_PULLUP);
        sdBitBangWakeup(cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
        gSdSpi.begin(cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
        pinMode(cfg::SD_CS_PIN, OUTPUT);
        digitalWrite(cfg::SD_CS_PIN, HIGH);
        delay(50);
        // Try a few clocks. SdFat takes &gSdSpi so it shares our bus.
        // Once mounted we leave it at the mount clock; session writes are
        // tiny so there's nothing to gain from a faster bus.
        const uint32_t kSdFatClocks[] = { SD_SCK_MHZ(8), SD_SCK_MHZ(4),
                                          SD_SCK_MHZ(1), SD_SCK_HZ(400000) };
        // On battery cold-boot the HW-125 LDO sometimes needs more than 50ms
        // to ramp before the card responds. Retry the entire clock sweep a
        // few times with a short gap so we don't give up too early.
        for (uint8_t attempt = 0; attempt < cfg::SD_INIT_RETRIES; ++attempt) {
            if (attempt > 0) {
                Serial.printf("[SdFat] retry %u/%u after %ums\n",
                              (unsigned)attempt,
                              (unsigned)(cfg::SD_INIT_RETRIES - 1),
                              (unsigned)cfg::SD_INIT_RETRY_GAP_MS);
                delay(cfg::SD_INIT_RETRY_GAP_MS);
                sdBitBangWakeup(cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
            }
            for (uint32_t hz : kSdFatClocks) {
                SdSpiConfig spiCfg(cfg::SD_CS_PIN, SHARED_SPI, hz, &gSdSpi);
                if (gSdFat.begin(spiCfg)) {
                    uint64_t cardSizeBytes =
                        (uint64_t)gSdFat.card()->sectorCount() * 512ULL;
                    cardSizeMb_ = cardSizeBytes / (1024ULL * 1024ULL);
                    Serial.printf("[SdFat] mounted at %u Hz attempt=%u: size=%lluMB fatType=%u\n",
                                  (unsigned)hz, (unsigned)attempt,
                                  (unsigned long long)cardSizeMb_,
                                  (unsigned)gSdFat.fatType());
                    sdFatPreflightOk_ = true;
                    backend_ = Backend::SdFat;
                    fs_ = nullptr;  // SdFat doesn't expose fs::FS
                    if (!gSdFat.exists(cfg::SESSIONS_DIR)) {
                        gSdFat.mkdir(cfg::SESSIONS_DIR);
                    }
                    return true;
                }
                Serial.printf("[SdFat] %u Hz failed: errCode=0x%02X errData=0x%02X (attempt %u)\n",
                              (unsigned)hz, gSdFat.sdErrorCode(), gSdFat.sdErrorData(),
                              (unsigned)attempt);
            }
        }
        Serial.println("[SdFat] mount FAILED across all clocks and retries - "
                       "card not responding on this bus.");

        // ===== Attempt 2: SD_MMC peripheral, 1-bit mode =====================
        // The S3 has a dedicated SDMMC controller that is *not* SPI. It uses
        // a different driver, different DMA path, and different pin signaling
        // than the SD-over-SPI library. Many cards that fail in SPI mode work
        // here because the SDMMC peripheral's clock/timing is more forgiving.
        // 1-bit mode needs only CLK, CMD, D0 -- we map them onto the same
        // physical wires the user already soldered for SPI:
        //   SPI SCK  (GPIO 5) -> MMC CLK
        //   SPI MOSI (GPIO 6) -> MMC CMD
        //   SPI MISO (GPIO 4) -> MMC D0
        // SPI CS    (GPIO 7) is unused by MMC; we tie it HIGH so the card
        // sees a quiet line on that side.
        Serial.printf("[SD] trying SD_MMC 1-bit: CLK=%u CMD=%u D0=%u\n",
                      cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_MISO_PIN);
        pinMode(cfg::SD_CS_PIN, OUTPUT);
        digitalWrite(cfg::SD_CS_PIN, HIGH);
        // setPins on ESP32-S3 takes (clk, cmd, d0) for 1-bit mode.
        if (SD_MMC.setPins(cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_MISO_PIN)) {
            // mode=true selects 1-bit, format_if_mount_failed=true,
            // max_files=5, frequency=BOARD_MAX_SDMMC_FREQ (auto).
            if (SD_MMC.begin("/sdmmc", true, true, SDMMC_FREQ_DEFAULT, 5)) {
                uint8_t cardType = SD_MMC.cardType();
                const char* typeName = "UNKNOWN";
                switch (cardType) {
                    case CARD_NONE:  typeName = "NONE";  break;
                    case CARD_MMC:   typeName = "MMC";   break;
                    case CARD_SD:    typeName = "SD";    break;
                    case CARD_SDHC:  typeName = "SDHC";  break;
                }
                if (cardType != CARD_NONE) {
                    cardSizeMb_ = SD_MMC.cardSize() / (1024ULL * 1024ULL);
                    Serial.printf("[SD_MMC] mounted: type=%s size=%lluMB\n",
                                  typeName, (unsigned long long)cardSizeMb_);
                    fs_ = &SD_MMC;
                    backend_ = Backend::Sd;
                    if (!SD_MMC.exists(cfg::SESSIONS_DIR)) {
                        SD_MMC.mkdir(cfg::SESSIONS_DIR);
                    }
                    return true;
                }
                Serial.println("[SD_MMC] CARD_NONE after mount, ending");
                SD_MMC.end();
            } else {
                Serial.println("[SD_MMC] begin() failed, falling through to SPI");
            }
        } else {
            Serial.println("[SD_MMC] setPins() failed, falling through to SPI");
        }

        // ===== Attempt 2: SD over SPI =======================================
        Serial.printf("[SD] trying SPI: SCK=%u MISO=%u MOSI=%u CS=%u (init@1MHz)\n",
                      cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN,
                      cfg::SD_CS_PIN);
        // Cheap HW-125 modules + jumper wires often need an explicit pullup
        // on MISO so the line doesn't float when the card hasn't asserted it.
        pinMode(cfg::SD_MISO_PIN, INPUT_PULLUP);
        // Spec-mandated SPI-mode power-up sequence: 74+ clocks with CS HIGH.
        sdBitBangWakeup(cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
        gSdSpi.begin(cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
        // Drive CS high before the first transaction so the card sees a clean
        // CS edge on its very first command.
        pinMode(cfg::SD_CS_PIN, OUTPUT);
        digitalWrite(cfg::SD_CS_PIN, HIGH);
        delay(50);  // Let Vdd settle and card finish its internal init.

        // Start at a conservative clock; jumper-wire setups rarely tolerate
        // 20MHz on first contact. We can raise it once mounted if we want.
        // Each attempt requires SD.end() between calls because the FATFS
        // driver caches state from failed mounts. Re-issue the wakeup
        // sequence between attempts to give the card a clean re-init.
        auto tryMount = [&](uint32_t hz, bool formatIfEmpty) {
            SD.end();
            delay(50);
            sdBitBangWakeup(cfg::SD_SCK_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
            gSdSpi.begin(cfg::SD_SCK_PIN, cfg::SD_MISO_PIN, cfg::SD_MOSI_PIN, cfg::SD_CS_PIN);
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

    // ---- SD required gate -------------------------------------------------
    // If the user requires SD (default), refuse to silently fall through to
    // the on-chip 1.5MB LittleFS partition. They want a clear failure state
    // they can react to (reboot / reseat the card) rather than logging
    // hours of field data to a tiny internal partition by accident.
    if (cfg::SD_ENABLED && cfg::SD_REQUIRED) {
        Serial.println("[STORE] FATAL: SD_REQUIRED=true and SD did not mount. "
                       "Recording will stay disabled until reboot.");
        backend_ = Backend::Failed;
        fs_ = nullptr;
        return false;
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
        case Backend::SdFat:    return "SdFat";
        case Backend::LittleFs: return "LittleFS";
        case Backend::Failed:   return "FAILED";
        default:                return "none";
    }
}

bool SessionStore::resumeIfActive() {
    if (!hasUsableBackend()) return false;
    if (backend_ == Backend::SdFat) {
        if (!gSdFat.exists(cfg::ACTIVE_FILE)) return false;
        FsFile f = gSdFat.open(cfg::ACTIVE_FILE, O_RDONLY);
        if (!f) return false;
        char buf[64] = {0};
        int n = f.read(buf, sizeof(buf) - 1);
        f.close();
        if (n <= 0) return false;
        activeId_ = String(buf);
        activeId_.trim();
        if (!activeId_.length()) return false;
        String path = pathFor(activeId_);
        if (!gSdFat.exists(path.c_str())) {
            gSdFat.remove(cfg::ACTIVE_FILE);
            activeId_ = "";
            return false;
        }
        recording_ = true;
        FsFile data = gSdFat.open(path.c_str(), O_RDONLY);
        if (data) {
            sampleCount_ = 0;
            char ch;
            while (data.read(&ch, 1) == 1) {
                if (ch == '\n') ++sampleCount_;
            }
            if (sampleCount_ > 0) --sampleCount_;
            data.close();
        }
        return true;
    }
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
    if (!hasUsableBackend()) { log_e("start: no backend"); return false; }
    if (recording_) stop();
    activeId_ = makeSessionId();
    sampleCount_ = 0;

    if (backend_ == Backend::SdFat) {
        String path = pathFor(activeId_);
        FsFile f = gSdFat.open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC);
        if (!f) { log_e("open session file failed"); activeId_ = ""; return false; }
        f.println("timestampMs,uSvPerHour,cps,latitude,longitude,deviceId");
        f.close();
        FsFile a = gSdFat.open(cfg::ACTIVE_FILE, O_WRONLY | O_CREAT | O_TRUNC);
        if (a) { a.print(activeId_); a.close(); }
        recording_ = true;
        log_i("Session started: %s", activeId_.c_str());
        return true;
    }
    if (!fs_) { log_e("start: no backend"); return false; }

    File f = fs_->open(pathFor(activeId_), "w", true);
    if (!f) { log_e("open session file failed"); activeId_ = ""; return false; }
    f.println(F("timestampMs,uSvPerHour,cps,latitude,longitude,deviceId"));
    f.close();

    File a = fs_->open(cfg::ACTIVE_FILE, "w", true);
    if (a) { a.print(activeId_); a.close(); }

    recording_ = true;
    log_i("Session started: %s", activeId_.c_str());
    Serial.printf("[REC] START: id=%s backend=LittleFS\n", activeId_.c_str());
    return true;
}

bool SessionStore::stop() {
    if (!recording_) return false;
    recording_ = false;
    if (backend_ == Backend::SdFat) {
        gSdFat.remove(cfg::ACTIVE_FILE);
    } else if (fs_) {
        fs_->remove(cfg::ACTIVE_FILE);
    }
    log_i("Session stopped: %s (%u samples)", activeId_.c_str(), (unsigned)sampleCount_);
    Serial.printf("[REC] STOP: id=%s samples=%u\n",
                  activeId_.c_str(), (unsigned)sampleCount_);
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
    if (!hasUsableBackend()) return;

    char line[cfg::MAX_LINE_BYTES];
    int len;
    if (hasGps) {
        len = snprintf(line, sizeof(line), "%llu,%.6f,%.3f,%.7f,%.7f,%s\n",
                       (unsigned long long)timestampMsFull,
                       uSvPerHour, cps, lat, lng, deviceId.c_str());
    } else {
        len = snprintf(line, sizeof(line), "%llu,%.6f,%.3f,,,%s\n",
                       (unsigned long long)timestampMsFull,
                       uSvPerHour, cps, deviceId.c_str());
    }
    if (len <= 0) return;

    if (backend_ == Backend::SdFat) {
        String path = pathFor(activeId_);
        FsFile f = gSdFat.open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND);
        if (!f) { log_w("append: open failed"); return; }
        f.write((const uint8_t*)line, (size_t)len);
        f.close();
        ++sampleCount_;
        return;
    }

    if (!fs_) return;
    File f = fs_->open(pathFor(activeId_), "a");
    if (!f) { log_w("append: open failed"); return; }
    f.print(line);
    f.close();
    ++sampleCount_;
}

size_t SessionStore::totalBytes() const {
    // SD reports values much larger than 32 bits; we clamp to size_t for the UI.
    if (backend_ == Backend::Sd)       return (size_t)std::min<uint64_t>(SD.totalBytes(),       SIZE_MAX);
    if (backend_ == Backend::SdFat)    return (size_t)std::min<uint64_t>(cardSizeMb_ * 1024ULL * 1024ULL, SIZE_MAX);
    if (backend_ == Backend::LittleFs) return LittleFS.totalBytes();
    return 0;
}
size_t SessionStore::usedBytes() const {
    if (backend_ == Backend::Sd)       return (size_t)std::min<uint64_t>(SD.usedBytes(),        SIZE_MAX);
    if (backend_ == Backend::SdFat) {
        // SdFat doesn't track free clusters cheaply; freeClusterCount() walks
        // the FAT and is slow on large cards. Skip it -- UI just shows total.
        return 0;
    }
    if (backend_ == Backend::LittleFs) return LittleFS.usedBytes();
    return 0;
}

int SessionStore::percentUsed() const {
    const size_t t = totalBytes();
    if (!t) return 0;
    return (int)((usedBytes() * 100ULL) / t);
}

int SessionStore::sessionCount() const {
    if (backend_ == Backend::SdFat) {
        FsFile dir = gSdFat.open(cfg::SESSIONS_DIR, O_RDONLY);
        if (!dir || !dir.isDir()) return 0;
        int n = 0;
        FsFile child;
        while (child.openNext(&dir, O_RDONLY)) {
            if (!child.isDir()) ++n;
            child.close();
        }
        return n;
    }
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
    if (backend_ == Backend::SdFat) {
        FsFile dir = gSdFat.open(cfg::SESSIONS_DIR, O_RDONLY);
        if (!dir || !dir.isDir()) return out;
        FsFile child;
        while (child.openNext(&dir, O_RDONLY)) {
            if (!child.isDir()) {
                char nameBuf[64];
                child.getName(nameBuf, sizeof(nameBuf));
                String name(nameBuf);
                if (name.endsWith(".csv")) {
                    SessionInfo info;
                    info.id        = stripCsvSuffix(name);
                    info.sizeBytes = (size_t)child.size();
                    info.samples   = 0;
                    // Count newlines via a separate read pass on the same file.
                    uint8_t buf[256];
                    int n;
                    child.seek(0);
                    while ((n = child.read(buf, sizeof(buf))) > 0) {
                        for (int i = 0; i < n; ++i) if (buf[i] == '\n') ++info.samples;
                    }
                    if (info.samples > 0) --info.samples;
                    out.push_back(info);
                }
            }
            child.close();
        }
        return out;
    }
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
    if (!hasUsableBackend()) {
        out.printf("[DUMP-ERR] id=%s reason=no-backend\n", id.c_str());
        return false;
    }
    if (backend_ == Backend::SdFat) {
        String path = String(cfg::SESSIONS_DIR) + "/" + id + ".csv";
        FsFile f = gSdFat.open(path.c_str(), O_RDONLY);
        if (!f) {
            out.printf("[DUMP-ERR] id=%s reason=open-failed\n", id.c_str());
            return false;
        }
        uint32_t bytes = (uint32_t)f.size();
        // Count samples in a separate pass.
        uint32_t samples = 0;
        {
            FsFile counter = gSdFat.open(path.c_str(), O_RDONLY);
            if (counter) {
                uint8_t cbuf[256];
                int n;
                while ((n = counter.read(cbuf, sizeof(cbuf))) > 0) {
                    for (int i = 0; i < n; ++i) if (cbuf[i] == '\n') ++samples;
                }
                if (samples > 0) --samples;
                counter.close();
            }
        }
        out.printf("[DUMP-BEGIN] id=%s bytes=%u samples=%u\n",
                   id.c_str(), (unsigned)bytes, (unsigned)samples);
        uint8_t buf[256];
        int n;
        while ((n = f.read(buf, sizeof(buf))) > 0) {
            out.write(buf, (size_t)n);
            yield();
        }
        f.close();
        out.print('\n');
        out.printf("[DUMP-END] id=%s\n", id.c_str());
        return true;
    }
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
    if (!hasUsableBackend()) return 0;

    if (backend_ == Backend::SdFat) {
        gSdFat.remove(cfg::ACTIVE_FILE);
        FsFile dir = gSdFat.open(cfg::SESSIONS_DIR, O_RDONLY);
        if (!dir || !dir.isDir()) return 0;
        std::vector<String> paths;
        FsFile child;
        while (child.openNext(&dir, O_RDONLY)) {
            if (!child.isDir()) {
                char nameBuf[64];
                child.getName(nameBuf, sizeof(nameBuf));
                paths.push_back(String(cfg::SESSIONS_DIR) + "/" + String(nameBuf));
            }
            child.close();
        }
        uint32_t removed = 0;
        for (const auto& p : paths) {
            if (gSdFat.remove(p.c_str())) ++removed;
        }
        activeId_    = "";
        sampleCount_ = 0;
        return removed;
    }

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
    if (id.length() == 0 || !hasUsableBackend()) return false;
    if (recording_ && activeId_ == id) return false;   // refuse to delete active
    if (backend_ == Backend::SdFat) {
        String path = pathFor(id);
        return gSdFat.remove(path.c_str());
    }
    if (!fs_) return false;
    return fs_->remove(pathFor(id));
}

bool SessionStore::readSessionToString(const String& id, size_t maxBytes, String& out) const {
    if (!hasUsableBackend()) return false;
    String path = pathFor(id);
    if (backend_ == Backend::SdFat) {
        FsFile f = gSdFat.open(path.c_str(), O_RDONLY);
        if (!f) return false;
        size_t sz = (size_t)f.size();
        if (sz > maxBytes) { f.close(); return false; }
        out.reserve(sz);
        uint8_t buf[256];
        int n;
        while ((n = f.read(buf, sizeof(buf))) > 0) {
            for (int i = 0; i < n; ++i) out += (char)buf[i];
        }
        f.close();
        return true;
    }
    if (!fs_) return false;
    File f = fs_->open(path, "r");
    if (!f) return false;
    if (f.size() > maxBytes) { f.close(); return false; }
    out.reserve((size_t)f.size());
    while (f.available()) {
        out += (char)f.read();
    }
    f.close();
    return true;
}
