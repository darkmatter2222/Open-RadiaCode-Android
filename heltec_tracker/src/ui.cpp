#include "ui.h"
#include "config.h"
#include "gps_module.h"
#include "session_store.h"

#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>
#include <SPI.h>

namespace {
Adafruit_ST7735 tft(cfg::TFT_CS, cfg::TFT_DC, cfg::TFT_RST);

constexpr uint16_t COL_BG       = ST77XX_BLACK;
constexpr uint16_t COL_FG       = ST77XX_WHITE;
constexpr uint16_t COL_DIM      = 0x8410;
constexpr uint16_t COL_GREEN    = 0x07E0;
constexpr uint16_t COL_RED      = 0xF800;
constexpr uint16_t COL_AMBER    = 0xFD20;
constexpr uint16_t COL_BLUE     = 0x041F;
constexpr uint16_t COL_HEADER   = 0x10A2;

const char* stateName(RadiaCode::State s) {
    switch (s) {
        case RadiaCode::State::Idle:         return "IDLE";
        case RadiaCode::State::Scanning:     return "SCAN";
        case RadiaCode::State::Connecting:   return "CONN";
        case RadiaCode::State::Initializing: return "INIT";
        case RadiaCode::State::Ready:        return "OK  ";
        case RadiaCode::State::Disconnected: return "DISC";
    }
    return "?";
}
uint16_t stateColor(RadiaCode::State s) {
    switch (s) {
        case RadiaCode::State::Ready:        return COL_GREEN;
        case RadiaCode::State::Connecting:
        case RadiaCode::State::Initializing:
        case RadiaCode::State::Scanning:     return COL_AMBER;
        default:                             return COL_RED;
    }
}
} // namespace

void Ui::begin() {
    tft.initR(INITR_BLACKTAB);
    tft.setRotation(cfg::TFT_ROTATION);
    tft.fillScreen(COL_BG);
    tft.setTextWrap(false);
    tft.setTextColor(COL_FG, COL_BG);
}

void Ui::setSources(GpsModule* gps, SessionStore* store, RadiaCode* rc) {
    gps_ = gps; store_ = store; rc_ = rc;
}

void Ui::onShortPress() {
    screen_ = (Screen)((screen_ + 1) % SCREEN_COUNT);
    dirty_ = true;
}

void Ui::onLongPress() {
    switch (screen_) {
        case SCREEN_STATS:   pendingAction_ = ACTION_RESCAN;     break;
        case SCREEN_STORAGE: pendingAction_ = ACTION_TOGGLE_REC; break;
        default: break;
    }
    dirty_ = true;
}

void Ui::setReading(const RadiaCode::Reading& r) {
    lastReading_ = r;
    if (screen_ == SCREEN_STATS) dirty_ = true;
}
void Ui::setRadiaState(RadiaCode::State s, const String& addr) {
    rcState_ = s;
    rcAddr_  = addr;
    dirty_ = true;
}

void Ui::tick() {
    const uint32_t now = millis();
    if (!dirty_ && (now - lastDrawMs_) < 500) return;
    if (screen_ != lastDrawnScreen_) {
        tft.fillScreen(COL_BG);
        lastDrawnScreen_ = screen_;
    }
    renderHeader();
    switch (screen_) {
        case SCREEN_STATS:   renderStats();   break;
        case SCREEN_GPS:     renderGps();     break;
        case SCREEN_STORAGE: renderStorage(); break;
        default: break;
    }
    dirty_ = false;
    lastDrawMs_ = now;
}

