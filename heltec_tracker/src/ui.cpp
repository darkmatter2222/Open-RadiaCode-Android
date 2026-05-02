#include "ui.h"
#include "config.h"
#include "gps_module.h"
#include "session_store.h"

#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>
#include <SPI.h>
#include <algorithm>

namespace {
// Subclass exposes the protected setColRowStart() helper so we can apply the
// HTIT-Tracker mini panel's RAM offsets and kill the rainbow edges.
// V1.2: setColRowStart(26, 1).  V2: setColRowStart(24, 0).  These are the
// post-rotation memory addresses Heltec uses in their factory firmware.
class Tracker_ST7735 : public Adafruit_ST7735 {
public:
    using Adafruit_ST7735::Adafruit_ST7735;
    void applyMiniOffsets() {
#if defined(TRACKER_HW_V2)
        setColRowStart(24, 0);
#else
        setColRowStart(26, 1);
#endif
    }
};

// HTIT-Tracker V1.2 SPI pins (custom HSPI, not default).
// Software-SPI constructor avoids the lib re-binding the HW SPI peripheral.
Tracker_ST7735 tft(cfg::TFT_CS, cfg::TFT_DC,
                   cfg::TFT_MOSI, cfg::TFT_SCLK,
                   cfg::TFT_RST);

constexpr uint16_t COL_BG       = ST77XX_BLACK;
constexpr uint16_t COL_FG       = 0xFFFF;
constexpr uint16_t COL_DIM      = 0x8C71;     // light gray, readable on black
constexpr uint16_t COL_GREEN    = 0x07E0;
constexpr uint16_t COL_RED      = 0xF800;
constexpr uint16_t COL_AMBER    = 0xFD20;
constexpr uint16_t COL_CYAN     = 0x07FF;
constexpr uint16_t COL_HEADER   = 0x10A2;     // dark blue band
constexpr uint16_t COL_PICK     = 0x041F;     // selected row highlight

constexpr int HEADER_H = 12;

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

// ---------------------------------------------------------------------------
void Ui::begin() {
    tft.initR(INITR_MINI160x80);
    tft.setRotation(cfg::TFT_ROTATION);
    tft.applyMiniOffsets();               // kills rainbow edge pixels
    // V1.2 panels are inverted (so invertDisplay(true) gives black BG); V2
    // panels are not. Mismatch == fully white screen, the symptom users see
    // when V1.2 firmware boots on a V2 board.
    tft.invertDisplay(cfg::TFT_INVERT);
    tft.fillScreen(COL_BG);
    tft.setTextWrap(false);
    tft.setTextColor(COL_FG, COL_BG);

    // Splash for ~600 ms so the user knows the panel is alive.
    tft.setTextSize(2);
    tft.setCursor(20, 22);
    tft.setTextColor(COL_GREEN, COL_BG);
    tft.print("HTIT-RC");
    tft.setTextSize(1);
    tft.setCursor(40, 50);
    tft.setTextColor(COL_DIM, COL_BG);
    tft.print("booting...");
    delay(600);
    tft.fillScreen(COL_BG);
    forceFullRedraw_ = true;
}

void Ui::setSources(GpsModule* gps, SessionStore* store, RadiaCode* rc) {
    gps_ = gps; store_ = store; rc_ = rc;
}

// ---------------------------------------------------------------------------
void Ui::onShortPress() {
    confirmStopPending_ = false;      // any screen navigation cancels pending stop
    if (screen_ == SCREEN_PICKER) {
        if (pickList_.empty()) return;
        // cursor 0..N-1 = device index, N = "Cancel"
        pickerCursor_ = (pickerCursor_ + 1) % ((int)pickList_.size() + 1);
        forceFullRedraw_ = true;     // redraw rows so cursor is visible
        return;
    }
    // Cycle STATS -> GPS -> STORAGE -> STATS
    screen_ = (Screen)((screen_ + 1) % SCREEN_NORMAL_COUNT);
    forceFullRedraw_ = true;
}

void Ui::onLongPress() {
    switch (screen_) {
        case SCREEN_STATS:
            pendingAction_ = ACTION_START_PICKER;
            break;
        case SCREEN_STORAGE:
            if (store_ && store_->isRecording()) {
                if (confirmStopPending_) {
                    // Second long press within window: confirmed, actually stop
                    confirmStopPending_ = false;
                    pendingAction_ = ACTION_TOGGLE_REC;
                } else {
                    // First long press: arm confirmation, show prompt on screen
                    confirmStopPending_ = true;
                    confirmStopArmMs_ = millis();
                    forceFullRedraw_ = true;
                }
            } else {
                // Not recording: start immediately, no confirmation needed
                pendingAction_ = ACTION_TOGGLE_REC;
            }
            break;
        case SCREEN_PICKER:
            if (pickerCursor_ >= (int)pickList_.size()) {
                pendingAction_ = ACTION_CANCEL_PICKER;
            } else {
                // pickerCursor_ indexes the displayed (sorted) order, so map
                // through pickerOrder_ to get the real pickList_ entry.
                int realIdx = pickerCursor_;
                if (pickerCursor_ < (int)pickerOrder_.size()) {
                    realIdx = pickerOrder_[pickerCursor_];
                }
                if (realIdx >= 0 && realIdx < (int)pickList_.size()) {
                    pickedAddr_ = String(pickList_[realIdx].address.c_str());
                    pickedAddrType_ = pickList_[realIdx].addrType;
                    pendingAction_ = ACTION_PICK_DEVICE;
                }
            }
            break;
        default: break;
    }
}

void Ui::setReading(const RadiaCode::Reading& r) {
    lastReading_ = r;
    if (r.battery > 0 && r.battery <= 100) {
        // RadiaCode reports its own battery; we treat it as the displayed value.
        // (USB-C powered ESP has its own divider but is less interesting here.)
    }
}
void Ui::setRadiaState(RadiaCode::State s, const String& addr) {
    rcState_ = s;
    rcAddr_  = addr;
}

void Ui::enterPicker(const std::vector<RadiaCode::ScanResult>& results) {
    // Decide if anything visible actually changed -- only redraw on real change
    // to avoid flicker. Address membership change OR significant RSSI delta.
    bool changed = (results.size() != pickList_.size());
    if (!changed) {
        for (size_t i = 0; i < results.size(); ++i) {
            // Match by address (order may differ between snapshots).
            const auto& a = results[i];
            bool found = false;
            for (const auto& b : pickList_) {
                if (a.address == b.address) {
                    if (a.name != b.name ||
                        a.likelyMatch != b.likelyMatch ||
                        std::abs(a.rssi - b.rssi) > 8) {
                        changed = true;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) { changed = true; break; }
        }
    }
    pickList_ = results;
    if (screen_ != SCREEN_PICKER) {
        pickerCursor_ = 0;
        screen_ = SCREEN_PICKER;
        forceFullRedraw_ = true;
    } else if (changed) {
        forceFullRedraw_ = true;
    }
    // pickList_.size() entries + 1 Cancel row, so max valid cursor = size().
    if (pickerCursor_ > (int)pickList_.size()) pickerCursor_ = (int)pickList_.size();
}

// ---------------------------------------------------------------------------
void Ui::field(int idx, int x, int y, int w, int h,
               const char* str, uint16_t fg, uint16_t bg, uint8_t size) {
    if (idx < 0 || idx >= MAX_FIELDS) return;
    String s(str);
    if (!forceFullRedraw_ &&
        prevText_[idx] == s && prevFg_[idx] == fg && prevSize_[idx] == size) {
        return; // unchanged: skip entirely (no flicker)
    }
    tft.fillRect(x, y, w, h, bg);
    tft.setTextColor(fg, bg);
    tft.setTextSize(size);
    tft.setCursor(x, y);
    tft.print(str);
    prevText_[idx] = s;
    prevFg_[idx]   = fg;
    prevSize_[idx] = size;
}

// ---------------------------------------------------------------------------
void Ui::tick() {
    // Expire the stop-recording confirmation if the user didn't confirm in time
    if (confirmStopPending_ && (millis() - confirmStopArmMs_) >= kConfirmStopTimeoutMs) {
        confirmStopPending_ = false;
        forceFullRedraw_ = true;
    }
    if (screen_ != lastDrawnScreen_) {
        tft.fillScreen(COL_BG);
        for (int i = 0; i < MAX_FIELDS; ++i) prevText_[i] = "";
        forceFullRedraw_ = true;
        lastDrawnScreen_ = screen_;
    }

    renderHeader();
    switch (screen_) {
        case SCREEN_STATS:   renderStats();   break;
        case SCREEN_GPS:     renderGps();     break;
        case SCREEN_STORAGE: renderStorage(); break;
        case SCREEN_PICKER:  renderPicker();  break;
        default: break;
    }
    forceFullRedraw_ = false;
}

// ---------------------------------------------------------------------------
// Header layout (160 wide, 12 tall)
//   [0..32]  state badge   "OK  " / "SCAN"
//   [34..78] gps badge     "GPS 3D" / "GPS NO"
//   [80..134] battery      "BAT 87%"
//   [138..159] rec dot     filled red circle if recording
void Ui::renderHeader() {
    if (forceFullRedraw_) {
        tft.fillRect(0, 0, cfg::TFT_W, HEADER_H, COL_HEADER);
    }

    field(0, 2, 2, 30, 8,
          stateName(rcState_), stateColor(rcState_), COL_HEADER, 1);

    const bool fix = gps_ && gps_->hasFix();
    char gbuf[10]; snprintf(gbuf, sizeof(gbuf), "GPS %s", fix ? "3D" : "NO");
    field(1, 36, 2, 44, 8, gbuf, fix ? COL_GREEN : COL_RED, COL_HEADER, 1);

    char bbuf[12];
    if (vbatPct_ >= 0) snprintf(bbuf, sizeof(bbuf), "BAT %3d%%", vbatPct_);
    else               snprintf(bbuf, sizeof(bbuf), "BAT --%%");
    uint16_t bcol = (vbatPct_ < 0) ? COL_DIM
                  : (vbatPct_ < 20 ? COL_RED
                                   : (vbatPct_ < 40 ? COL_AMBER : COL_FG));
    field(2, 84, 2, 54, 8, bbuf, bcol, COL_HEADER, 1);

    // Recording dot (drawn directly; only paint state changes are noticeable)
    const bool rec = store_ && store_->isRecording();
    static bool prevRec = false;
    if (forceFullRedraw_ || rec != prevRec) {
        tft.fillRect(140, 1, 18, 10, COL_HEADER);
        if (rec) tft.fillCircle(149, 6, 4, COL_RED);
        prevRec = rec;
    }
}

// ---------------------------------------------------------------------------
// STATS screen (160 x 68 below header)
//   y=14: "DOSE"  small dim
//   y=22: big nSv/h value (size 3) ~24px tall
//   y=46: "CPS"   small dim    + count rate (size 2) ~16px
//   y=66: footer (errors / addr last 5)
void Ui::renderStats() {
    field(10, 4, 14, 60, 8, "DOSE nSv/h", COL_DIM, COL_BG, 1);

    char buf[24];
    if (lastReading_.valid) {
        const float nsv = lastReading_.uSvPerHour * 1000.0f;
        if (nsv < 100)        snprintf(buf, sizeof(buf), "%5.2f", nsv);
        else if (nsv < 1000)  snprintf(buf, sizeof(buf), "%5.1f", nsv);
        else                  snprintf(buf, sizeof(buf), "%5.0f", nsv);
    } else {
        strcpy(buf, " --- ");
    }
    field(11, 4, 22, 110, 22, buf, COL_GREEN, COL_BG, 3);

    if (lastReading_.valid) {
        char e[12]; snprintf(e, sizeof(e), "+/-%2.0f%%", lastReading_.doseErrPct);
        field(12, 116, 26, 42, 8, e, COL_DIM, COL_BG, 1);
    } else {
        field(12, 116, 26, 42, 8, "", COL_DIM, COL_BG, 1);
    }

    field(13, 4, 46, 30, 8, "CPS", COL_DIM, COL_BG, 1);

    if (lastReading_.valid) snprintf(buf, sizeof(buf), "%5.1f", lastReading_.cps);
    else strcpy(buf, " --- ");
    field(14, 36, 46, 80, 16, buf, COL_FG, COL_BG, 2);

    if (lastReading_.valid) {
        char e[12]; snprintf(e, sizeof(e), "+/-%2.0f%%", lastReading_.cpsErrPct);
        field(15, 116, 50, 42, 8, e, COL_DIM, COL_BG, 1);
    } else {
        field(15, 116, 50, 42, 8, "", COL_DIM, COL_BG, 1);
    }

    // Footer line: state hint + last 5 of MAC
    char foot[24];
    if (rcState_ != RadiaCode::State::Ready) {
        snprintf(foot, sizeof(foot), "Hold: pick RC");
    } else if (rcAddr_.length() >= 5) {
        snprintf(foot, sizeof(foot), "RC %s%s",
                 rcAddr_.substring(rcAddr_.length() - 5).c_str(),
                 (gps_ && gps_->hasFix()) ? "" : " *noGPS");
    } else {
        strcpy(foot, "");
    }
    field(16, 4, 66, 156, 8, foot, COL_DIM, COL_BG, 1);
}

// ---------------------------------------------------------------------------
// GPS screen (160 x 68)
//   col1 (4..78): SAT count, HDOP, fix
//   col2 (82..156): LAT, LON, ALT, SPD
void Ui::renderGps() {
    if (!gps_) return;
    char buf[24];

    snprintf(buf, sizeof(buf), "FIX %s", gps_->hasFix() ? "3D" : "NO");
    field(20, 4, 14, 76, 8, buf,
          gps_->hasFix() ? COL_GREEN : COL_RED, COL_BG, 1);

    snprintf(buf, sizeof(buf), "Sats %u", (unsigned)gps_->satellites());
    field(21, 4, 26, 76, 8, buf, COL_FG, COL_BG, 1);

    snprintf(buf, sizeof(buf), "HDOP %.1f", gps_->hdop());
    field(22, 4, 38, 76, 8, buf, COL_FG, COL_BG, 1);

    // Estimated horizontal accuracy: HDOP * UERE (~3 m typical for L1 GNSS).
    if (gps_->hasFix() && gps_->hdop() < 50.0) {
        const float acc = (float)gps_->hdop() * 3.0f;
        snprintf(buf, sizeof(buf), "+/-%4.1fm", acc);
        field(23, 4, 50, 76, 8, buf, COL_GREEN, COL_BG, 1);
    } else {
        field(23, 4, 50, 76, 8, "+/- ---", COL_DIM, COL_BG, 1);
    }

    if (gps_->hasFix()) {
        snprintf(buf, sizeof(buf), "%.5f", gps_->latitude());
        field(24, 84, 14, 76, 8, buf, COL_FG, COL_BG, 1);
        snprintf(buf, sizeof(buf), "%.5f", gps_->longitude());
        field(25, 84, 26, 76, 8, buf, COL_FG, COL_BG, 1);
        snprintf(buf, sizeof(buf), "%.0fm", gps_->altitudeMeters());
        field(26, 84, 38, 76, 8, buf, COL_FG, COL_BG, 1);
        snprintf(buf, sizeof(buf), "%.1fkph", gps_->speedKph());
        field(27, 84, 50, 76, 8, buf, COL_FG, COL_BG, 1);
    } else {
        field(24, 84, 14, 76, 8, "  ---  ", COL_DIM, COL_BG, 1);
        field(25, 84, 26, 76, 8, "  ---  ", COL_DIM, COL_BG, 1);
        field(26, 84, 38, 76, 8, "  ---  ", COL_DIM, COL_BG, 1);
        field(27, 84, 50, 76, 8, "  ---  ", COL_DIM, COL_BG, 1);
    }

    field(28, 4, 66, 156, 8, "Acquiring fix outdoors", COL_DIM, COL_BG, 1);
}

// ---------------------------------------------------------------------------
// STORAGE screen
void Ui::renderStorage() {
    if (!store_) return;
    char buf[40];

    // Hard-failure mode: SD was required and didn't mount. Show a single
    // unambiguous message so the user knows recording is disabled and the
    // device needs a power cycle. No half-measures.
    if (store_->storageFailed()) {
        if (forceFullRedraw_) {
            tft.fillRect(0, HEADER_H, cfg::TFT_W, cfg::TFT_H - HEADER_H, COL_BG);
        }
        field(28, 4, 14, 156, 8, "STORAGE INIT FAILED", COL_RED, COL_BG, 1);
        field(29, 4, 26, 156, 8, "SD card not detected", COL_AMBER, COL_BG, 1);
        field(30, 4, 40, 156, 8, "Please reboot device", COL_FG, COL_BG, 1);
        field(31, 4, 54, 156, 8, "Check card seat / power", COL_DIM, COL_BG, 1);
        field(32, 4, 66, 156, 8, "Recording is DISABLED", COL_RED, COL_BG, 1);
        return;
    }

    const bool rec = store_->isRecording();
    const bool fix = gps_ && gps_->hasFix();
    field(30, 4, 14, 50, 8, "REC", COL_DIM, COL_BG, 1);
    field(31, 36, 14, 60, 8,
          rec ? (fix ? "ON     " : "ON noGPS") : "OFF    ",
          rec ? (fix ? COL_GREEN : COL_AMBER) : COL_RED,
          COL_BG, 1);

    snprintf(buf, sizeof(buf), "Samp %lu", (unsigned long)store_->sampleCount());
    field(32, 80, 14, 76, 8, buf, COL_FG, COL_BG, 1);

    if (rec) {
        snprintf(buf, sizeof(buf), "ID %s", store_->activeId().c_str());
    } else {
        strcpy(buf, "(idle)");
    }
    field(33, 4, 26, 156, 8, buf, COL_FG, COL_BG, 1);

    // Disk bar
    const int pct = store_->percentUsed();
    snprintf(buf, sizeof(buf), "Disk %d%%  %lu/%luK",
             pct,
             (unsigned long)(store_->usedBytes() / 1024),
             (unsigned long)(store_->totalBytes() / 1024));
    field(34, 4, 38, 156, 8, buf, COL_DIM, COL_BG, 1);

    // Bar drawing: only redraw when percentage rounded changed
    static int prevPct = -1;
    if (forceFullRedraw_ || pct != prevPct) {
        const int barX = 4, barY = 50, barW = cfg::TFT_W - 8, barH = 6;
        tft.drawRect(barX, barY, barW, barH, COL_DIM);
        tft.fillRect(barX + 1, barY + 1, barW - 2, barH - 2, COL_BG);
        const int fill = (barW - 2) * pct / 100;
        tft.fillRect(barX + 1, barY + 1, fill, barH - 2,
                     pct > 85 ? COL_RED : (pct > 60 ? COL_AMBER : COL_GREEN));
        prevPct = pct;
    }

    if (confirmStopPending_) {
        field(35, 4, 66, 156, 8, "HOLD AGAIN: STOP REC", COL_RED, COL_BG, 1);
    } else {
        snprintf(buf, sizeof(buf), "%s   Sess:%d",
                 rec ? "Hold: STOP" : "Hold: START",
                 store_->sessionCount());
        field(35, 4, 66, 156, 8, buf, COL_DIM, COL_BG, 1);
    }
}

// ---------------------------------------------------------------------------
// PICKER screen: list of nearby BLE devices found during scan.
// RadiaCode 110 sometimes advertises with no name, so we list ALL nearby
// advertisers sorted by signal strength. Likely RadiaCode matches (name or
// service UUID) are prefixed with '*' in green.
void Ui::renderPicker() {
    if (!forceFullRedraw_) return;       // only redraw on real changes
    tft.fillRect(0, HEADER_H, cfg::TFT_W, cfg::TFT_H - HEADER_H, COL_BG);

    if (pickList_.empty()) {
        tft.setTextSize(1);
        tft.setTextColor(COL_AMBER, COL_BG);
        tft.setCursor(4, 16); tft.print("Scanning...");
        tft.setTextColor(COL_DIM, COL_BG);
        tft.setCursor(4, 30); tft.print("no devices yet");
        tft.setCursor(4, 46); tft.print("long press = cancel");
        return;
    }

    // Build sorted view (descending RSSI, likely matches first within tie).
    std::vector<int> order(pickList_.size());
    for (size_t i = 0; i < pickList_.size(); ++i) order[i] = (int)i;
    std::sort(order.begin(), order.end(), [&](int a, int b){
        const auto& ra = pickList_[a];
        const auto& rb = pickList_[b];
        if (ra.likelyMatch != rb.likelyMatch) return ra.likelyMatch && !rb.likelyMatch;
        return ra.rssi > rb.rssi;
    });
    pickerOrder_ = order;   // remember mapping so onLongPress picks correct entry

    const int rowH = 12;
    const int total = (int)pickList_.size() + 1;       // +1 for Cancel
    const int show = total < 5 ? total : 5;

    char line[40];
    for (int row = 0; row < show; ++row) {
        const int y = HEADER_H + 1 + row * rowH;
        const bool selected = (row == pickerCursor_);
        const uint16_t bg = selected ? COL_PICK : COL_BG;
        tft.fillRect(0, y, cfg::TFT_W, rowH, bg);
        tft.setTextSize(1);

        if (row < (int)pickList_.size()) {
            const auto& r = pickList_[order[row]];
            // Label: name if present; if it's a likely RadiaCode without a
            // resolved name, show "RadiaCode?" so the user knows to pick it.
            // Otherwise fall back to last 3 octets of the MAC.
            char label[16];
            if (!r.name.empty()) {
                snprintf(label, sizeof(label), "%-12.12s", r.name.c_str());
            } else if (r.likelyMatch) {
                snprintf(label, sizeof(label), "%-12.12s", "RadiaCode?");
            } else {
                std::string a = r.address;
                std::string tail = a.length() >= 8
                    ? a.substr(a.length() - 8) : a;     // "xx:xx:xx"
                char tmp[16];
                snprintf(tmp, sizeof(tmp), "?%s", tail.c_str());
                snprintf(label, sizeof(label), "%-12.12s", tmp);
            }
            const uint16_t fg = r.likelyMatch ? COL_GREEN : 0xFFFF;
            const char marker = r.likelyMatch ? '*' : (selected ? '>' : ' ');
            snprintf(line, sizeof(line), "%c%s", marker, label);
            tft.setTextColor(fg, bg);
            tft.setCursor(2, y + 2); tft.print(line);
            // RSSI right-aligned
            char rs[8]; snprintf(rs, sizeof(rs), "%4d", r.rssi);
            tft.setTextColor(0xFFFF, bg);
            tft.setCursor(cfg::TFT_W - 26, y + 2); tft.print(rs);
        } else {
            snprintf(line, sizeof(line), "%c [Cancel]",
                     selected ? '>' : ' ');
            tft.setTextColor(0xFFFF, bg);
            tft.setCursor(2, y + 2); tft.print(line);
        }
    }
}
