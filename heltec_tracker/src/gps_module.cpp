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
}

void GpsModule::update() {
    while (gpsSerial.available()) {
        const int b = gpsSerial.read();
        if (b < 0) break;
        gps_.encode((char)b);
        ++bytesIn_;
    }
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
