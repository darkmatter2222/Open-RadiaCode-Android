#include "radiacode.h"
#include "config.h"

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <algorithm>
#include <time.h>

// ----- protocol constants (mirror Android RadiacodeProtocol.kt) ---------------
namespace {
const NimBLEUUID SVC_UUID   ("e63215e5-7003-49d8-96b0-b024798fb901");
const NimBLEUUID WRITE_UUID ("e63215e6-7003-49d8-96b0-b024798fb901");
const NimBLEUUID NOTIFY_UUID("e63215e7-7003-49d8-96b0-b024798fb901");

constexpr uint16_t CMD_SET_EXCHANGE   = 0x0007;
constexpr uint16_t CMD_SET_TIME       = 0x0A04;
constexpr uint16_t CMD_RD_VIRT_SFR    = 0x0824;
constexpr uint16_t CMD_WR_VIRT_SFR    = 0x0825;
constexpr uint16_t CMD_RD_VIRT_STRING = 0x0826;

constexpr uint32_t VS_DATA_BUF        = 0x00000100;
constexpr uint32_t VSFR_DEVICE_TIME   = 0x00000504;

constexpr uint16_t BLE_CHUNK = 18;
constexpr uint32_t REQ_TIMEOUT_DEFAULT_MS  = 12000;
constexpr uint32_t REQ_TIMEOUT_EXCHANGE_MS = 25000;

constexpr const char* PREFS_NS  = "rctracker";
constexpr const char* PREFS_KEY_LAST_PEER = "last_peer";
} // namespace

// ----------------- internal state ---------------------------------------------
struct Internal {
    RadiaCode::ReadingCb onReading;
    RadiaCode::StateCb   onState;

    NimBLEAdvertisedDevice* foundDev = nullptr;
    NimBLEClient*           client = nullptr;
    NimBLERemoteCharacteristic* writeChar = nullptr;
    NimBLERemoteCharacteristic* notifyChar = nullptr;

    RadiaCode::State  state = RadiaCode::State::Idle;
    String            peerAddr;
    String            peerName;
    int               rssi = 0;

    uint8_t           seqCounter = 0;
    uint16_t          activeCmd = 0;
    uint8_t           activeSeq = 0;
    uint32_t          activeDeadlineMs = 0;
    bool              awaitingResponse = false;

    int32_t           expectedLen = -1;
    std::vector<uint8_t> respBuffer;

    uint32_t          lastPollMs = 0;
    uint32_t          lastReadingMs = 0;

    enum InitStep { INIT_NONE = 0, INIT_EXCHANGE, INIT_SET_TIME, INIT_DEV_TIME0, INIT_DONE };
    InitStep          initStep = INIT_NONE;

    // Manual picker scan
    bool              manualScanActive = false;
    uint32_t          manualScanDeadline = 0;
    std::vector<RadiaCode::ScanResult> scanResults;
    std::string       pendingConnectAddr;     // set by connectTo()

    Preferences       prefs;
};
static Internal g;

// ----------------- helpers ----------------------------------------------------
static void setState(RadiaCode::State s) {
    if (g.state == s) return;
    g.state = s;
    if (g.onState) g.onState(s, g.peerAddr);
}

static uint8_t nextSeq() {
    const uint8_t v = (uint8_t)(0x80 | (g.seqCounter & 0x1F));
    g.seqCounter = (g.seqCounter + 1) & 0x1F;
    return v;
}

