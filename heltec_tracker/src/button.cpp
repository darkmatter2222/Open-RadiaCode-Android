#include "button.h"

void Button::begin(uint8_t pin, uint16_t debounceMs, uint16_t longMs) {
    pin_ = pin;
    debounceMs_ = debounceMs;
    longMs_ = longMs;
    pinMode(pin_, INPUT_PULLUP);
    lastRaw_ = digitalRead(pin_);
    lastChangeMs_ = millis();
}

Button::Event Button::poll() {
    const uint32_t now = millis();
    const bool raw = digitalRead(pin_);

    if (raw != lastRaw_) {
        lastRaw_ = raw;
        lastChangeMs_ = now;
        return NONE;
    }

    // Stable for debounce window?
    if ((now - lastChangeMs_) < debounceMs_) return NONE;

    const bool downStable = (raw == LOW);

    if (downStable && !pressed_) {
        pressed_ = true;
        pressStartMs_ = now;
        longFired_ = false;
        return NONE;
    }

    if (pressed_ && downStable && !longFired_ &&
        (now - pressStartMs_) >= longMs_) {
        longFired_ = true;
        return LONG_PRESS;       // fires while still held
    }

    if (!downStable && pressed_) {
        pressed_ = false;
        const bool wasLong = longFired_;
        longFired_ = false;
        return wasLong ? NONE : SHORT_PRESS;
    }

    return NONE;
}
