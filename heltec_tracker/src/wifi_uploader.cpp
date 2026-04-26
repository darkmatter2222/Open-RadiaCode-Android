#include "wifi_uploader.h"

#include <WiFi.h>
#include <HTTPClient.h>
#include <esp_system.h>
#include <esp_mac.h>
#include <algorithm>

#include "config.h"
#include "session_store.h"
#include "secrets.h"

namespace {

// Concatenate the chip MAC into a stable id like "esp32-aabbccddeeff".
String chipIdString() {
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    char buf[32];
    snprintf(buf, sizeof(buf), "esp32-%02x%02x%02x%02x%02x%02x",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    return String(buf);
}

// Read the entire CSV body for a session into a String. Stops if the file
// is larger than `maxBytes` (returns empty in that case so we don't blow
// the heap on a runaway session).
bool readWholeFile(SessionStore& store, const String& id, size_t maxBytes, String& out) {
    File f = store.openForRead(id);
    if (!f) return false;
    if (f.size() > maxBytes) {
        f.close();
        return false;
    }
    out.reserve((size_t)f.size());
    while (f.available()) {
        out += (char)f.read();
    }
    f.close();
    return true;
}

} // namespace


void WifiUploader::begin(SessionStore* store) {
    store_ = store;

    // No SSID == feature disabled; this is the default for `secrets.h.example`
    // so the firmware still builds for users who haven't set up Wi-Fi yet.
    if (!secrets::WIFI_SSID || secrets::WIFI_SSID[0] == '\0' ||
        !secrets::INGEST_URL || secrets::INGEST_URL[0] == '\0') {
        enabled_ = false;
        Serial.println("[WIFI] disabled (no SSID/INGEST_URL in secrets.h)");
        return;
    }
    enabled_ = true;

    // Don't auto-connect at boot; the cadenced tick() will handle it.
    WiFi.mode(WIFI_STA);
    WiFi.disconnect(true, true);
    WiFi.persistent(false);
    WiFi.setAutoReconnect(false);

    Serial.printf("[WIFI] uploader armed; ssid='%s' url='%s' interval=%us trackerId=%s\n",
                  secrets::WIFI_SSID, secrets::INGEST_URL,
                  (unsigned)(secrets::UPLOAD_INTERVAL_MS / 1000),
                  chipIdString().c_str());
}


bool WifiUploader::connectWifi() {
    Serial.printf("[WIFI] connecting to '%s'...\n", secrets::WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(secrets::WIFI_SSID, secrets::WIFI_PASSWORD);

    const uint32_t deadline = millis() + secrets::WIFI_CONNECT_TIMEOUT_MS;
    while (WiFi.status() != WL_CONNECTED && millis() < deadline) {
        delay(150);
    }
    if (WiFi.status() != WL_CONNECTED) {
        Serial.printf("[WIFI] connect timeout (status=%d)\n", (int)WiFi.status());
        WiFi.disconnect(true, true);
        return false;
    }
    Serial.printf("[WIFI] connected ip=%s rssi=%d dBm\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
    return true;
}


void WifiUploader::disconnectWifi() {
    WiFi.disconnect(true, true);
    WiFi.mode(WIFI_OFF);
}


bool WifiUploader::uploadOne(const String& sessionId, size_t expectedBytes, uint32_t expectedSamples) {
    // Cap a single body at 1 MB. The API also enforces a server-side cap.
    constexpr size_t MAX_BODY_BYTES = 1 * 1024 * 1024;
    String body;
    if (!readWholeFile(*store_, sessionId, MAX_BODY_BYTES, body) || body.length() == 0) {
        Serial.printf("[UPLOAD] %s: read failed (size=%u max=%u)\n",
                      sessionId.c_str(), (unsigned)expectedBytes, (unsigned)MAX_BODY_BYTES);
        return false;
    }

    HTTPClient http;
    if (!http.begin(secrets::INGEST_URL)) {
        Serial.printf("[UPLOAD] %s: http.begin('%s') failed\n",
                      sessionId.c_str(), secrets::INGEST_URL);
        return false;
    }
    http.setTimeout(15000);
    http.addHeader("Content-Type", "text/csv");
    http.addHeader("X-Session-Id", sessionId);
    http.addHeader("X-Tracker-Id", chipIdString());
    http.addHeader("X-Firmware",   cfg::FW_VERSION);
    if (secrets::INGEST_TOKEN && secrets::INGEST_TOKEN[0] != '\0') {
        http.addHeader("Authorization", String("Bearer ") + secrets::INGEST_TOKEN);
    }

    const uint32_t t0 = millis();
    const int code   = http.POST((uint8_t*)body.c_str(), body.length());
    const String resp = (code > 0) ? http.getString() : String();
    http.end();
    lastHttpStatus_ = code;

    if (code >= 200 && code < 300) {
        Serial.printf("[UPLOAD] %s OK http=%d %ums %u bytes resp=%s\n",
                      sessionId.c_str(), code, (unsigned)(millis() - t0),
                      (unsigned)body.length(),
                      resp.length() > 200 ? "<truncated>" : resp.c_str());
        return true;
    }
    Serial.printf("[UPLOAD] %s FAIL http=%d %ums resp=%s\n",
                  sessionId.c_str(), code, (unsigned)(millis() - t0),
                  resp.length() > 200 ? "<truncated>" : resp.c_str());
    return false;
}


uint32_t WifiUploader::runOnce() {
    if (!enabled_ || !store_) return 0;

    auto sessions = store_->listSessions();
    // Don't upload the active (still-being-written) session.
    sessions.erase(std::remove_if(sessions.begin(), sessions.end(),
                                  [this](const SessionStore::SessionInfo& s) {
                                      return store_->isActive(s.id);
                                  }),
                   sessions.end());
    if (sessions.empty()) {
        return 0;   // nothing to do; don't even bring up Wi-Fi
    }

    busy_ = true;
    lastAttempt_ = millis();
    Serial.printf("[UPLOAD] cycle start; %u session(s) pending\n", (unsigned)sessions.size());

    if (!connectWifi()) {
        ++failedCount_;
        busy_ = false;
        return 0;
    }

    uint32_t ok = 0;
    for (const auto& s : sessions) {
        if (uploadOne(s.id, s.sizeBytes, s.samples)) {
            // Server confirmed; safe to free the on-device copy.
            if (store_->removeSession(s.id)) {
                ++ok;
                ++uploadedCount_;
                lastSuccess_ = millis();
                Serial.printf("[UPLOAD] %s removed from device\n", s.id.c_str());
            } else {
                Serial.printf("[UPLOAD] %s: server OK but local remove failed\n", s.id.c_str());
                ++failedCount_;
            }
        } else {
            ++failedCount_;
        }
        // Yield between large uploads so BLE keeps running.
        delay(50);
    }

    disconnectWifi();
    busy_ = false;
    Serial.printf("[UPLOAD] cycle done; ok=%u/%u\n",
                  (unsigned)ok, (unsigned)sessions.size());
    return ok;
}


void WifiUploader::tick() {
    if (!enabled_ || !store_ || busy_) return;
    const uint32_t now = millis();
    const uint32_t since = now - lastAttempt_;
    if (!forceNow_ && lastAttempt_ != 0 && since < secrets::UPLOAD_INTERVAL_MS) {
        return;
    }
    // First-call grace: wait 15s after boot before the very first attempt
    // so the BLE/GPS subsystems can come up cleanly.
    if (lastAttempt_ == 0 && now < 15000 && !forceNow_) return;

    forceNow_ = false;
    runOnce();
}
