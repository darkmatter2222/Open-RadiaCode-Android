"""Drive the Heltec tracker over its USB serial command interface.

Usage examples:
    python drive.py listen 8                  # just listen for 8 seconds
    python drive.py cmd s --listen 16         # start scan, capture 16s of output
    python drive.py cmd l                     # ask device to list scan results
    python drive.py cmd "c 3" --listen 12     # connect to scan idx 3
    python drive.py auto-connect 18           # scan 18s, then try every likely
                                              # match in RSSI order until one
                                              # actually connects (state -> 4)
"""
from __future__ import annotations
import argparse, re, sys, threading, time
import serial

PORT = "COM4"
BAUD = 115200

def open_port() -> serial.Serial:
    # Keep DTR/RTS unasserted so reopening the port doesn't reset the
    # ESP32-S3 (native USB CDC). Without this, every fresh invocation
    # would interrupt any in-flight BLE work.
    s = serial.Serial()
    s.port = PORT
    s.baudrate = BAUD
    s.timeout = 0.1
    s.dtr = False
    s.rts = False
    s.open()
    return s

def reader_loop(s: serial.Serial, sink: list[str], stop: threading.Event):
    while not stop.is_set():
        try:
            chunk = s.read(512)
        except Exception as e:
            print(f"[drive] read err: {e}")
            return
        if not chunk:
            continue
        text = chunk.decode("utf-8", errors="replace")
        for line in text.splitlines():
            if line.strip():
                ts = time.strftime("%H:%M:%S")
                print(f"{ts} | {line}")
                sink.append(line)

def send_cmd(s: serial.Serial, cmd: str):
    print(f"--> {cmd}")
    s.write((cmd.strip() + "\n").encode("utf-8"))
    s.flush()

def cmd_listen(seconds: float):
    s = open_port()
    sink: list[str] = []
    stop = threading.Event()
    t = threading.Thread(target=reader_loop, args=(s, sink, stop), daemon=True)
    t.start()
    time.sleep(seconds)
    stop.set()
    t.join(timeout=1)
    s.close()
    return sink

def cmd_one(cmd: str, listen_secs: float):
    s = open_port()
    sink: list[str] = []
    stop = threading.Event()
    t = threading.Thread(target=reader_loop, args=(s, sink, stop), daemon=True)
    t.start()
    time.sleep(0.3)
    send_cmd(s, cmd)
    time.sleep(listen_secs)
    stop.set()
    t.join(timeout=1)
    s.close()
    return sink

# Parse "  [3] e1:f5:39:2b:37:f7 type=1 rssi=-56 likely=1 name='RadiaCode 11'"
LIST_LINE = re.compile(
    r"\[(?P<idx>\d+)\]\s+(?P<addr>[0-9a-f:]+)\s+type=(?P<type>\d+)\s+"
    r"rssi=(?P<rssi>-?\d+)\s+likely=(?P<likely>\d+)\s+name='(?P<name>[^']*)'"
)

def parse_list(lines: list[str]):
    rows = []
    for ln in lines:
        m = LIST_LINE.search(ln)
        if m:
            rows.append({
                "idx": int(m["idx"]),
                "addr": m["addr"],
                "type": int(m["type"]),
                "rssi": int(m["rssi"]),
                "likely": int(m["likely"]),
                "name": m["name"],
            })
    return rows

def auto_connect(scan_secs: int):
    """Scan, then iterate over likely matches strongest first, trying each.

    A successful connection is marked by `[RC] state=4 ...` (Ready=4) appearing.
    """
    s = open_port()
    sink: list[str] = []
    stop = threading.Event()
    t = threading.Thread(target=reader_loop, args=(s, sink, stop), daemon=True)
    t.start()
    try:
        time.sleep(0.3)
        send_cmd(s, "s")
        time.sleep(scan_secs)
        # Drain old, request fresh list
        sink.clear()
        send_cmd(s, "l")
        time.sleep(1.5)
        rows = parse_list(sink)
        if not rows:
            print("[drive] no scan results parsed")
            return
        rows.sort(key=lambda r: (-r["likely"], -r["rssi"]))
        print(f"[drive] {len(rows)} candidates; trying in priority order:")
        for r in rows:
            print(f"   idx={r['idx']} addr={r['addr']} type={r['type']} "
                  f"rssi={r['rssi']} likely={r['likely']} name='{r['name']}'")
        for r in rows:
            sink.clear()
            print(f"\n[drive] === attempting idx={r['idx']} ({r['addr']}) ===")
            send_cmd(s, f"c {r['idx']}")
            # Wait up to 15s for state=4 (Ready)
            deadline = time.time() + 15
            connected = False
            while time.time() < deadline:
                time.sleep(0.4)
                if any("state=4" in ln for ln in sink):
                    connected = True
                    break
                if any("connect(addr) failed" in ln for ln in sink):
                    print("[drive] connect failed at NimBLE layer")
                    break
            if connected:
                print(f"\n[drive] *** CONNECTED to {r['addr']} (idx={r['idx']}) ***")
                # Watch a bit more for service discovery / readings
                time.sleep(8)
                return
            # Disconnect / cancel and try next
            send_cmd(s, "x")
            time.sleep(2)
        print("[drive] exhausted candidates without success")
    finally:
        stop.set()
        t.join(timeout=1)
        s.close()

def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="action", required=True)
    p_listen = sub.add_parser("listen")
    p_listen.add_argument("seconds", type=float)
    p_cmd = sub.add_parser("cmd")
    p_cmd.add_argument("text")
    p_cmd.add_argument("--listen", type=float, default=4.0)
    p_auto = sub.add_parser("auto-connect")
    p_auto.add_argument("scan_secs", type=int, nargs="?", default=15)
    args = p.parse_args()
    if args.action == "listen":
        cmd_listen(args.seconds)
    elif args.action == "cmd":
        cmd_one(args.text, args.listen)
    elif args.action == "auto-connect":
        auto_connect(args.scan_secs)

if __name__ == "__main__":
    main()
