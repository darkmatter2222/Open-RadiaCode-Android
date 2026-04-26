"""Render an interactive HTML map from one or more tracker session CSVs.

Two layers are produced:
  - dose rate (uSv/h)   color-graded along the GPS track
  - count rate (cps)    same, on its own toggleable layer

Usage:
  python plot_session_map.py path/to/session.csv [more.csv ...] -o map.html
  python plot_session_map.py --latest                # auto-pick newest dump dir
"""
from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path
from typing import List, Tuple

import folium
from folium.plugins import MarkerCluster, HeatMap
from branca.colormap import LinearColormap


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_DUMP_ROOT = SCRIPT_DIR.parent / "data" / "sessions_dump"


def load_csv(path: Path) -> List[dict]:
    rows: List[dict] = []
    with path.open("r", encoding="utf-8", newline="") as f:
        rdr = csv.DictReader(f)
        for r in rdr:
            try:
                lat = float(r["latitude"]); lng = float(r["longitude"])
                if lat == 0.0 and lng == 0.0:
                    continue
                rows.append({
                    "t":   int(r["timestampMs"]),
                    "uSv": float(r["uSvPerHour"]),
                    "cps": float(r["cps"]),
                    "lat": lat,
                    "lng": lng,
                    "dev": r.get("deviceId", ""),
                })
            except (KeyError, ValueError):
                continue
    return rows


def latest_dump_dir() -> Path:
    if not DEFAULT_DUMP_ROOT.exists():
        sys.exit(f"no dump root at {DEFAULT_DUMP_ROOT}")
    candidates = sorted([p for p in DEFAULT_DUMP_ROOT.iterdir() if p.is_dir()])
    if not candidates:
        sys.exit(f"no dump subdirectories in {DEFAULT_DUMP_ROOT}")
    return candidates[-1]


def build_polyline_segments(rows: List[dict], values: List[float],
                            cmap: LinearColormap, name: str) -> folium.FeatureGroup:
    """One small PolyLine per consecutive pair, colored by `values`."""
    fg = folium.FeatureGroup(name=name, show=(name.startswith("Dose")))
    for i in range(1, len(rows)):
        a, b = rows[i - 1], rows[i]
        if abs(a["t"] - b["t"]) > 60_000:  # gap > 60s, don't draw
            continue
        v = 0.5 * (values[i - 1] + values[i])
        color = cmap(v)
        folium.PolyLine(
            locations=[(a["lat"], a["lng"]), (b["lat"], b["lng"])],
            color=color, weight=5, opacity=0.85,
        ).add_to(fg)
    return fg


def build_sample_markers(rows: List[dict], decim: int = 30) -> folium.FeatureGroup:
    """Sparse circle markers with a popup of the actual values."""
    fg = folium.FeatureGroup(name=f"Sample points (every {decim})", show=False)
    cluster = MarkerCluster().add_to(fg)
    for i in range(0, len(rows), decim):
        r = rows[i]
        popup = (f"<b>t</b>={r['t']}<br>"
                 f"<b>dose</b>={r['uSv']*1000:.2f} nSv/h ({r['uSv']:.4f} uSv/h)<br>"
                 f"<b>count</b>={r['cps']:.2f} cps ({r['cps']*60:.0f} cpm)<br>"
                 f"<b>pos</b>={r['lat']:.6f}, {r['lng']:.6f}")
        folium.CircleMarker(
            location=(r["lat"], r["lng"]),
            radius=4, weight=1, fill=True, fill_opacity=0.9,
            color="#222", fill_color="#00E676",
            popup=folium.Popup(popup, max_width=300),
        ).add_to(cluster)
    return fg


