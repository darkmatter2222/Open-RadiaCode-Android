"""Download all CSV session files from the Heltec tracker over USB serial,
verify them, and (optionally) wipe the on-device storage.

Tracker firmware commands used (see main.cpp / session_store.cpp):
    LS                  -> [LS] count + per-session lines + [LS-END]
    DUMP <id>           -> [DUMP-BEGIN ...] <csv body> [DUMP-END id=<id>]
    DUMPALL             -> sequence of dumps wrapped by [DUMP-ALL-BEGIN]/[DUMP-DONE]
    WIPE <expected>     -> [WIPE-DONE] removed=N        (count must match LS)
    STATFS              -> [STATFS] used=... total=... pct=... sessions=N

Typical use:
    python download_sessions.py                # download AND wipe device storage (default)
    python download_sessions.py --no-wipe      # download but leave sessions on device
    python download_sessions.py --confirm      # prompt before wiping
    python download_sessions.py --port COM7    # override port
    python download_sessions.py --list         # just print LS results

Notes:
 - The tracker's USB CDC port is reused by drive.py at 115200 baud.
 - We deliberately keep DTR/RTS low so opening the port does not reset the
   ESP32-S3, which would interrupt logging.
 - Wipe is gated server-side: the tracker requires the count we pass to
   match its current session count exactly. If anything changed between
   LS and WIPE we abort.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import serial


DEFAULT_PORT = "COM3"
DEFAULT_BAUD = 115200

LS_LINE = re.compile(r"^\s+(?P<id>\S+)\s+bytes=(?P<bytes>\d+)\s+samples=(?P<samples>\d+)")
DUMP_BEGIN = re.compile(r"\[DUMP-BEGIN\]\s+id=(?P<id>\S+)\s+bytes=(?P<bytes>\d+)\s+samples=(?P<samples>\d+)")
DUMP_END = re.compile(r"\[DUMP-END\]\s+id=(?P<id>\S+)")
DUMP_ERR = re.compile(r"\[DUMP-ERR\]\s+id=(?P<id>\S+)\s+reason=(?P<reason>\S+)")


@dataclass
class SessionInfo:
    sid: str
    sample_bytes: int
    samples: int


# ----- low-level serial helpers --------------------------------------------

def open_port(port: str, baud: int) -> serial.Serial:
    s = serial.Serial()
    s.port = port
    s.baudrate = baud
    s.timeout = 0.2
    # Don't reset the ESP32-S3 on open.
    s.dtr = False
    s.rts = False
    s.open()
    return s


def drain(s: serial.Serial, secs: float = 0.4) -> None:
    """Discard any pending input."""
    end = time.time() + secs
    while time.time() < end:
        if s.in_waiting:
            s.read(s.in_waiting)
        else:
            time.sleep(0.02)


def send_line(s: serial.Serial, line: str) -> None:
    s.write((line.strip() + "\n").encode("utf-8"))
    s.flush()


def read_lines_until(
    s: serial.Serial,
    sentinel_re: "re.Pattern[str]",
    timeout: float,
    echo: bool = False,
) -> tuple[list[str], "re.Match[str] | None"]:
    """Read lines until a regex matches, the inactivity timeout fires,
    or the absolute timeout is reached. Returns (lines, sentinel_match)."""
    deadline = time.time() + timeout
    buf = b""
    out: list[str] = []
    sentinel: "re.Match[str] | None" = None
    while time.time() < deadline:
        chunk = s.read(1024)
        if not chunk:
            if buf:
                # Flush partial line on timeout slice.
                pass
            continue
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            text = line.decode("utf-8", errors="replace").rstrip("\r")
            if echo:
                print(text)
            out.append(text)
            m = sentinel_re.search(text)
            if m:
                sentinel = m
                return out, sentinel
    return out, sentinel


# ----- LS, DUMP, WIPE wrappers ---------------------------------------------

def ls(s: serial.Serial) -> list[SessionInfo]:
    drain(s, 0.3)
    send_line(s, "LS")
    lines, m = read_lines_until(s, re.compile(r"\[LS-END\]\s+count=(\d+)"), timeout=8)
    if not m:
        raise RuntimeError("LS: no [LS-END] sentinel within timeout")
    sessions: list[SessionInfo] = []
    for ln in lines:
        ml = LS_LINE.match(ln)
        if ml:
            sessions.append(SessionInfo(
                sid=ml.group("id"),
                sample_bytes=int(ml.group("bytes")),
                samples=int(ml.group("samples")),
            ))
    return sessions


def dump_session(
    s: serial.Serial,
    sid: str,
    out_dir: Path,
    timeout: float = 60.0,
) -> tuple[Path, int]:
    """Stream a single session, write it to <out_dir>/<sid>.csv, return
    (path, samples_seen)."""
    drain(s, 0.2)
    send_line(s, f"DUMP {sid}")

    # Wait for DUMP-BEGIN (or DUMP-ERR).
    deadline = time.time() + timeout
    buf = b""
    begin_match: "re.Match[str] | None" = None
    err_match: "re.Match[str] | None" = None
    pre_lines: list[str] = []
    while time.time() < deadline and begin_match is None:
        chunk = s.read(1024)
        if not chunk:
            continue
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            text = line.decode("utf-8", errors="replace").rstrip("\r")
            err_match = DUMP_ERR.search(text)
            if err_match:
                raise RuntimeError(f"DUMP error from device: {text}")
            begin_match = DUMP_BEGIN.search(text)
            if begin_match:
                break
            pre_lines.append(text)
    if begin_match is None:
        raise RuntimeError(f"DUMP {sid}: no [DUMP-BEGIN] within {timeout:.0f}s")

    expected_bytes = int(begin_match.group("bytes"))
    expected_samples = int(begin_match.group("samples"))

    # Now stream raw bytes until we see [DUMP-END] on its own line.
    out_path = out_dir / f"{sid}.csv"
    end_re = re.compile(rf"^\[DUMP-END\]\s+id={re.escape(sid)}$")

    body = bytearray()
    seen_end = False
    deadline = time.time() + max(timeout, expected_bytes / 1024.0 + 30)
    while time.time() < deadline and not seen_end:
        chunk = s.read(2048)
        if not chunk:
            continue
        buf += chunk
        # Walk newlines so we can spot the sentinel without consuming
        # following bytes (there shouldn't be any after [DUMP-END], but
        # be safe).
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            text = line.decode("utf-8", errors="replace").rstrip("\r")
            if end_re.match(text):
                seen_end = True
                break
            body.extend(text.encode("utf-8"))
            body.extend(b"\n")
    if not seen_end:
        raise RuntimeError(f"DUMP {sid}: no [DUMP-END] within window")

    # Trim the trailing blank-line padding that dumpSession() emits to
    # guarantee the marker appears alone.
    while body.endswith(b"\n\n"):
        body = body[:-1]

    out_path.write_bytes(bytes(body))

    # Count samples: total lines minus header.
    text = body.decode("utf-8", errors="replace")
    lines = [ln for ln in text.splitlines() if ln.strip()]
    samples = max(0, len(lines) - 1)

    print(f"  saved {out_path.name}: {len(body):,} bytes, {samples} samples "
          f"(device reported {expected_bytes:,} bytes / {expected_samples} samples)")
    if samples != expected_samples:
        print(f"  WARNING: sample-count mismatch ({samples} != {expected_samples})")
    return out_path, samples


def wipe(s: serial.Serial, expected_count: int, timeout: float = 15.0) -> int:
    drain(s, 0.3)
    send_line(s, f"WIPE {expected_count}")
    sentinel = re.compile(r"\[(WIPE-DONE|WIPE-ABORT)\]")
    lines, m = read_lines_until(s, sentinel, timeout=timeout, echo=True)
    if not m:
        raise RuntimeError("WIPE: no response within timeout")
    if "WIPE-ABORT" in m.group(0):
        raise RuntimeError("WIPE aborted by device (count mismatch)")
    md = re.search(r"removed=(\d+)", lines[-1])
    return int(md.group(1)) if md else 0


# ----- main -----------------------------------------------------------------

def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Download tracker session CSVs")
    p.add_argument("--port", default=DEFAULT_PORT)
    p.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    p.add_argument("--out", default=None,
                   help="output directory (default: heltec_tracker/data/sessions_dump/<ts>)")
    p.add_argument("--list", action="store_true", help="just run LS and exit")
    # Wipe-on-download is the default so the device is always ready for more data.
    p.add_argument("--no-wipe", dest="wipe", action="store_false",
                   help="keep sessions on the device after download (default: wipe)")
    p.add_argument("--wipe", dest="wipe", action="store_true",
                   help="WIPE device storage after download (default behavior)")
    p.set_defaults(wipe=True)
    p.add_argument("--yes", action="store_true",
                   help="skip the interactive 'really wipe?' prompt")
    p.add_argument("--confirm", action="store_true",
                   help="force the interactive 'really wipe?' prompt")
    args = p.parse_args(argv)

    print(f"[drive] opening {args.port} @ {args.baud}")
    try:
        s = open_port(args.port, args.baud)
    except serial.SerialException as e:
        print(f"ERROR: cannot open serial port {args.port}: {e}")
        return 2

    try:
        sessions = ls(s)
    except Exception as e:
        print(f"ERROR: LS failed: {e}")
        s.close()
        return 3

    print(f"[ls] {len(sessions)} session(s) on device:")
    for si in sessions:
        print(f"   {si.sid}  bytes={si.sample_bytes:,}  samples={si.samples}")

    if args.list:
        s.close()
        return 0

    if not sessions:
        print("Nothing to download.")
        if args.wipe:
            # Still allow caller to clear the active flag if there's nothing.
            wipe(s, 0)
        s.close()
        return 0

    if args.out:
        out_dir = Path(args.out)
    else:
        ts = time.strftime("%Y%m%d_%H%M%S")
        out_dir = (Path(__file__).resolve().parent.parent / "data" / "sessions_dump" / ts)
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"[out] writing to {out_dir}")

    failed: list[str] = []
    total_samples = 0
    for si in sessions:
        try:
            _, samples = dump_session(s, si.sid, out_dir)
            total_samples += samples
        except Exception as e:
            print(f"  FAILED to dump {si.sid}: {e}")
            failed.append(si.sid)

    print(f"[done] {len(sessions) - len(failed)}/{len(sessions)} files saved, "
          f"{total_samples:,} total samples")

    if failed:
        print(f"WARNING: {len(failed)} session(s) failed; not wiping.")
        s.close()
        return 4

    if args.wipe:
        # Default is wipe-without-prompting (download-then-clean).
        # Use --confirm to force the prompt; --yes is kept for backwards compat.
        if args.confirm and not args.yes:
            ans = input(f"Really wipe {len(sessions)} session(s) from device? [y/N] ").strip().lower()
            if ans not in ("y", "yes"):
                print("Wipe cancelled.")
                s.close()
                return 0
        try:
            removed = wipe(s, len(sessions))
            print(f"[wipe] {removed} file(s) removed from device")
        except Exception as e:
            print(f"ERROR: wipe failed: {e}")
            s.close()
            return 5

    s.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
