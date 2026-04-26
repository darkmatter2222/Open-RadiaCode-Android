#include "radiacode.h"
#include "config.h"

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_log.h>
#include <algorithm>
#include <time.h>

// ----- protocol constants (mirror Android RadiacodeProtocol.kt) ---------------
namespace {
const NimBLEUUID SVC_UUID   ("e63215e5-7003-49d8-96b0-b024798fb901");
const NimBLEUUID WRITE_UUID ("e63215e6-7003-49d8-96b0-b024798fb901");
const NimBLEUUID NOTIFY_UUID("e63215e7-7003-49d8-96b0-b024798fb901");

// Nordic UART Service short UUID. RadiaCode devices are built on Nordic
// chips and advertise this service in their adv packet -- their custom
// service UUID is only visible after GATT discovery (post-connect). So we
// also flag any device advertising 0xfeaf as a "likely RadiaCode" candidate.
const NimBLEUUID NORDIC_UART_SVC((uint16_t)0xfeaf);

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
constexpr const char* PREFS_KEY_PINNED    = "pinned_peer";  // user-pinned target; auto-mode connects ONLY to this
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
    uint8_t           pendingConnectAddrType = 0; // BLE_ADDR_PUBLIC by default

    // When non-empty, ScanCb captures any adv matching this address into
    // g.foundDev (regardless of name/svc match). Used by connectToAddress()
    // so we can wait for a sleepy peer to actually beacon before connecting,
    // and so we keep the BT5 ext-adv SID/PHY metadata from the adv event.
    std::string       targetAddr;

    // User-pinned address. When set, auto-mode (idle-loop scan-then-connect)
    // ONLY accepts adv from this address. Prevents wasting time on imposter
    // peers that happen to advertise the RadiaCode service UUID but aren't
    // real RadiaCodes (service discovery fails after connect).
    std::string       pinnedAddr;

    // Auto-grab name pattern (case-insensitive substring of advertised local
    // name). When set, ScanCb watches for ANY connectable peer whose name
    // contains this pattern and immediately captures+connects to it. Used to
    // race the brief connectable-ADV window of bonded RadiaCode-110 units.
    std::string       grabPattern;

    // Auto-retry halt. Set when a connection attempt reaches GATT but
    // fails (SMP timeout / char discovery timeout / init drop). The 110
    // firmware appears to soft-brick after a few failed attempts in
    // quick succession (operations slow from <1s to 30s, then refuse).
    // Once halted we stop the auto-mode scan/reconnect loop and wait
    // for an explicit user command (t / c / disconnectAndForget) which
    // clears it. Doesn't affect the BLE peer itself -- just our retry
    // policy.
    bool              autoRetryHalted = false;