// ---------------------------------------------------------------------------
void Ui::renderHeader() {
    tft.fillRect(0, 0, cfg::TFT_W, 12, COL_HEADER);
    tft.setTextColor(COL_FG, COL_HEADER);
    tft.setTextSize(1);
    tft.setCursor(2, 2);
    const char* label =
        screen_ == SCREEN_STATS   ? "STATS"   :
        screen_ == SCREEN_GPS     ? "GPS"     :
        screen_ == SCREEN_STORAGE ? "STORAGE" : "?";
    tft.print(label);

    // RC state badge
    tft.setTextColor(stateColor(rcState_), COL_HEADER);
    tft.setCursor(50, 2);
    tft.print("RC:");
    tft.print(stateName(rcState_));

    // GPS fix indicator
    const bool fix = gps_ && gps_->hasFix();
    tft.setTextColor(fix ? COL_GREEN : COL_RED, COL_HEADER);
    tft.setCursor(98, 2);
    tft.print(fix ? "GPS:OK" : "GPS:--");

    // Battery
    tft.setTextColor(COL_FG, COL_HEADER);
    tft.setCursor(132, 2);
    if (vbatPct_ >= 0) {
        char b[8]; snprintf(b, sizeof(b), "%3d%%", vbatPct_);
        tft.print(b);
    } else {
        tft.print("--%");
    }

    // Recording dot
    if (store_ && store_->isRecording()) {
        tft.fillCircle(155, 6, 3, COL_RED);
    }

    tft.setTextColor(COL_FG, COL_BG);
}

// ---------------------------------------------------------------------------
void Ui::renderStats() {
    // Big nSv/h
    tft.fillRect(0, 14, cfg::TFT_W, cfg::TFT_H - 14, COL_BG);

    tft.setTextColor(COL_DIM, COL_BG);
    tft.setTextSize(1);
    tft.setCursor(4, 18);
    tft.print("DOSE RATE");

    tft.setTextColor(COL_GREEN, COL_BG);
    tft.setTextSize(3);
    tft.setCursor(4, 30);
    char buf[24];
    if (lastReading_.valid) {
        const float nsv = lastReading_.uSvPerHour * 1000.0f;
        if (nsv < 100)        snprintf(buf, sizeof(buf), "%.2f", nsv);
        else if (nsv < 1000)  snprintf(buf, sizeof(buf), "%.1f", nsv);
        else                  snprintf(buf, sizeof(buf), "%.0f", nsv);
    } else {
        strcpy(buf, "----");
    }
    tft.print(buf);
    tft.setTextSize(1);
    tft.setCursor(4, 56);
    tft.setTextColor(COL_DIM, COL_BG);
    tft.print("nSv/h");
    if (lastReading_.valid) {
        tft.setCursor(50, 56);
        char e[16]; snprintf(e, sizeof(e), "+/- %.1f%%", lastReading_.doseErrPct);
        tft.print(e);
    }

    // CPS
    tft.setCursor(4, 70);
    tft.print("COUNT RATE");
    tft.setTextColor(COL_FG, COL_BG);
    tft.setTextSize(2);
    tft.setCursor(4, 80);
    if (lastReading_.valid) snprintf(buf, sizeof(buf), "%.1f", lastReading_.cps);
    else strcpy(buf, "----");
    tft.print(buf);
    tft.setTextSize(1);
    tft.setCursor(64, 88);
    tft.setTextColor(COL_DIM, COL_BG);
    tft.print("CPS");
    if (lastReading_.valid) {
        tft.setCursor(90, 88);
        char e[16]; snprintf(e, sizeof(e), "+/- %.1f%%", lastReading_.cpsErrPct);
        tft.print(e);
    }

    // Footer
    tft.setCursor(4, cfg::TFT_H - 10);
    tft.setTextColor(COL_DIM, COL_BG);
    if (rcState_ != RadiaCode::State::Ready) {
        tft.print("Hold btn: rescan");
    } else {
        tft.print("RC ");
        // Show last 5 of address
        if (rcAddr_.length() >= 5) tft.print(rcAddr_.substring(rcAddr_.length() - 5));
    }
}

