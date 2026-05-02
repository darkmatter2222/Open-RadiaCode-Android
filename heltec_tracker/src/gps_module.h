#pragma once
#include <Arduino.h>
#include <TinyGPSPlus.h>

class GpsModule {
public:
    void begin();
    void update();   // call from loop()

    // TinyGPSPlus's accessors are not const-qualified upstream, so these aren't either.
    bool    hasFix()         { return gps_.location.isValid() && gps_.location.age() < 5000; }
    double  latitude()       { return gps_.location.lat(); }
    double  longitude()      { return gps_.location.lng(); }
    uint8_t satellites()     { return gps_.satellites.isValid() ? (uint8_t)gps_.satellites.value() : 0; }
    double  hdop()           { return gps_.hdop.isValid() ? gps_.hdop.hdop() : 99.99; }
    double  altitudeMeters() { return gps_.altitude.isValid() ? gps_.altitude.meters() : 0.0; }
    double  speedKph()       { return gps_.speed.isValid() ? gps_.speed.kmph() : 0.0; }
    double  courseDeg()      { return gps_.course.isValid() ? gps_.course.deg() : -1.0; }

    bool    hasUtc()         { return gps_.date.isValid() && gps_.time.isValid(); }

    // UTC epoch milliseconds, computed from GPS date+time. 0 if no fix.
    uint64_t utcEpochMs();

    // Best-effort wall-clock time in epoch milliseconds. Once GPS UTC has been
    // seen at least once, this returns (utcAnchor + (millis() - millisAnchor))
    // so timestamps keep advancing monotonically during GPS outages (e.g.
    // indoors). Returns 0 only if UTC has never been acquired this boot.
    uint64_t bestEpochMs();

    uint32_t bytesIn() const { return bytesIn_; }
    uint32_t sentencesWithFix() { return gps_.sentencesWithFix(); }
    uint32_t passedChecksum()  { return gps_.passedChecksum(); }
    uint32_t failedChecksum()  { return gps_.failedChecksum(); }
    uint32_t lastByteMs() const { return lastByteMs_; }
    uint32_t baud()      const { return currentBaud_; }

    // Pipe raw GPS UART bytes to the supplied stream for `secs` seconds.
    // Useful for confirming the GPS module is even producing NMEA.
    void passthru(Stream& out, uint32_t secs);

    // Try fallback bauds if no bytes have arrived after `silenceMs`.
    // Returns true if data is now flowing.
    bool autoBaudIfSilent(uint32_t silenceMs);

private:
    TinyGPSPlus gps_;
    uint32_t    bytesIn_ = 0;
    uint32_t    lastByteMs_ = 0;
    uint32_t    currentBaud_ = 0;

    // Anchor that converts ESP32 millis() into wall-clock UTC ms. Set the
    // first time UTC is seen and re-synced periodically while a fix is live
    // so we don't accumulate drift from millis() over long sessions.
    uint64_t utcAnchorMs_  = 0;   // UTC ms at the moment of the anchor
    uint32_t millisAnchor_ = 0;   // millis() value at the moment of the anchor
    uint32_t lastUtcSyncMs_ = 0;  // last time we re-anchored from a fresh fix
};