    // Tracks the millis() of the most recent BLE_CONNECT event. Used by
    // onDisconnect to detect short-lived links (likely a soft-bricked
    // peer): if the link lasted less than ~90s we set autoRetryHalted to
    // stop the firmware from immediately re-scanning + reconnecting,
    // which appears to compound the 110's already-flaky state.
    uint32_t          lastConnectMs = 0;

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
        // Write WITHOUT response. The radiacode write characteristic is
        // declared write-without-response capable, and that's the path
        // both the python radiacode lib and the firmware-validated 102
        // configuration use. Forcing write-with-response on a 110 has
        // been observed to make it silently drop notifications.
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

// Walk the raw advertising payload (TLV: len, type, data...) looking for the
// Complete Local Name (0x09) or Shortened Local Name (0x08). NimBLE's
// AdvertisedDevice::getName() only reflects the most recent packet, so for
// devices whose name is only in the SCAN_RSP we may need to keep the
// previously-extracted name -- but parsing the live payload here lets us pick
// up the name from whichever packet (adv or scan response) just arrived.
static std::string extractNameFromPayload(NimBLEAdvertisedDevice* dev) {
    uint8_t* p = dev->getPayload();
    const size_t total = dev->getPayloadLength();
    if (!p || total == 0) return "";
    size_t i = 0;
    while (i < total) {
        const uint8_t fieldLen = p[i];
        if (fieldLen == 0 || i + fieldLen >= total) break;
        const uint8_t fieldType = p[i + 1];
        if (fieldType == 0x09 || fieldType == 0x08) {     // complete or short local name
            return std::string((const char*)&p[i + 2], fieldLen - 1);
        }
        i += 1 + fieldLen;
    }
    return "";
}

class ScanCb : public NimBLEAdvertisedDeviceCallbacks {
public:
    void onResult(NimBLEAdvertisedDevice* dev) override {
        // Name resolution: prefer NimBLE's getName(), fall back to manual
        // payload parsing (covers cases where getName() returns empty even
        // though the local-name TLV is present in the current packet).
        std::string name = dev->getName();
        if (name.empty()) name = extractNameFromPayload(dev);
        const bool nameMatch = nameLooksLikeRadiaCode(name);
        const bool svcMatch  = dev->isAdvertisingService(SVC_UUID) ||
                               dev->isAdvertisingService(NORDIC_UART_SVC);
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
                Serial.printf("[%s] %s type=%u rssi=%d name='%s' svcs=[%s] match=%d/%d\n",
                              tag, addr.c_str(), (unsigned)dev->getAddressType(),
                              rssi, name.c_str(), svcStr.c_str(),
                              nameMatch ? 1 : 0, svcMatch ? 1 : 0);
            }
        }