// ---------------------------------------------------------------------------
void Ui::renderGps() {
    tft.fillRect(0, 14, cfg::TFT_W, cfg::TFT_H - 14, COL_BG);
    tft.setTextSize(1);
    tft.setTextColor(COL_FG, COL_BG);

    char line[40];

    int y = 18;
    if (!gps_) return;

    tft.setCursor(4, y); tft.setTextColor(COL_DIM, COL_BG); tft.print("FIX:"); y += 0;
    tft.setCursor(38, y);
    tft.setTextColor(gps_->hasFix() ? COL_GREEN : COL_RED, COL_BG);
    tft.print(gps_->hasFix() ? "3D" : "NO ");
    y += 12;

    tft.setTextColor(COL_FG, COL_BG);
    snprintf(line, sizeof(line), "Sats : %u", gps_->satellites()); tft.setCursor(4, y); tft.print(line); y += 11;
    snprintf(line, sizeof(line), "HDOP : %.2f", gps_->hdop());     tft.setCursor(4, y); tft.print(line); y += 11;

    if (gps_->hasFix()) {
        snprintf(line, sizeof(line), "Lat  : %.6f",  gps_->latitude());        tft.setCursor(4, y); tft.print(line); y += 11;
        snprintf(line, sizeof(line), "Lon  : %.6f",  gps_->longitude());       tft.setCursor(4, y); tft.print(line); y += 11;
        snprintf(line, sizeof(line), "Alt  : %.0f m", gps_->altitudeMeters()); tft.setCursor(4, y); tft.print(line); y += 11;
        snprintf(line, sizeof(line), "Spd  : %.1f kph", gps_->speedKph());     tft.setCursor(4, y); tft.print(line); y += 11;
    } else {
        tft.setCursor(4, y);   tft.setTextColor(COL_DIM, COL_BG);
        tft.print("Acquiring fix...");
        y += 11;
    }

    tft.setTextColor(COL_DIM, COL_BG);
    tft.setCursor(4, cfg::TFT_H - 10);
    snprintf(line, sizeof(line), "RX %lu B", (unsigned long)gps_->bytesIn());
    tft.print(line);
}

// ---------------------------------------------------------------------------
void Ui::renderStorage() {
    tft.fillRect(0, 14, cfg::TFT_W, cfg::TFT_H - 14, COL_BG);
    tft.setTextSize(1);
    tft.setTextColor(COL_FG, COL_BG);
    if (!store_) return;

    char line[48];
    int y = 18;

    tft.setTextColor(COL_DIM, COL_BG);
    tft.setCursor(4, y); tft.print("RECORDING");
    tft.setTextColor(store_->isRecording() ? COL_GREEN : COL_RED, COL_BG);
    tft.setCursor(78, y); tft.print(store_->isRecording() ? "ON " : "OFF");
    y += 14;

    tft.setTextColor(COL_FG, COL_BG);
    if (store_->isRecording()) {
        snprintf(line, sizeof(line), "ID: %s", store_->activeId().c_str());
        tft.setCursor(4, y); tft.print(line); y += 11;
        snprintf(line, sizeof(line), "Samples: %lu", (unsigned long)store_->sampleCount());
        tft.setCursor(4, y); tft.print(line); y += 11;
    } else {
        tft.setCursor(4, y); tft.setTextColor(COL_DIM, COL_BG);
        tft.print("(idle)"); y += 11;
        tft.setTextColor(COL_FG, COL_BG);
    }

    // Bar
    const int pct = store_->percentUsed();
    tft.setCursor(4, y); tft.setTextColor(COL_DIM, COL_BG);
    snprintf(line, sizeof(line), "Disk %d%% (%lu/%lu KB)",
             pct,
             (unsigned long)(store_->usedBytes() / 1024),
             (unsigned long)(store_->totalBytes() / 1024));
    tft.print(line);
    y += 12;

    const int barX = 4, barY = y, barW = cfg::TFT_W - 8, barH = 8;
    tft.drawRect(barX, barY, barW, barH, COL_FG);
    const int fill = (barW - 2) * pct / 100;
    tft.fillRect(barX + 1, barY + 1, fill, barH - 2,
                 pct > 85 ? COL_RED : (pct > 60 ? COL_AMBER : COL_GREEN));
    y += barH + 6;

    snprintf(line, sizeof(line), "Sessions on disk: %d", store_->sessionCount());
    tft.setTextColor(COL_FG, COL_BG);
    tft.setCursor(4, y); tft.print(line);

    tft.setTextColor(COL_DIM, COL_BG);
    tft.setCursor(4, cfg::TFT_H - 10);
    tft.print(store_->isRecording() ? "Hold btn: STOP" : "Hold btn: START");
}