static void putU16LE(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back((uint8_t)(x & 0xFF));
    v.push_back((uint8_t)((x >> 8) & 0xFF));
}
static void putU32LE(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back((uint8_t)(x & 0xFF));
    v.push_back((uint8_t)((x >> 8) & 0xFF));
    v.push_back((uint8_t)((x >> 16) & 0xFF));
    v.push_back((uint8_t)((x >> 24) & 0xFF));
}
static uint16_t readU16LE(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}
static uint32_t readU32LE(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static int32_t readI32LE(const uint8_t* p) { return (int32_t)readU32LE(p); }
static float readF32LE(const uint8_t* p) {
    uint32_t u = readU32LE(p);
    float f;
    memcpy(&f, &u, 4);
    return f;
}

// Build full request frame: <I len><H cmd><B 0><B seq> + args
static std::vector<uint8_t> buildRequest(uint16_t cmd, uint8_t seq,
                                         const uint8_t* args, size_t argLen) {
    std::vector<uint8_t> inner;
    inner.reserve(4 + argLen);
    putU16LE(inner, cmd);
    inner.push_back(0);
    inner.push_back(seq);
    inner.insert(inner.end(), args, args + argLen);

    std::vector<uint8_t> out;
    out.reserve(4 + inner.size());
    putU32LE(out, inner.size());
    out.insert(out.end(), inner.begin(), inner.end());
    return out;
}

static bool writeChunked(const std::vector<uint8_t>& bytes) {
    if (!g.writeChar) return false;
    size_t off = 0;
    while (off < bytes.size()) {
        const size_t n = std::min<size_t>(BLE_CHUNK, bytes.size() - off);
        // RadiaCode write characteristic is "write without response" capable.
        if (!g.writeChar->writeValue(bytes.data() + off, n, false)) {
            log_w("BLE write chunk failed at off=%u", (unsigned)off);
            return false;
        }
        off += n;
        delay(5);
    }
    return true;
}

// ----------------- forward decls ----------------------------------------------
static void handleNotify(NimBLERemoteCharacteristic*, uint8_t* data, size_t len, bool);
static bool sendCommand(uint16_t cmd, const uint8_t* args, size_t argLen);
static void onResponseComplete(const uint8_t* payload, size_t len);
static void decodeDataBuf(const uint8_t* p, size_t len);
static void advanceInit();

// ----------------- BLE callbacks ----------------------------------------------
static bool nameLooksLikeRadiaCode(const std::string& nIn) {
    if (nIn.empty()) return false;
    std::string n = nIn;
    for (auto& c : n) c = (char)tolower((unsigned char)c);
    // RadiaCode-101 / -102 / -103 / -103G / -110 / RC-XXX, case insensitive
    return n.rfind("radiacode", 0) == 0 ||
           n.rfind("radiacod",  0) == 0 ||
           n.rfind("rc-",       0) == 0;
}

class ScanCb : public NimBLEAdvertisedDeviceCallbacks {
public:
    void onResult(NimBLEAdvertisedDevice* dev) override {
        const std::string name = dev->getName();
        const bool nameMatch = nameLooksLikeRadiaCode(name);
        const bool svcMatch  = dev->isAdvertisingService(SVC_UUID);
        const std::string addr = dev->getAddress().toString();
        const int rssi = dev->getRSSI();

        // Serial dump for diagnostics. Print one line per device every ~1s
        // while picker is open, with the LATEST resolved name and service
        // UUIDs. This makes it easy to identify a device by holding it close.
        if (g.manualScanActive) {
            struct SeenInfo { std::string addr; std::string name; uint32_t lastLogMs; };
            static std::vector<SeenInfo> seen;
            const uint32_t now = millis();
            auto it = std::find_if(seen.begin(), seen.end(),
                [&](const SeenInfo& s){ return s.addr == addr; });
            const bool firstSight = (it == seen.end());
            const bool nameChanged = !firstSight && it->name != name && !name.empty();
            const bool dueAgain = !firstSight && (now - it->lastLogMs) > 1000;
            if (firstSight || nameChanged || dueAgain) {
                if (firstSight) {
                    seen.push_back({addr, name, now});
                } else {
                    if (!name.empty()) it->name = name;
                    it->lastLogMs = now;
                }
                std::string svcStr;
                const size_t nSvc = dev->getServiceUUIDCount();
                for (size_t i = 0; i < nSvc; ++i) {
                    if (i) svcStr += ",";
                    svcStr += dev->getServiceUUID(i).toString();
                }
                const char* tag = firstSight ? "NEW" : (nameChanged ? "NAME" : "upd");
                Serial.printf("[%s] %s rssi=%d name='%s' svcs=[%s] match=%d/%d\n",
                              tag, addr.c_str(), rssi, name.c_str(), svcStr.c_str(),
                              nameMatch ? 1 : 0, svcMatch ? 1 : 0);
            }
        }

        // Picker mode: collect EVERY advertiser. The user picks one and we
        // attempt to connect; if it's not a RadiaCode the connection will
        // simply fail at service-discovery and we return to disconnected.
        // RadiaCode 110 advertises with no name, so name-only filtering is
        // not enough.
        if (g.manualScanActive) {
            bool found = false;
            for (auto& r : g.scanResults) {
                if (r.address == addr) {
                    r.rssi = rssi;
                    if (!name.empty()) r.name = name;
                    found = true;
                    break;
                }
            }
            if (!found) {
                RadiaCode::ScanResult r;
                r.address    = addr;
                r.name       = name;
                r.rssi       = rssi;
                r.likelyMatch = nameMatch || svcMatch;
                g.scanResults.push_back(r);
            } else {
                // Bubble up the likely-match flag if it ever becomes true.
                for (auto& r : g.scanResults) {
                    if (r.address == addr && (nameMatch || svcMatch)) {
                        r.likelyMatch = true;
                        break;
                    }
                }
            }
            return;
        }

        // Auto-mode: only consider true RadiaCode matches.
        if (!(nameMatch || svcMatch)) return;
        log_i("Match: %s rssi=%d svcMatch=%d name=%s",
              addr.c_str(), rssi, svcMatch, name.c_str());
        if (!g.foundDev || dev->getRSSI() > g.foundDev->getRSSI()) {
            if (g.foundDev) delete g.foundDev;
            g.foundDev = new NimBLEAdvertisedDevice(*dev);
        }
    }
};
static ScanCb gScanCb;

class ClientCb : public NimBLEClientCallbacks {
public:
    void onConnect(NimBLEClient*) override {
        log_i("BLE connected");
    }
    void onDisconnect(NimBLEClient*) override {
        log_w("BLE disconnected");
        g.writeChar = nullptr;
        g.notifyChar = nullptr;
        g.awaitingResponse = false;
        g.expectedLen = -1;
        g.respBuffer.clear();
        g.initStep = Internal::INIT_NONE;
        setState(RadiaCode::State::Disconnected);
    }
};
static ClientCb gClientCb;

// ----------------- send / response handling -----------------------------------
static bool sendCommand(uint16_t cmd, const uint8_t* args, size_t argLen) {
    if (!g.writeChar || g.awaitingResponse) return false;

    g.activeCmd = cmd;
    g.activeSeq = nextSeq();
    g.activeDeadlineMs = millis() +
        (cmd == CMD_SET_EXCHANGE ? REQ_TIMEOUT_EXCHANGE_MS : REQ_TIMEOUT_DEFAULT_MS);
    g.awaitingResponse = true;
    g.expectedLen = -1;
    g.respBuffer.clear();

    auto frame = buildRequest(cmd, g.activeSeq, args, argLen);
    if (!writeChunked(frame)) {
        g.awaitingResponse = false;
        return false;
    }
    return true;
}

static void handleNotify(NimBLERemoteCharacteristic*, uint8_t* data, size_t len, bool) {
    if (!g.awaitingResponse) {
        // Stray notification — ignore.
        return;
    }
    // First chunk: <I total_len> + payload bytes
    if (g.expectedLen < 0) {
        if (len < 4) return;
        g.expectedLen = (int32_t)readU32LE(data);
        g.respBuffer.insert(g.respBuffer.end(), data + 4, data + len);
    } else {
        g.respBuffer.insert(g.respBuffer.end(), data, data + len);
    }

    if ((int32_t)g.respBuffer.size() >= g.expectedLen && g.expectedLen >= 4) {
        // Strip echoed 4-byte header (cmd, 0, seq) -> actual payload
        const size_t headerSize = 4;
        const uint8_t* full = g.respBuffer.data();
        const size_t   total = (size_t)g.expectedLen;
        const uint8_t* payload = full + headerSize;
        const size_t   payloadLen = (total > headerSize) ? (total - headerSize) : 0;
        g.awaitingResponse = false;
        onResponseComplete(payload, payloadLen);
    }
}

static void onResponseComplete(const uint8_t* payload, size_t len) {
    switch (g.activeCmd) {
        case CMD_RD_VIRT_STRING: {
            // <I retcode><I flen><flen bytes>
            if (len < 8) { log_w("RD_VIRT_STRING short"); break; }
            const uint32_t ret  = readU32LE(payload);
            const uint32_t flen = readU32LE(payload + 4);
            if (ret != 1 || len < 8 + flen) { log_w("RD_VIRT_STRING fail ret=%u", ret); break; }
            const uint8_t* data = payload + 8;
            size_t dlen = flen;
            // Trim trailing 0x00 like Android does.
            if (dlen > 0 && data[dlen - 1] == 0) --dlen;
            decodeDataBuf(data, dlen);
            break;
        }
        case CMD_SET_EXCHANGE:
        case CMD_SET_TIME:
        case CMD_WR_VIRT_SFR:
        default:
            // Init steps just need an ack; advance the machine.
            break;
    }

    if (g.initStep != Internal::INIT_DONE && g.state == RadiaCode::State::Initializing) {
        advanceInit();
    }
}

// ----------------- DATA_BUF decoder (matches Android RadiacodeDataBuf) --------
static void decodeDataBuf(const uint8_t* p, size_t len) {
    RadiaCode::Reading out;

    size_t i = 0;
    while (i + 7 <= len) {
        // header: <BBB i> seq, eid, gid, ts_offset
        // const uint8_t seq = p[i+0]; (unused)
        const uint8_t eid = p[i + 1];
        const uint8_t gid = p[i + 2];
        // const int32_t tsOff = readI32LE(p + i + 3); (unused)
        i += 7;

        if (eid == 0 && gid == 0) {                    // RealTimeData (15 bytes)
            if (i + 15 > len) break;
            const float    countRate  = readF32LE(p + i + 0);
            const float    doseRate   = readF32LE(p + i + 4);
            const uint16_t cpsErrRaw  = readU16LE(p + i + 8);
            const uint16_t drErrRaw   = readU16LE(p + i + 10);
            // const uint16_t flags    = readU16LE(p + i + 12);
            // const uint8_t  rtFlags  = p[i + 14];
            i += 15;

            out.valid       = true;
            out.cps         = countRate;
            out.uSvPerHour  = doseRate * 10000.0f;     // matches Android conversion
            out.cpsErrPct   = cpsErrRaw / 10.0f;
            out.doseErrPct  = drErrRaw / 10.0f;
            out.timestampMs = millis();
        } else if (eid == 0 && gid == 1) {             // RawData
            if (i + 8 > len) break; i += 8;
        } else if (eid == 0 && gid == 2) {             // DoseRateDB
            if (i + 16 > len) break; i += 16;
        } else if (eid == 0 && gid == 3) {             // RareData (battery + temp)
            if (i + 14 > len) break;
            // duration U32 + dose F32 + tempRaw U16 + chargeRaw U16 + flags U16
            const uint16_t tempRaw   = readU16LE(p + i + 8);
            const uint16_t chargeRaw = readU16LE(p + i + 10);
            i += 14;
            out.tempC       = (tempRaw - 2000) / 100.0f;
            int charge = chargeRaw / 100;
            if (charge < 0) charge = 0;
            if (charge > 100) charge = 100;
            out.battery     = (uint8_t)charge;
            out.hasMetadata = true;
        } else if (eid == 0 && (gid == 4 || gid == 5)) {
            if (i + 16 > len) break; i += 16;
        } else if (eid == 0 && gid == 6) {
            if (i + 6 > len) break; i += 6;
        } else if (eid == 0 && gid == 7) {
            if (i + 4 > len) break; i += 4;
        } else if (eid == 0 && (gid == 8 || gid == 9)) {
            if (i + 6 > len) break; i += 6;
        } else if (eid == 1 && gid >= 1 && gid <= 3) {
            if (i + 6 > len) break;
            const uint16_t samples = readU16LE(p + i);
            i += 6;
            const size_t bps = (gid == 1) ? 8 : (gid == 2 ? 16 : 14);
            const size_t skip = (size_t)samples * bps;
            if (i + skip > len) break;
            i += skip;
        } else {
            break; // unknown -> stop, don't desync
        }
    }

    if (out.valid && g.onReading) {
        g.lastReadingMs = millis();
        g.onReading(out);
    }
}

// ----------------- init state machine -----------------------------------------
static void startInit() {
    g.initStep = Internal::INIT_EXCHANGE;
    setState(RadiaCode::State::Initializing);
    // SET_EXCHANGE 0x01 0xFF 0x12 0xFF
    static const uint8_t args[] = {0x01, 0xFF, 0x12, 0xFF};
    if (!sendCommand(CMD_SET_EXCHANGE, args, sizeof(args))) {
        log_e("SET_EXCHANGE failed to send");
        g.client->disconnect();
    }
}

static void advanceInit() {
    if (g.initStep == Internal::INIT_EXCHANGE) {
        g.initStep = Internal::INIT_SET_TIME;
        // <BBBBBBBB> day, month, year-2000, 0, sec, min, hour, 0
        // Use GPS UTC if we have it eventually; for init we use system time
        // (boot=epoch 0 + millis), which is acceptable — Android does the same with local time.
        time_t now = time(nullptr);
        struct tm tmv;
        if (now <= 0) {
            // synthetic: 2026-01-01 00:00:00 to keep device happy
            memset(&tmv, 0, sizeof(tmv));
            tmv.tm_year = 126; tmv.tm_mon = 0; tmv.tm_mday = 1;
        } else {
            gmtime_r(&now, &tmv);
        }
        uint8_t args[8] = {
            (uint8_t)tmv.tm_mday,
            (uint8_t)(tmv.tm_mon + 1),
            (uint8_t)((tmv.tm_year + 1900) - 2000),
            0,
            (uint8_t)tmv.tm_sec,
            (uint8_t)tmv.tm_min,
            (uint8_t)tmv.tm_hour,
            0,
        };
        sendCommand(CMD_SET_TIME, args, sizeof(args));
        return;
    }
    if (g.initStep == Internal::INIT_SET_TIME) {
        g.initStep = Internal::INIT_DEV_TIME0;
        // WR_VIRT_SFR(VSFR_DEVICE_TIME, 0)
        std::vector<uint8_t> args;
        putU32LE(args, VSFR_DEVICE_TIME);
        putU32LE(args, 0);
        sendCommand(CMD_WR_VIRT_SFR, args.data(), args.size());
        return;
    }
    if (g.initStep == Internal::INIT_DEV_TIME0) {
        g.initStep = Internal::INIT_DONE;
        setState(RadiaCode::State::Ready);
        log_i("RadiaCode init complete");
    }
}

// ----------------- connect flow -----------------------------------------------
static bool finishConnect(NimBLEClient* client) {
    auto* svc = client->getService(SVC_UUID);
    if (!svc) { log_e("service not found"); client->disconnect(); return false; }

    g.writeChar  = svc->getCharacteristic(WRITE_UUID);
    g.notifyChar = svc->getCharacteristic(NOTIFY_UUID);
    if (!g.writeChar || !g.notifyChar) {
        log_e("char not found"); client->disconnect(); return false;
    }
    if (!g.notifyChar->subscribe(true, handleNotify)) {
        log_e("subscribe failed"); client->disconnect(); return false;
    }
    g.prefs.putString(PREFS_KEY_LAST_PEER, g.peerAddr);
    delay(500);
    startInit();
    return true;
}

static bool connectToFound() {
    if (!g.foundDev) return false;

    g.peerAddr = g.foundDev->getAddress().toString().c_str();
    g.peerName = g.foundDev->getName().c_str();
    g.rssi     = g.foundDev->getRSSI();
    setState(RadiaCode::State::Connecting);

    if (!g.client) {
        g.client = NimBLEDevice::createClient();
        g.client->setClientCallbacks(&gClientCb, false);
        g.client->setConnectionParams(12, 24, 0, 200);
        g.client->setConnectTimeout(10);
    }

    if (!g.client->connect(g.foundDev)) {
        log_e("connect() failed");
        return false;
    }
    return finishConnect(g.client);
}

static bool connectToAddress(const std::string& addr) {
    g.peerAddr = addr.c_str();
    g.peerName = "";
    g.rssi     = 0;
    setState(RadiaCode::State::Connecting);

    if (!g.client) {
        g.client = NimBLEDevice::createClient();
        g.client->setClientCallbacks(&gClientCb, false);
        g.client->setConnectionParams(12, 24, 0, 200);
        g.client->setConnectTimeout(10);
    }

    NimBLEAddress target(addr);
    if (!g.client->connect(target)) {
        log_e("connect(addr) failed for %s", addr.c_str());
        setState(RadiaCode::State::Disconnected);
        return false;
    }
    return finishConnect(g.client);
}

static void doScan(uint32_t durMs) {
    setState(RadiaCode::State::Scanning);
    if (g.foundDev) { delete g.foundDev; g.foundDev = nullptr; }
    g.scanResults.clear();

    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(&gScanCb, /*wantDuplicates=*/true);
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(99);
    scan->setDuplicateFilter(false);
    scan->start(durMs / 1000, false);
    scan->stop();

    if (g.foundDev) {
        connectToFound();
    } else {
        setState(RadiaCode::State::Disconnected);
    }
}

// Non-blocking scan (uses NimBLE's async start). Results land in g.scanResults
// via the existing ScanCb. Caller polls isManualScanComplete().
static void startAsyncScan(uint32_t durMs) {
    g.scanResults.clear();
    if (g.foundDev) { delete g.foundDev; g.foundDev = nullptr; }
    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(&gScanCb, /*wantDuplicates=*/true);
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(99);
    scan->setDuplicateFilter(false);
    scan->start(durMs / 1000, nullptr, false);   // async, no completion cb
}

// ----------------- public surface ---------------------------------------------
void RadiaCode::begin(ReadingCb onReading, StateCb onState) {
    g.onReading = std::move(onReading);
    g.onState   = std::move(onState);

    g.prefs.begin(PREFS_NS, false);

    NimBLEDevice::init("htit-tracker");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    setState(State::Idle);
}

void RadiaCode::loop() {
    const uint32_t now = millis();

    // Manual picker scan: keep scanning continuously while picker is open.
    // Each underlying NimBLE scan runs ~6s, then we restart it -- this lets
    // devices that powered on AFTER the user opened the picker still be
    // discovered, and gives more chances to catch a slowly-advertising peer.
    if (g.manualScanActive) {
        NimBLEScan* scan = NimBLEDevice::getScan();
        if ((int32_t)(now - g.manualScanDeadline) >= 0) {
            // Soft deadline reached -- stop. Caller can poll
            // isManualScanComplete() to see we're done. Keep results.
            scan->stop();
            g.manualScanActive = false;
        } else if (!scan->isScanning()) {
            // Restart scan -- previous burst finished but deadline not hit yet.
            scan->setActiveScan(true);
            scan->setInterval(80);
            scan->setWindow(60);
            scan->setDuplicateFilter(false);
            scan->start(0, nullptr, false);   // 0 = scan forever (we stop it)
        }

        // Refresh scanResults from the scan's merged table every ~500 ms.
        // NimBLE merges advertisement + scan-response into one device record
        // here, so this picks up names that streaming callbacks may miss.
        static uint32_t lastMerge = 0;
        if ((now - lastMerge) > 500) {
            lastMerge = now;
            NimBLEScanResults res = scan->getResults();
            const int n = res.getCount();
            for (int i = 0; i < n; ++i) {
                NimBLEAdvertisedDevice d = res.getDevice((uint32_t)i);
                const std::string addr = d.getAddress().toString();
                const std::string name = d.getName();
                const int rssi = d.getRSSI();
                const bool nameMatch = nameLooksLikeRadiaCode(name);
                const bool svcMatch  = d.isAdvertisingService(SVC_UUID);

                bool foundIt = false;
                for (auto& r : g.scanResults) {
                    if (r.address == addr) {
                        r.rssi = rssi;
                        if (!name.empty() && r.name != name) {
                            r.name = name;
                            // Re-evaluate likelyMatch when name resolves.
                            if (nameLooksLikeRadiaCode(name)) r.likelyMatch = true;
                            Serial.printf("[merge-name] %s -> '%s'\n",
                                          addr.c_str(), name.c_str());
                        }
                        if (svcMatch || nameMatch) r.likelyMatch = true;
                        foundIt = true;
                        break;
                    }
                }
                if (!foundIt) {
                    RadiaCode::ScanResult nr;
                    nr.address     = addr;
                    nr.name        = name;
                    nr.rssi        = rssi;
                    nr.likelyMatch = nameMatch || svcMatch;
                    g.scanResults.push_back(nr);
                }
            }
        }
        return;
    }

    // Pending connect request from picker -- direct connect by address,
    // no rescanning, no UI blocking.
    if (!g.pendingConnectAddr.empty() &&
        (g.state == State::Disconnected || g.state == State::Idle ||
         g.state == State::Scanning)) {
        std::string target = g.pendingConnectAddr;
        g.pendingConnectAddr.clear();
        log_i("Picker connect -> %s", target.c_str());
        connectToAddress(target);
        return;
    }

    // Request timeout
    if (g.awaitingResponse && (int32_t)(now - g.activeDeadlineMs) >= 0) {
        log_w("Request 0x%04X timed out", g.activeCmd);
        g.awaitingResponse = false;
        if (g.client && g.client->isConnected()) g.client->disconnect();
    }

    // Drive scan/reconnect when not connected (auto-mode)
    if (g.state == State::Idle || g.state == State::Disconnected) {
        static uint32_t nextScan = 0;
        if ((int32_t)(now - nextScan) >= 0) {
            doScan(cfg::RADIACODE_SCAN_MS);
            nextScan = millis() + cfg::RADIACODE_RECONNECT_MS;
        }
        return;
    }

    if (g.state == State::Ready && !g.awaitingResponse) {
        if ((now - g.lastPollMs) >= cfg::RADIACODE_POLL_MS) {
            g.lastPollMs = now;
            std::vector<uint8_t> args;
            putU32LE(args, VS_DATA_BUF);
            sendCommand(CMD_RD_VIRT_STRING, args.data(), args.size());
        }
    }
}

void RadiaCode::startManualScan(uint32_t durMs) {
    // Tear down any existing connection first. NimBLE disconnect is async,
    // so give it a brief moment to free the controller before we start
    // scanning -- otherwise the scanner can miss early adv packets.
    if (g.client && g.client->isConnected()) {
        g.client->disconnect();
        for (int i = 0; i < 30 && g.client->isConnected(); ++i) delay(10);
    }
    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->stop();
    scan->clearResults();
    g.scanResults.clear();
    if (g.foundDev) { delete g.foundDev; g.foundDev = nullptr; }

    g.manualScanActive = true;
    g.manualScanDeadline = millis() + durMs;
    setState(State::Scanning);

    scan->setAdvertisedDeviceCallbacks(&gScanCb, /*wantDuplicates=*/true);
    scan->setActiveScan(true);
    scan->setInterval(80);
    scan->setWindow(60);
    scan->setDuplicateFilter(false);   // get scan responses w/ names
    scan->start(0, nullptr, false);   // run until loop() stops it
}

bool RadiaCode::isManualScanActive() const   { return g.manualScanActive; }
bool RadiaCode::isManualScanComplete() const {
    return !g.manualScanActive && !g.scanResults.empty();
}
const std::vector<RadiaCode::ScanResult>& RadiaCode::getScanResults() const {
    return g.scanResults;
}
bool RadiaCode::connectTo(const std::string& address) {
    g.pendingConnectAddr = address;
    g.manualScanActive = false;
    NimBLEDevice::getScan()->stop();
    setState(State::Disconnected);    // triggers loop() to honor pendingConnectAddr
    return true;
}
void RadiaCode::cancelManualScan() {
    g.manualScanActive = false;
    NimBLEDevice::getScan()->stop();
    setState(State::Disconnected);
}

void RadiaCode::requestScan() {
    if (g.client && g.client->isConnected()) g.client->disconnect();
    setState(State::Disconnected);
}

void RadiaCode::disconnectAndForget() {
    g.prefs.remove(PREFS_KEY_LAST_PEER);
    if (g.client && g.client->isConnected()) g.client->disconnect();
}

RadiaCode::State  RadiaCode::state()       { return g.state; }
const String&     RadiaCode::peerAddress() { return g.peerAddr; }
const String&     RadiaCode::peerName()    { return g.peerName; }
int               RadiaCode::rssi()        { return g.rssi; }
