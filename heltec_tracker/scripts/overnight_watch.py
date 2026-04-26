"""Long-running connect watcher.

Sends `c <addr> 1` to the tracker, then logs every line forever (or until
the device reports a successful connect + readings). Designed to run
unattended overnight.

Outputs to both stdout and overnight.log.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import os
import sys
import time
from pathlib import Path

import serial
from serial.tools import list_ports

DEFAULT_BAUD = 115200


def find_port() -> str:
    # Heltec uses the ESP32-S3 native USB CDC. Pick the first non-Bluetooth COM.
    candidates = []
    for p in list_ports.comports():
        desc = (p.description or "").lower()
        if "bluetooth" in desc:
            continue
        candidates.append(p.device)
    if not candidates:
        raise RuntimeError("no serial ports found")
    return candidates[0]


def open_port(port: str) -> serial.Serial:
    """Open the port with DTR/RTS unasserted from the start so we don't
    pulse the ESP32-S3 reset on connect."""
    s = serial.Serial(timeout=0.5)
    s.dtr = False
    s.rts = False
    s.port = port
    s.baudrate = DEFAULT_BAUD
    s.open()
    return s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="e1:f5:39:2b:37:f7")
    ap.add_argument("--port", default=os.environ.get("TRACKER_PORT") or find_port())
    ap.add_argument("--log", default="overnight.log")
    ap.add_argument("--max-hours", type=float, default=12.0)
    ap.add_argument("--no-cmd", action="store_true",
                    help="do not send the connect command (just observe)")
    args = ap.parse_args()

    log_path = Path(args.log).resolve()
    log = log_path.open("a", encoding="utf-8")

    def write(line: str) -> None:
        ts = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        msg = f"{ts} | {line}"
        print(msg, flush=True)
        log.write(msg + "\n")
        log.flush()

    write(f"== overnight_watch starting on {args.port}, target {args.addr}, log {log_path} ==")

    s = open_port(args.port)
    time.sleep(0.4)
    s.reset_input_buffer()

    if not args.no_cmd:
        cmd = f"c {args.addr} 1\n".encode()
        s.write(cmd)
        s.flush()
        write(f"sent: c {args.addr} 1")

    deadline = time.time() + args.max_hours * 3600.0
    connected = False
    last_state_msg = 0.0
    try:
        while time.time() < deadline:
            try:
                raw = s.readline()
            except serial.SerialException as e:
                write(f"serial err {e}; sleeping 2s and retrying")
                time.sleep(2)
                try:
                    s.close()
                except Exception:
                    pass
                time.sleep(0.5)
                s = open_port(args.port)
                continue
            if not raw:
                # heartbeat every 5 minutes
                if time.time() - last_state_msg > 300:
                    write("(no serial output for 5 min)")
                    last_state_msg = time.time()
                continue
            try:
                line = raw.decode("utf-8", errors="replace").rstrip()
            except Exception:
                continue
            if not line:
                continue
            write(line)
            low = line.lower()
            if "connect ok on attempt" in low or "init done" in low or "device_time" in low:
                write("== CONNECT SUCCESS DETECTED ==")
                connected = True
            if connected and ("dose" in low or "rate=" in low or "cps=" in low or "reading" in low):
                write("== READING FLOWING ==")
                # Keep watching for a while to confirm
                time.sleep(0.05)
    except KeyboardInterrupt:
        write("interrupted")
    finally:
        log.close()
        try:
            s.close()
        except Exception:
            pass
    return 0 if connected else 1


if __name__ == "__main__":
    sys.exit(main())
