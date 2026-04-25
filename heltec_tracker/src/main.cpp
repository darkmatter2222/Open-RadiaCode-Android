// HTIT-Tracker firmware entry point.
// - Heltec WiFi LoRa 32 V3 (ESP32-S3) on the HTIT-Tracker V1.2 carrier.
// - Connects (BLE central) to a RadiaCode dosimeter.
// - Logs CSV samples to LittleFS in the same schema as the Android app:
//     timestampMs,uSvPerHour,cps,latitude,longitude,deviceId

#include <Arduino.h>
#include "config.h"
#include "button.h"
#include "gps_module.h"
#include "radiacode.h"
#include "session_store.h"
#include "ui.h"

namespace {
Button       gButton;
GpsModule    gGps;
RadiaCode    gRadia;
SessionStore gStore;
Ui           gUi;
} // namespace

static int readBatteryPercent() {
    digitalWrite(cfg::VBAT_EN_PIN, HIGH);    // enable divider
    delay(10);
    const int raw = analogRead(cfg::VBAT_ADC_PIN);
    digitalWrite(cfg::VBAT_EN_PIN, LOW);     // disable to save power

    // ESP32-S3 ADC ~3.3V full scale at 12-bit.
    const float volts = (raw / 4095.0f) * 3.3f * cfg::VBAT_DIV_MULT;
    // segmented LiPo curve
    if (volts >= 4.20f) return 100;
    if (volts >= 4.05f) return 90 + (int)((volts - 4.05f) / 0.015f);
    if (volts >= 3.90f) return 70 + (int)((volts - 3.90f) / 0.0075f);
    if (volts >= 3.75f) return 40 + (int)((volts - 3.75f) / 0.005f);
    if (volts >= 3.60f) return 10 + (int)((volts - 3.60f) / 0.005f);
    if (volts >= 3.30f) return (int)((volts - 3.30f) / 0.030f);
    return 0;
}

static void enablePeripherals() {
    // VTFT/VGNSS rail. The Heltec HT_st7735 library (used by the reference
    // darkmatter HTIT-Tracker firmware) drives this pin HIGH to enable the
    // 3.3V rail feeding both the ST7735 panel and the UC6580 GNSS module.
    pinMode(cfg::VGNSS_CTRL_PIN, OUTPUT);
    digitalWrite(cfg::VGNSS_CTRL_PIN, HIGH);

    // TFT backlight (active HIGH).
    pinMode(cfg::BL_CTRL_PIN, OUTPUT);
    digitalWrite(cfg::BL_CTRL_PIN, HIGH);

    // Battery divider control (idle LOW; pulsed HIGH only when sampling).
    pinMode(cfg::VBAT_EN_PIN, OUTPUT);
    digitalWrite(cfg::VBAT_EN_PIN, LOW);

    delay(250);   // let regulator + GNSS settle
}

void setup() {
    Serial.begin(115200);
    // Heltec V3 uses native USB CDC; give the host a moment to enumerate so
    // the first prints aren't lost.
    const uint32_t cdcDeadline = millis() + 1500;
    while (!Serial && millis() < cdcDeadline) { delay(10); }
    Serial.println();
    Serial.printf("HTIT-Tracker firmware v%s starting...\n", cfg::FW_VERSION);

    enablePeripherals();
    gButton.begin(cfg::BUTTON_PIN, cfg::BUTTON_DEBOUNCE_MS, cfg::BUTTON_LONG_PRESS_MS);

    gUi.begin();
    gGps.begin();

    if (!gStore.begin()) {
        Serial.println("LittleFS failed -- recording disabled");
    } else {
        gStore.resumeIfActive();
    }

    gUi.setSources(&gGps, &gStore, &gRadia);
    gUi.setRadiaState(RadiaCode::State::Idle, String());

    gRadia.begin(
        // onReading
        [](const RadiaCode::Reading& r) {
            gUi.setReading(r);

            // Build deviceId = address w/o colons (compact)
            String id = gRadia.peerAddress();
            id.replace(":", "");

            // Prefer GPS UTC timestamp; fall back to millis() since boot.
            uint64_t ts = gGps.utcEpochMs();
            if (ts == 0) ts = (uint64_t)millis();

            gStore.append(0, ts, r.uSvPerHour, r.cps,
                          gGps.hasFix(), gGps.latitude(), gGps.longitude(),
                          id);
        },
        // onState
        [](RadiaCode::State s, const String& addr) {
            gUi.setRadiaState(s, addr);
            Serial.printf("[RC] state=%d addr=%s\n", (int)s, addr.c_str());
        }
    );

    Serial.println("Setup complete");
}

void loop() {
    gGps.update();
    gRadia.loop();

    // If a manual scan was kicked off and just completed, hand the results
    // to the UI so the picker is populated.
    static bool manualScanArmed = false;
    if (manualScanArmed && !gRadia.isManualScanActive()) {
        manualScanArmed = false;
        gUi.enterPicker(gRadia.getScanResults());
    }

    // Button events
    switch (gButton.poll()) {
        case Button::SHORT_PRESS:
            gUi.onShortPress();
            break;
        case Button::LONG_PRESS:
            gUi.onLongPress();
            switch (gUi.lastLongAction()) {
                case Ui::ACTION_TOGGLE_REC:
                    gStore.toggle();
                    break;
                case Ui::ACTION_START_PICKER:
                    Serial.println("[UI] starting RadiaCode picker scan");
                    gRadia.startManualScan(15000);
                    gUi.enterPicker({});      // show "Scanning..." placeholder
                    manualScanArmed = true;
                    break;
                case Ui::ACTION_PICK_DEVICE: {
                    String addr = gUi.pickedAddress();
                    uint8_t aType = gUi.pickedAddrType();
                    Serial.printf("[UI] picker chose %s (type=%u)\n", addr.c_str(), (unsigned)aType);
                    gRadia.connectTo(std::string(addr.c_str()), aType);
                    gUi.exitPicker();
                    break;
                }
                case Ui::ACTION_CANCEL_PICKER:
                    gRadia.cancelManualScan();
                    gUi.exitPicker();
                    break;
                default: break;
            }
            break;
        default: break;
    }

    // While in picker mode, push the (possibly growing) list to the UI ~2 Hz.
    // The UI itself decides if anything actually changed and only redraws then.
    if (manualScanArmed && gRadia.isManualScanActive()) {
        static uint32_t lastListPush = 0;
        const uint32_t now2 = millis();
        if ((now2 - lastListPush) > 500) {
            lastListPush = now2;
            gUi.enterPicker(gRadia.getScanResults());
        }
    }

    // Battery refresh + heartbeat log
    static uint32_t lastBat = 0;
    static uint32_t lastBeat = 0;
    const uint32_t now = millis();
    if ((now - lastBat) > 5000) {
        lastBat = now;
        gUi.setBatteryPercent(readBatteryPercent());
    }
    if ((now - lastBeat) > cfg::HEARTBEAT_MS) {
        lastBeat = now;
        Serial.printf("[HB] uptime=%lus fix=%d sats=%u rcState=%d rec=%d samples=%u\n",
                      (unsigned long)(now / 1000),
                      (int)gGps.hasFix(),
                      (unsigned)gGps.satellites(),
                      (int)gRadia.state(),
                      (int)gStore.isRecording(),
                      (unsigned)gStore.sampleCount());
    }

    gUi.tick();
    delay(2);
}
