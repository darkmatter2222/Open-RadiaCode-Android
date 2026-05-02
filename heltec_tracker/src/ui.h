#pragma once
#include <Arduino.h>
#include <vector>
#include "radiacode.h"

class GpsModule;
class SessionStore;

class Ui {
public:
    enum Screen : uint8_t {
        SCREEN_STATS = 0,
        SCREEN_GPS,
        SCREEN_STORAGE,
        SCREEN_PICKER,
        SCREEN_NORMAL_COUNT = SCREEN_PICKER, // STATS/GPS/STORAGE only in cycle
    };

    void begin();
    void setSources(GpsModule* gps, SessionStore* store, RadiaCode* rc);

    void onShortPress();
    void onLongPress();

    void setReading(const RadiaCode::Reading& r);
    void setRadiaState(RadiaCode::State s, const String& addr);
    void setBatteryPercent(int pct) { vbatPct_ = pct; }

    // Picker entry / exit
    void enterPicker(const std::vector<RadiaCode::ScanResult>& results);
    void exitPicker() { screen_ = SCREEN_STATS; forceFullRedraw_ = true; }

    void tick();

    enum LongAction : uint8_t {
        ACTION_NONE = 0,
        ACTION_TOGGLE_REC,
        ACTION_START_PICKER,    // Stats long-press: scan + show picker
        ACTION_PICK_DEVICE,     // Picker: connect to selected
        ACTION_CANCEL_PICKER,
    };
    LongAction lastLongAction() {
        LongAction a = pendingAction_;
        pendingAction_ = ACTION_NONE;
        return a;
    }
    String pickedAddress() const { return pickedAddr_; }
    uint8_t pickedAddrType() const { return pickedAddrType_; }

private:
    // Flicker-free field redraw. Each call site picks a unique index 0..MAX_FIELDS-1.
    void field(int idx, int x, int y, int w, int h,
               const char* str, uint16_t fg, uint16_t bg, uint8_t size);

    void renderHeader();
    void renderStats();
    void renderGps();
    void renderStorage();
    void renderPicker();

    Screen        screen_ = SCREEN_STATS;
    GpsModule*    gps_ = nullptr;
    SessionStore* store_ = nullptr;
    RadiaCode*    rc_ = nullptr;

    RadiaCode::Reading lastReading_{};
    RadiaCode::State   rcState_ = RadiaCode::State::Idle;
    String             rcAddr_;
    int                vbatPct_ = -1;

    LongAction         pendingAction_ = ACTION_NONE;
    // Stopping recording requires two consecutive long-presses within
    // kConfirmStopTimeoutMs.  The first long-press arms this flag; the
    // second actually executes the stop.  Prevents accidental mid-trip stops.
    bool               confirmStopPending_ = false;
    uint32_t           confirmStopArmMs_   = 0;
    static constexpr uint32_t kConfirmStopTimeoutMs = 5000;
    bool               forceFullRedraw_ = true;
    Screen             lastDrawnScreen_ = SCREEN_NORMAL_COUNT;

    static constexpr int MAX_FIELDS = 40;
    String   prevText_[MAX_FIELDS];
    uint16_t prevFg_[MAX_FIELDS] = {0};
    uint8_t  prevSize_[MAX_FIELDS] = {0};

    // Picker state
    std::vector<RadiaCode::ScanResult> pickList_;
    std::vector<int> pickerOrder_;          // sort order indices into pickList_
    int    pickerCursor_ = 0;
    String pickedAddr_;
    uint8_t pickedAddrType_ = 0;
};