def percentile(vals: List[float], pct: float) -> float:
    if not vals: return 0.0
    s = sorted(vals)
    k = max(0, min(len(s) - 1, int(round((pct / 100.0) * (len(s) - 1)))))
    return s[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csvs", nargs="*", type=Path,
                    help="session CSV file(s); omit with --latest")
    ap.add_argument("--latest", action="store_true",
                    help="use newest sessions_dump/<ts>/ directory")
    ap.add_argument("-o", "--out", type=Path, default=None,
                    help="output HTML path (default: alongside CSV)")
    args = ap.parse_args()

    csvs: List[Path] = list(args.csvs)
    if args.latest or not csvs:
        d = latest_dump_dir()
        csvs = sorted(d.glob("*.csv"))
        if not csvs:
            sys.exit(f"no CSVs in {d}")
        print(f"[map] using latest dump dir: {d}")

    all_rows: List[dict] = []
    for p in csvs:
        rows = load_csv(p)
        print(f"[map] {p.name}: {len(rows)} rows w/ GPS")
        all_rows.extend(rows)

    if not all_rows:
        sys.exit("no GPS-tagged samples to plot")

    all_rows.sort(key=lambda r: r["t"])

    lats = [r["lat"] for r in all_rows]
    lngs = [r["lng"] for r in all_rows]
    center = (sum(lats) / len(lats), sum(lngs) / len(lngs))

    # Build maps
    fmap = folium.Map(location=center, zoom_start=17, tiles=None,
                      control_scale=True)
    folium.TileLayer("OpenStreetMap", name="OpenStreetMap").add_to(fmap)
    folium.TileLayer("CartoDB dark_matter", name="Dark").add_to(fmap)
    folium.TileLayer(
        tiles="https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        name="Satellite",
        attr="Tiles &copy; Esri",
    ).add_to(fmap)

    dose_vals  = [r["uSv"] for r in all_rows]
    count_vals = [r["cps"] for r in all_rows]

    # Robust scaling (1st..99th percentile so a single hot sample doesn't crush the gradient)
    dose_lo,  dose_hi  = percentile(dose_vals, 1),  max(percentile(dose_vals, 99),  percentile(dose_vals, 1)  + 1e-9)
    count_lo, count_hi = percentile(count_vals, 1), max(percentile(count_vals, 99), percentile(count_vals, 1) + 1e-9)

    palette = ["#0033cc", "#00E676", "#FFD740", "#FF1744"]
    dose_cmap = LinearColormap(palette, vmin=dose_lo,  vmax=dose_hi,
                               caption=f"Dose rate (uSv/h)  [{dose_lo:.4f} .. {dose_hi:.4f}]")
    count_cmap = LinearColormap(palette, vmin=count_lo, vmax=count_hi,
                                caption=f"Count rate (cps)  [{count_lo:.2f} .. {count_hi:.2f}]")

    build_polyline_segments(all_rows, dose_vals,  dose_cmap,  "Dose track (uSv/h)").add_to(fmap)
    build_polyline_segments(all_rows, count_vals, count_cmap, "Count track (cps)").add_to(fmap)

    # Heatmaps weighted by normalized values
    def norm(v, lo, hi): return max(0.0, min(1.0, (v - lo) / (hi - lo)))
    HeatMap(
        [[r["lat"], r["lng"], norm(r["uSv"], dose_lo, dose_hi)] for r in all_rows],
        name="Dose heatmap", radius=15, blur=20, min_opacity=0.3, show=False,
    ).add_to(fmap)
    HeatMap(
        [[r["lat"], r["lng"], norm(r["cps"], count_lo, count_hi)] for r in all_rows],
        name="Count heatmap", radius=15, blur=20, min_opacity=0.3, show=False,
    ).add_to(fmap)

    build_sample_markers(all_rows).add_to(fmap)

    # Start/end markers
    s, e = all_rows[0], all_rows[-1]
    folium.Marker((s["lat"], s["lng"]), tooltip="START",
                  icon=folium.Icon(color="green", icon="play")).add_to(fmap)
    folium.Marker((e["lat"], e["lng"]), tooltip="END",
                  icon=folium.Icon(color="red", icon="stop")).add_to(fmap)

    # Hottest dose point
    hot_i = max(range(len(all_rows)), key=lambda i: all_rows[i]["uSv"])
    h = all_rows[hot_i]
    folium.Marker(
        (h["lat"], h["lng"]),
        tooltip=f"PEAK DOSE: {h['uSv']*1000:.2f} nSv/h",
        icon=folium.Icon(color="orange", icon="exclamation-sign"),
    ).add_to(fmap)

    dose_cmap.add_to(fmap)
    count_cmap.add_to(fmap)
    folium.LayerControl(collapsed=False).add_to(fmap)

    # Stats banner
    duration_s = (all_rows[-1]["t"] - all_rows[0]["t"]) / 1000.0
    mean_dose  = sum(dose_vals) / len(dose_vals)
    mean_cps   = sum(count_vals) / len(count_vals)
    stats_html = f"""
    <div style="position: fixed; top: 10px; right: 10px; z-index: 9999;
                background: rgba(13,13,13,0.85); color: #eee;
                font-family: monospace; padding: 10px 14px; border-radius: 6px;
                border: 1px solid #00E676;">
      <div style="color:#00E676;font-weight:bold;margin-bottom:4px;">RadiaCode Track</div>
      samples: {len(all_rows):,}<br>
      duration: {duration_s/60:.1f} min<br>
      mean dose: {mean_dose*1000:.2f} nSv/h<br>
      mean count: {mean_cps:.2f} cps ({mean_cps*60:.0f} cpm)<br>
      peak dose: {max(dose_vals)*1000:.2f} nSv/h<br>
      peak count: {max(count_vals):.2f} cps
    </div>
    """
    fmap.get_root().html.add_child(folium.Element(stats_html))

    out = args.out
    if out is None:
        if len(csvs) == 1:
            out = csvs[0].with_suffix(".html")
        else:
            out = csvs[0].parent / "map.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    fmap.save(str(out))
    print(f"[map] wrote {out}  ({len(all_rows):,} samples, "
          f"dose {dose_lo*1000:.2f}-{dose_hi*1000:.2f} nSv/h, "
          f"cps {count_lo:.2f}-{count_hi:.2f})")


if __name__ == "__main__":
    main()
