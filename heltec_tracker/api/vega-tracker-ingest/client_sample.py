"""Smoke-test the Vega Tracker Ingest API.

Usage:
    python client_sample.py http://192.168.86.48:8030 path/to/session.csv

If no CSV is given, generates a small synthetic one and uploads that.
"""
from __future__ import annotations

import os
import sys
import time
import uuid
from pathlib import Path

import requests


SAMPLE_CSV = (
    "timestampMs,uSvPerHour,cps,latitude,longitude,deviceId\n"
    "{t1},0.054,3.6,34.3047238,-84.0843347,524306602024\n"
    "{t2},0.060,4.1,34.3047250,-84.0843340,524306602024\n"
    "{t3},0.071,4.9,34.3047280,-84.0843310,524306602024\n"
)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: client_sample.py <base_url> [csv_path]")
        return 2
    base = argv[1].rstrip("/")
    csv_path = Path(argv[2]) if len(argv) >= 3 else None

    print(f"GET  {base}/health")
    r = requests.get(f"{base}/health", timeout=10); r.raise_for_status()
    print(" ", r.json())

    if csv_path is not None:
        body = csv_path.read_bytes()
        sid  = csv_path.stem
    else:
        now = int(time.time() * 1000)
        body = SAMPLE_CSV.format(t1=now, t2=now + 1000, t3=now + 2000).encode("utf-8")
        sid  = f"smoketest_{uuid.uuid4().hex[:8]}"

    print(f"POST {base}/ingest/csv  session={sid}  bytes={len(body)}")
    r = requests.post(
        f"{base}/ingest/csv",
        data=body,
        headers={
            "Content-Type": "text/csv",
            "X-Session-Id": sid,
            "X-Tracker-Id": "client-sample",
            "X-Firmware":   "0.0.0",
        },
        timeout=30,
    )
    r.raise_for_status()
    print(" ", r.json())

    print(f"GET  {base}/sessions")
    r = requests.get(f"{base}/sessions", timeout=10); r.raise_for_status()
    for s in r.json()[:5]:
        print(" ", s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
