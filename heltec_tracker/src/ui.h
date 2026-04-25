#pragma once
#include <Arduino.h>
#include "radiacode.h"

class GpsModule;
class SessionStore;

class Ui {
public:
    enum Screen : uint8_t { SCREEN_STATS = 0, SCREEN_GPS, SCREEN_STORAGE, SCREEN_COUNT };

    void begin();
    void setSources(GpsModule* gps, SessionStore* store, RadiaCode* rc);

    // Inputs
    void onShortPress();   // cycle screen
    void onLongPress();    // contextual

    // External pushes
    void setReading(const RadiaCode::Reading& r);
    void setRadiaState(RadiaCode::State s, const String& addr);
    void setBatteryPercent(int pct) { vbatPct_ = pct; }

    void tick();           // call from loop()

    // Returned by onLongPress for the main loop to action.
    enum LongAction : uint8_t { ACTION_NONE = 0, ACTION_TOGGLE_REC, ACTION_RESCAN };
    LongAction lastLongAction() {
        LongAction a = pendingAction_;
        pendingAction_ = ACTION_NONE;
        return a;
    }

private:
    void renderStats();
    void renderGps();
    void renderStorage();
    void renderHeader();

    Screen        screen_ = SCREEN_STATS;
    GpsModule*    gps_ = nullptr;
    SessionStore* store_ = nullptr;
    RadiaCode*    rc_ = nullptr;

    RadiaCode::Reading lastReading_{};
    RadiaCode::State   rcState_ = RadiaCode::State::Idle;
    String             rcAddr_;
    int                vbatPct_ = -1;

    uint32_t           lastDrawMs_ = 0;
    bool               dirty_ = true;
    Screen             lastDrawnScreen_ = SCREEN_COUNT;

    LongAction         pendingAction_ = ACTION_NONE;
};
