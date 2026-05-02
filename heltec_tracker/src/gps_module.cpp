#include "gps_module.h"
#include "config.h"

namespace {
HardwareSerial gpsSerial(cfg::GPS_UART_NUM);

// Days from 1970-01-01 (Thursday) to (year, month, day). Civil-date algorithm.
// Reference: Howard Hinnant.
int64_t daysFromCivil(int y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097LL + static_cast<int64_t>(doe) - 719468LL;
}
} // namespace

void GpsModule::begin() {
    gpsSerial.begin(cfg::GPS_BAUD, SERIAL_8N1, cfg::GPS_RX_PIN, cfg::GPS_TX_PIN);
    currentBaud_ = cfg::GPS_BAUD;

    // Probe for incoming bytes for ~600ms. If silent, sweep the fallback
    // bauds until something shows up. UC6580 modules in some revisions
    // come up at 9600 even though the Heltec ref defaults to 115200.
    const uint32_t probeUntil = millis() + 600;
    while (millis() < probeUntil) {
        if (gpsSerial.available()) { lastByteMs_ = millis(); return; }
        delay(10);
    }

    for (uint32_t b : cfg::GPS_FALLBACK_BAUDS) {
        if (b == cfg::GPS_BAUD) continue;
        log_w("GPS: silent at %u baud, trying %u...", (unsigned)currentBaud_, (unsigned)b);
        gpsSerial.end();
        delay(50);
        gpsSerial.begin(b, SERIAL_8N1, cfg::GPS_RX_PIN, cfg::GPS_TX_PIN);
        currentBaud_ = b;
        const uint32_t until = millis() + 600;
        while (millis() < until) {
            if (gpsSerial.available()) {
                log_i("GPS: bytes flowing at %u baud", (unsigned)b);
                lastByteMs_ = millis();
                return;
            }
            delay(10);
        }
    }
    log_w("GPS: no bytes seen on any baud (check VGNSS power, antenna, RX/TX wiring)");
}

void GpsModule::update() {
    while (gpsSerial.available()) {
        const int b = gpsSerial.read();
        if (b < 0) break;
        gps_.encode((char)b);
        ++bytesIn_;
        lastByteMs_ = millis();
    }
}

bool GpsModule::autoBaudIfSilent(uint32_t silenceMs) {
    if (lastByteMs_ != 0 && (millis() - lastByteMs_) < silenceMs) return true;
    // Re-run the begin() probe sequence.
    log_w("GPS: %u ms of silence, re-probing bauds...", (unsigned)silenceMs);
    begin();
    return (lastByteMs_ != 0 && (millis() - lastByteMs_) < 1000);
}

void GpsModule::passthru(Stream& out, uint32_t secs) {
    out.printf("[GPS-PASSTHRU] baud=%u for %u sec...\n", (unsigned)currentBaud_, (unsigned)secs);
    const uint32_t until = millis() + secs * 1000UL;
    uint32_t bytes = 0;
    while ((int32_t)(millis() - until) < 0) {
        while (gpsSerial.available()) {
            int b = gpsSerial.read();
            if (b < 0) break;
            // Mirror to TinyGPS so the rest of the system stays consistent.
            gps_.encode((char)b);
            ++bytesIn_;
            ++bytes;
            lastByteMs_ = millis();
            out.write((uint8_t)b);
        }
        yield();
    }
    out.printf("\n[GPS-PASSTHRU-END] bytes=%u\n", (unsigned)bytes);
}

uint64_t GpsModule::utcEpochMs() {
    if (!hasUtc()) return 0;
    const int64_t days = daysFromCivil(gps_.date.year(),
                                       gps_.date.month(),
                                       gps_.date.day());
    int64_t secs = days * 86400LL
                 + (int64_t)gps_.time.hour() * 3600LL
                 + (int64_t)gps_.time.minute() * 60LL
                 + (int64_t)gps_.time.second();
    return (uint64_t)secs * 1000ULL + (uint64_t)gps_.time.centisecond() * 10ULL;
}

uint64_t GpsModule::bestEpochMs() {
    // Re-anchor while we have a *fresh* GPS time fix (age < 5s). This keeps
    // long sessions accurate against millis() drift. We cap re-anchors to
    // once per 30s so we don't churn the anchor on every NMEA sentence.
    const uint32_t now = millis();
    if (hasUtc() && gps_.time.age() < 5000 &&
        (lastUtcSyncMs_ == 0 || (now - lastUtcSyncMs_) >= 30000)) {
        const uint64_t fresh = utcEpochMs();
        if (fresh != 0) {
            const bool firstAnchor = (utcAnchorMs_ == 0);
            // Account for the small age of the parsed sentence.
            utcAnchorMs_   = fresh;
            millisAnchor_  = now - gps_.time.age();
            lastUtcSyncMs_ = now;
            if (firstAnchor) {
                Serial.printf("[GPS] UTC anchor set: %llu ms (millis=%u age=%u)\n",
                              (unsigned long long)utcAnchorMs_, now, gps_.time.age());
            } else {
                Serial.printf("[GPS] UTC anchor refreshed: %llu ms (drift %lld ms)\n",
                              (unsigned long long)utcAnchorMs_,
                              (long long)(fresh - (utcAnchorMs_ + (uint64_t)(now - millisAnchor_))));
            }
        }
    }
    if (utcAnchorMs_ == 0) return 0;
    // Project forward using the monotonic millis() clock so timestamps keep
    // advancing during GPS outages (indoors, tunnels, etc.).
    return utcAnchorMs_ + (uint64_t)(now - millisAnchor_);
}
