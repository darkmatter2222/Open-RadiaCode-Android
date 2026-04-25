#pragma once
#include <Arduino.h>

// Single PRG-button helper with short/long-press semantics.
class Button {
public:
    enum Event : uint8_t { NONE = 0, SHORT_PRESS, LONG_PRESS };

    void begin(uint8_t pin, uint16_t debounceMs, uint16_t longMs);
    Event poll();   // call from loop()

private:
    uint8_t  pin_ = 0;
    uint16_t debounceMs_ = 30;
    uint16_t longMs_ = 800;

    bool     lastRaw_ = true;       // active LOW => idle is true
    bool     pressed_ = false;
    uint32_t lastChangeMs_ = 0;
    uint32_t pressStartMs_ = 0;
    bool     longFired_ = false;
};