        // Picker mode: collect EVERY advertiser. The user picks one and we
        // attempt to connect; if it's not a RadiaCode the connection will
        // simply fail at service-discovery and we return to disconnected.
        // RadiaCode 110 advertises with no name, so name-only filtering is
        // not enough.
        if (g.manualScanActive) {
            const uint8_t aType = dev->getAddressType();
            bool found = false;
            for (auto& r : g.scanResults) {
                if (r.address == addr) {
                    r.rssi = rssi;
                    r.addrType = aType;
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
                r.addrType   = aType;
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

        // Targeted address mode: explicit connect-by-address waits for the
        // peer to actually beacon, then captures the adv (incl. ext-adv
        // SID/PHY) so we connect with full BT5 metadata.
        if (!g.targetAddr.empty()) {
            if (addr == g.targetAddr) {
                const uint8_t at = dev->getAdvType();
                const bool isLegacy = dev->isLegacyAdvertisement();
                const bool isConnectable = dev->isConnectable();
                log_i("Target seen: %s rssi=%d advType=%u legacy=%d conn=%d primPhy=%u secPhy=%u sid=%u name=%s",
                      addr.c_str(), rssi, (unsigned)at, isLegacy, isConnectable,
                      (unsigned)dev->getPrimaryPhy(), (unsigned)dev->getSecondaryPhy(),
                      (unsigned)dev->getSetId(), name.c_str());
                // Always capture the latest adv -- we'll attempt CONNECT_REQ
                // regardless of the advertised connectable flag (some peers
                // accept connections even from NONCONN_IND state once the
                // central has the address; worst case it times out and we
                // retry on the next adv).
                if (g.foundDev) delete g.foundDev;
                g.foundDev = new NimBLEAdvertisedDevice(*dev);
            }
            return;
        }

        // Auto-grab pattern check: a separate fast path that catches ANY
        // connectable peer whose name contains the user-supplied pattern.
        // Bypasses the RadiaCode name/service filter entirely so it can
        // grab a 110 the moment its brief connectable window opens.
        if (!g.grabPattern.empty() && !name.empty() && dev->isConnectable()) {
            std::string ln = name;
            for (auto& c : ln) c = (char)tolower((unsigned char)c);
            std::string lp = g.grabPattern;
            for (auto& c : lp) c = (char)tolower((unsigned char)c);
            if (ln.find(lp) != std::string::npos) {
                log_i("GRAB hit: name='%s' addr=%s rssi=%d -- pinning + connecting",
                      name.c_str(), addr.c_str(), rssi);
                g.pinnedAddr = addr;
                g.prefs.putString(PREFS_KEY_PINNED, String(addr.c_str()));
                g.grabPattern.clear();
                g.prefs.remove("grab_pat");
                if (g.foundDev) delete g.foundDev;
                g.foundDev = new NimBLEAdvertisedDevice(*dev);
                return;
            }
        }

        // Auto-mode: only consider true RadiaCode matches.
        if (!(nameMatch || svcMatch)) return;
        const uint8_t at = dev->getAdvType();
        const bool isConn = dev->isConnectable();
        log_i("Match: %s rssi=%d svcMatch=%d advType=%u legacy=%d conn=%d name=%s",
              addr.c_str(), rssi, svcMatch, (unsigned)at,
              dev->isLegacyAdvertisement(), isConn, name.c_str());
        if (!isConn) return;  // skip non-connectable peers in auto mode
        // If user has pinned a specific target, ONLY auto-connect to that
        // address. Prevents wasted connect attempts on imposter peers that
        // happen to advertise the RadiaCode service UUID but fail svc disc.
        if (!g.pinnedAddr.empty() && addr != g.pinnedAddr) return;
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
        g.lastConnectMs = millis();
    }
    void onDisconnect(NimBLEClient*) override {
        const uint32_t now = millis();
        const uint32_t linkAgeMs = (g.lastConnectMs == 0)
            ? 0xFFFFFFFFu
            : (now - g.lastConnectMs);
        log_w("BLE disconnected (link age=%ums)", (unsigned)linkAgeMs);
        g.writeChar = nullptr;
        g.notifyChar = nullptr;
        g.awaitingResponse = false;
        g.expectedLen = -1;
        g.respBuffer.clear();
        g.initStep = Internal::INIT_NONE;
        // Halt auto-retry if the peer dropped us in under 90s. This is
        // almost always a soft-bricked RadiaCode-110 -- repeated
        // immediate reconnect attempts compound the brick. The user must
        // power-cycle the peer then re-issue a 't' / 'c' command to
        // resume.
        if (linkAgeMs < 90000) {
            g.autoRetryHalted = true;
            log_w("autoRetryHalted: link dropped after %ums (likely soft-bricked peer). Power-cycle the RadiaCode then issue 't 110' or 'c <addr>' to resume.", (unsigned)linkAgeMs);
        }
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
    // Negotiate larger MTU before service discovery. Default is 23 (legacy)
    // which is enough for the e632... write/notify protocol BUT some Nordic-
    // based peers (notably RadiaCode-110) close the link if no MTU exchange
    // happens within the supervision window. Always-safe to ask for 247.
    {
        const uint16_t mtu = client->getMTU();
        log_i("finishConnect: initial MTU=%u, requesting exchange", mtu);
        // NimBLE-Arduino exposes setMTU only at device-init; the actual
        // exchange happens automatically on first GATT op. Triggering a
        // small read primes the exchange and gives the peer a chance to
        // negotiate before our discovery flood.
    }

    // The RadiaCode-110 firmware requires the central to ATTEMPT SMP
    // before it will answer the protocol commands -- without this call,
    // char discovery succeeds but every SET_EXCHANGE write is silently
    // ignored and the link drops at state=Initializing. The pairing
    // itself ALWAYS fails on the 110 (rc=1283 "auth requirements") and
    // we don't actually want a bond -- we just need to trigger the
    // peer's policy bit. Failure here is non-fatal. The 102 also accepts
    // this with no side effects.
    {
        log_i("finishConnect: securing link...");
        const bool secOk = client->secureConnection();
        log_i("finishConnect: secureConnection() -> %d", secOk ? 1 : 0);
    }

    auto* svc = client->getService(SVC_UUID);
    if (!svc) {
        log_e("service %s not found -- enumerating peer services for diagnostics:", SVC_UUID.toString().c_str());
        std::vector<NimBLERemoteService*>* svcs = client->getServices(true);
        if (svcs) {
            for (auto* s : *svcs) {
                log_e("  peer svc: %s", s->getUUID().toString().c_str());
            }
        }
        g.autoRetryHalted = true;
        log_w("autoRetryHalted: peer likely soft-bricked, power-cycle the RadiaCode then issue 't' / 'c' / 'd' to resume");
        client->disconnect();
        return false;
    }
    log_i("finishConnect: got service %s, looking up chars", SVC_UUID.toString().c_str());

    // Force a single bulk char discovery for the service. Calling
    // getCharacteristic(uuid) twice triggers two round-trips and on the
    // RadiaCode-110 occasionally times out the second one. Doing one
    // discover-all up front populates the cache for both subsequent
    // lookups.
    {
        std::vector<NimBLERemoteCharacteristic*>* chars = svc->getCharacteristics(true);
        if (!chars || chars->empty()) {
            log_e("bulk char discovery failed (got %u)",
                  (unsigned)(chars ? chars->size() : 0));
            g.autoRetryHalted = true;
            log_w("autoRetryHalted: char discovery timed out (peer soft-bricked) -- power-cycle the RadiaCode");
            client->disconnect();
            return false;
        }
        log_i("finishConnect: discovered %u chars", (unsigned)chars->size());
    }

    g.writeChar  = svc->getCharacteristic(WRITE_UUID);
    g.notifyChar = svc->getCharacteristic(NOTIFY_UUID);
    if (!g.writeChar || !g.notifyChar) {
        log_e("char not found (write=%p notify=%p) -- listing service chars:", g.writeChar, g.notifyChar);
        std::vector<NimBLERemoteCharacteristic*>* chars = svc->getCharacteristics(true);
        if (chars) {
            for (auto* c : *chars) {
                log_e("  char: %s", c->getUUID().toString().c_str());
            }
        }
        client->disconnect();
        return false;
    }
    if (!g.notifyChar->subscribe(true, handleNotify)) {
        log_e("subscribe failed"); client->disconnect(); return false;
    }

    // If we don't have a name yet (RadiaCode 110 doesn't broadcast it), read
    // the GAP Device Name characteristic (0x2A00) of the Generic Access service
    // (0x1800). This is what Android does when you tap "pair new device".
    if (g.peerName.length() == 0) {
        auto* gap = client->getService(NimBLEUUID((uint16_t)0x1800));
        if (gap) {
            auto* devNameChar = gap->getCharacteristic(NimBLEUUID((uint16_t)0x2A00));
            if (devNameChar && devNameChar->canRead()) {
                std::string n = devNameChar->readValue();
                if (!n.empty()) {
                    g.peerName = String(n.c_str());
                    log_i("Resolved GAP device name: %s", n.c_str());
                }
            }
        }
    }

    g.prefs.putString(PREFS_KEY_LAST_PEER, g.peerAddr);
    delay(500);
    startInit();
    return true;
}

static void teardownClient() {
    if (g.client) {
        if (g.client->isConnected()) g.client->disconnect();
        NimBLEDevice::deleteClient(g.client);
        g.client = nullptr;
    }
}

static void freshClient() {
    teardownClient();
    g.client = NimBLEDevice::createClient();
    g.client->setClientCallbacks(&gClientCb, false);
    // Slower interval (50-100 ms) aligns with RadiaCode's adv interval and
    // greatly improves the chance the peer hears CONNECT_REQ. Old 15-30 ms
    // intervals consistently produced status=13 timeouts.
    // Supervision = 3000 * 10ms = 30s; the RadiaCode-110 takes longer than
    // the previous 6s budget for initial service discovery (likely doing
    // internal pairing setup) which caused link drops with rc=7 ENOTCONN.
    g.client->setConnectionParams(40, 80, 0, 3000);
    g.client->setConnectTimeout(20);
    // Force 1M PHY only. Default phyMask is 1M|2M|CODED -- some peripherals
    // fail to ACK CONNECT_REQ when CODED PHY is offered if they don't
    // support it on the BLE 5 secondary channel. RadiaCode peers are 1M.
#if CONFIG_BT_NIMBLE_EXT_ADV
    g.client->setConnectPhy(BLE_GAP_LE_PHY_1M_MASK);
#endif
}

static bool connectToFound() {
    if (!g.foundDev) return false;

    g.peerAddr = g.foundDev->getAddress().toString().c_str();
    g.peerName = g.foundDev->getName().c_str();
    g.rssi     = g.foundDev->getRSSI();
    setState(RadiaCode::State::Connecting);

    NimBLEScan* sc = NimBLEDevice::getScan();
    if (sc->isScanning()) sc->stop();
    delay(40);

    bool ok = false;
    for (int attempt = 1; attempt <= 4; ++attempt) {
        freshClient();
        ok = g.client->connect(g.foundDev, /*deleteAttibutes=*/true);
        if (ok) break;
        log_w("connectToFound attempt %d failed", attempt);
        delay(600);
    }
    if (!ok) {
        log_e("connect() failed");
        return false;
    }
    return finishConnect(g.client);
}

// Wait (scanning) for the peer to advertise as CONNECTABLE. Returns true if
// g.foundDev was populated with a connectable adv from the target. Sleepy
// RadiaCode peers (and peers that are currently connected to another central)
// often broadcast NONCONN_IND for long periods between brief connectable
// windows. We only return success on a genuinely connectable adv so the
// subsequent CONNECT_REQ has a real chance of being ACKed.
static bool waitForConnectableAdv(const std::string& addr, uint32_t waitMs) {
    NimBLEScan* scan = NimBLEDevice::getScan();
    if (scan->isScanning()) scan->stop();
    if (g.foundDev) { delete g.foundDev; g.foundDev = nullptr; }
    g.targetAddr = addr;
    g.manualScanActive = false;

    scan->setAdvertisedDeviceCallbacks(&gScanCb, /*wantDuplicates=*/true);
    scan->setActiveScan(true);
    scan->setInterval(48);   // 30 ms
    scan->setWindow(48);     // 100% duty cycle
    scan->setDuplicateFilter(false);
    scan->setMaxResults(0);
    scan->start(0, nullptr, false);  // continuous

    const uint32_t start = millis();
    const uint32_t reportEvery = 10000;
    uint32_t nextReport = start + reportEvery;
    int sawNonConn = 0;
    while (millis() - start < waitMs) {
        if (g.foundDev) {
            // ScanCb captures every adv from target. Check if THIS one is connectable.
            if (g.foundDev->isConnectable()) {
                scan->stop();
                log_i("Connectable adv captured from %s after %lu ms",
                      addr.c_str(), (unsigned long)(millis() - start));
                g.targetAddr.clear();
                return true;
            }
            // Non-connectable adv: discard and keep waiting.
            delete g.foundDev;
            g.foundDev = nullptr;
            sawNonConn++;
        }
        if (millis() >= nextReport) {
            log_i("Waiting for %s connectable adv... %lu/%lu s (saw %d non-conn)",
                  addr.c_str(),
                  (unsigned long)((millis() - start) / 1000),
                  (unsigned long)(waitMs / 1000),
                  sawNonConn);
            nextReport += reportEvery;
        }
        delay(20);
    }
    scan->stop();
    g.targetAddr.clear();
    log_w("%s never went connectable in %lu s window (saw %d non-conn adv)",
          addr.c_str(), (unsigned long)(waitMs / 1000), sawNonConn);
    return false;
}

// Legacy entry: wait for ANY adv from address. Kept for callers that don't
// require the peer to be connectable (e.g. picker UI showing presence).
static bool waitForTargetAdv(const std::string& addr, uint32_t waitMs) {
    NimBLEScan* scan = NimBLEDevice::getScan();
    if (scan->isScanning()) scan->stop();
    if (g.foundDev) { delete g.foundDev; g.foundDev = nullptr; }
    g.targetAddr = addr;
    g.manualScanActive = false;

    scan->setAdvertisedDeviceCallbacks(&gScanCb, /*wantDuplicates=*/true);
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(99);
    scan->setDuplicateFilter(false);
    scan->setMaxResults(0);
    scan->start(0, nullptr, false);  // continuous

    const uint32_t start = millis();
    const uint32_t reportEvery = 5000;
    uint32_t nextReport = start + reportEvery;
    while (millis() - start < waitMs) {
        if (g.foundDev) {
            scan->stop();
            log_i("Target adv captured after %lu ms", (unsigned long)(millis() - start));
            g.targetAddr.clear();
            return true;
        }
        if (millis() >= nextReport) {
            log_i("Waiting for %s to advertise... %lu/%lu s",
                  addr.c_str(),
                  (unsigned long)((millis() - start) / 1000),
                  (unsigned long)(waitMs / 1000));
            nextReport += reportEvery;
        }
        delay(50);
    }
    scan->stop();
    g.targetAddr.clear();
    return false;
}

static bool connectToAddress(const std::string& addr, uint8_t addrType) {
    (void)addrType;  // type comes from the captured adv now
    g.peerAddr = addr.c_str();
    g.peerName = "";
    g.rssi     = 0;
    setState(RadiaCode::State::Connecting);

    NimBLEScan* sc = NimBLEDevice::getScan();
    if (sc->isScanning()) sc->stop();
    g.manualScanActive = false;
    delay(40);

    // Forever-retry strategy:
    //  1. Wait (scanning) for the target to broadcast a CONNECTABLE adv. We
    //     deliberately ignore non-connectable advertisements -- CONNECT_REQ
    //     against a NONCONN_IND advertiser is guaranteed to time out
    //     (status=13). RadiaCode peers that are already connected to a
    //     phone broadcast NONCONN_IND continuously; we just keep waiting.
    //  2. The instant we see ADV_IND / ADV_DIRECT_IND (or BT5 connectable
    //     ext-adv), issue CONNECT_REQ with a SHORT timeout. The connectable
    //     window from a sleepy peer is often brief; a short timeout lets us
    //     fail fast and resume scanning.
    //  3. On failure, brief client teardown + immediate re-scan.
    bool ok = false;
    int attempt = 0;
    int connectableHits = 0;
    while (!ok) {
        attempt++;
        log_i("Connect %s attempt %d -- waiting for connectable adv", addr.c_str(), attempt);
        // Wait up to 5 minutes per cycle for a connectable adv.
        if (!waitForConnectableAdv(addr, 300000)) {
            log_w("attempt %d: no connectable adv in 5min window. The peer may be busy with another central (e.g. your phone). Retrying.", attempt);
            teardownClient();
            delay(1000);
            if (attempt >= 200) {
                log_e("giving up on %s after %d attempts (~16 hours)", addr.c_str(), attempt);
                break;
            }
            continue;
        }
        connectableHits++;
        log_i("attempt %d: connectable adv #%d captured, firing CONNECT_REQ", attempt, connectableHits);
        freshClient();
        // Short connect timeout (5 s) -- the connectable window is brief; if
        // CONNECT_REQ isn't ACKed quickly it never will be on this adv.
        g.client->setConnectTimeout(5);
        ok = g.client->connect(g.foundDev, /*deleteAttibutes=*/true);
        if (ok) {
            log_i("connect ok on attempt %d (connectable hit #%d)", attempt, connectableHits);
            break;
        }
        log_w("connect attempt %d failed for %s, resuming scan", attempt, addr.c_str());
        teardownClient();
        delay(150);
        if (attempt >= 200) {
            log_e("giving up on %s after %d attempts", addr.c_str(), attempt);
            break;
        }
    }
    if (!ok) {
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

    // Restore pinned target. If set, auto-mode will only attempt to connect
    // to this address. Cleared via the `forget` command.
    {
        String pinned = g.prefs.getString(PREFS_KEY_PINNED, "");
        if (pinned.length()) {
            g.pinnedAddr = std::string(pinned.c_str());
            log_i("Pinned target restored from prefs: %s", g.pinnedAddr.c_str());
        }
    }

    // Restore auto-grab name pattern (set via `t <pattern>` command).
    {
        String pat = g.prefs.getString("grab_pat", "");
        if (pat.length()) {
            g.grabPattern = std::string(pat.c_str());
            log_i("Auto-grab pattern restored: '%s'", g.grabPattern.c_str());
        }
    }

    // Match Bluedroid's scan duplicate behaviour (per-device, not per-data).
    // RadiaCode-110 uses BT5 chained ext-adv; default per-data filtering
    // drops AUX packets and the controller never sees the full adv set.
    // Must be called BEFORE NimBLEDevice::init().
    NimBLEDevice::setScanFilterMode(CONFIG_BTDM_SCAN_DUPL_TYPE_DEVICE);
    NimBLEDevice::setScanDuplicateCacheSize(200);

    NimBLEDevice::init("htit-tracker");

    // Request larger ATT MTU. Default is 23 (legacy) which forces 20-byte
    // payloads. RadiaCode-110 closes the link if the peer doesn't negotiate
    // a larger MTU within the supervision window. 247 is the max NimBLE
    // supports.
    NimBLEDevice::setMTU(247);

    // Max TX power on ALL power categories. Default setPower() only changes
    // the "default" category; ADV/SCAN/CONN may still use lower power.
    // The Heltec V3 board shares its 2.4GHz path with the LoRa front-end
    // and benefits from explicit max power on the CONN category in
    // particular -- low conn-TX power is a known cause of CONNECT_REQ
    // not reaching the peer despite scan working fine.
    NimBLEDevice::setPower(ESP_PWR_LVL_P9, ESP_BLE_PWR_TYPE_DEFAULT);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9, ESP_BLE_PWR_TYPE_SCAN);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9, ESP_BLE_PWR_TYPE_CONN_HDL0);

    // Just-Works security advertisement. The RadiaCode-110 protocol
    // appears to require the central to attempt SMP at connect time
    // (without it, every SET_EXCHANGE write is ignored). The pairing
    // itself always fails -- we don't bond. Each failed SMP attempt
    // appears to compound on the 110 firmware (user-observed soft-brick
    // requiring power-cycle), so finishConnect() also caps the number
    // of attempts per boot via g.connAttempts.
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
    NimBLEDevice::setSecurityAuth(/*bonding=*/false, /*mitm=*/false, /*sc=*/true);

    // Silence chatty NimBLE info logs (per-packet adv updates) so the
    // serial console stays readable. Our own state/match/connect logs
    // still print at INFO level via log_i / Serial.printf.
    esp_log_level_set("NimBLEScan",   ESP_LOG_WARN);
    esp_log_level_set("NimBLEDevice", ESP_LOG_WARN);
    esp_log_level_set("NimBLEClient", ESP_LOG_WARN);
    esp_log_level_set("NimBLEAdvertisedDevice", ESP_LOG_WARN);

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
            // Transition out of Scanning so auto-mode loop below can resume
            // continuous scan / reconnect attempts. Without this the tracker
            // sits idle (state stuck on Scanning) until the next user command.
            setState(State::Disconnected);
        } else if (!scan->isScanning()) {
            // Restart scan -- previous burst finished but deadline not hit yet.
            // is_continue=true preserves the merged adv+scan-response table so
            // names resolved from scan responses are NOT lost on restart.
            // Window == Interval = 100% scan duty cycle, maximises chance of
            // catching slow advertisers and their scan responses.
            scan->setActiveScan(true);
            scan->setInterval(160);
            scan->setWindow(160);
            scan->setDuplicateFilter(false);
            scan->start(0, nullptr, /*is_continue=*/true);
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
                std::string name = d.getName();
                if (name.empty()) name = extractNameFromPayload(&d);
                const int rssi = d.getRSSI();
                const bool nameMatch = nameLooksLikeRadiaCode(name);
                const bool svcMatch  = d.isAdvertisingService(SVC_UUID) ||
                                       d.isAdvertisingService(NORDIC_UART_SVC);

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
                    nr.addrType    = d.getAddressType();
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
        uint8_t targetType = g.pendingConnectAddrType;
        g.pendingConnectAddr.clear();
        g.pendingConnectAddrType = 0;
        log_i("Picker connect -> %s (type=%u)", target.c_str(), (unsigned)targetType);
        connectToAddress(target, targetType);
        return;
    }

    // Request timeout. Don't disconnect on a single timeout -- the 110 is\n    // observed to take 5-9 s for the first VS_DATA_BUF response after\n    // Ready, and an idle slot or two is normal. Just clear the flag and\n    // let the next poll fire. The peer will close the link itself if it\n    // really has gone away.\n    if (g.awaitingResponse && (int32_t)(now - g.activeDeadlineMs) >= 0) {\n        log_w("Request 0x%04X timed out (cmd will be retried by next poll)", g.activeCmd);\n        g.awaitingResponse = false;\n        g.expectedLen = -1;\n        g.respBuffer.clear();\n    }

    // Drive scan/reconnect when not connected (auto-mode)
    if (g.state == State::Idle || g.state == State::Disconnected) {
        if (g.autoRetryHalted) {
            // Don't auto-retry. The previous connect attempt failed
            // post-link (likely an SMP timeout from a soft-bricked 110).
            // Wait for explicit user command to resume.
            return;
        }
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
    // window < interval so the BLE radio can switch advertising channels.
    // window==interval prevents channel hopping and silently misses
    // peripherals advertising on other channels (RadiaCode 110 was
    // observed only by the auto-scan with these gentler params).
    scan->setInterval(160);   // 100ms
    scan->setWindow(99);      // ~62% duty, allows ch hop
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
    return connectTo(address, 0);
}
bool RadiaCode::connectTo(const std::string& address, uint8_t addrType) {
    g.pendingConnectAddr = address;
    g.pendingConnectAddrType = addrType;
    g.manualScanActive = false;
    g.autoRetryHalted = false;
    // Pin this address so auto-mode stops chasing imposter peers and a
    // reboot continues trying the same target.
    g.pinnedAddr = address;
    g.prefs.putString(PREFS_KEY_PINNED, String(address.c_str()));
    log_i("Pinned target -> %s", address.c_str());
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
    g.prefs.remove(PREFS_KEY_PINNED);
    g.prefs.remove("grab_pat");
    g.pinnedAddr.clear();
    g.grabPattern.clear();
    g.autoRetryHalted = false;
    if (g.client && g.client->isConnected()) g.client->disconnect();
}

void RadiaCode::setNameGrabPattern(const std::string& pattern) {
    g.grabPattern = pattern;
    g.autoRetryHalted = false;
    if (pattern.empty()) {
        g.prefs.remove("grab_pat");
        log_i("Auto-grab pattern cleared");
    } else {
        g.prefs.putString("grab_pat", String(pattern.c_str()));
        log_i("Auto-grab pattern set: '%s' -- will pin+connect first connectable peer matching", pattern.c_str());
    }
    // Force a fresh scan so the watcher engages immediately.
    if (g.client && g.client->isConnected()) g.client->disconnect();
    setState(State::Disconnected);
}

void RadiaCode::disconnectKeepPin() {
    log_i("disconnectKeepPin(): dropping link, pin retained (%s)", g.pinnedAddr.c_str());
    if (g.client && g.client->isConnected()) g.client->disconnect();
    setState(State::Disconnected);
}

RadiaCode::State  RadiaCode::state()       { return g.state; }
const String&     RadiaCode::peerAddress() { return g.peerAddr; }
const String&     RadiaCode::peerName()    { return g.peerName; }
int               RadiaCode::rssi()        { return g.rssi; }
