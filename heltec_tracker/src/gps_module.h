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
};
