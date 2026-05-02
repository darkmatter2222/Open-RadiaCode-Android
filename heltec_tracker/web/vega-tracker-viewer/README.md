# Vega Tracker Viewer

Static React + Leaflet web UI for the Vega Tracker ingest API. Lets you
browse uploaded sessions on a map, scrub through time, color the trace
by dose rate, and toggle multiple sessions simultaneously.

## Local dev

```powershell
cd web/vega-tracker-viewer
npm install
npm run dev
# open http://localhost:5173
```

In dev the app talks to `http://192.168.86.48:8030` by default. Override
with `VITE_API_URL` in your environment.

## Deploy

```powershell
cd web/vega-tracker-viewer
.\deploy.ps1
```

Reads `.env` (gitignored), copies the source over SSH, builds a
multi-stage Docker image (node -> nginx) on the server, runs it. Visit
`http://192.168.86.48:8031/`.

## Runtime config

The image is environment-driven: at container start a small entrypoint
script substitutes `API_BASE` into `/usr/share/nginx/html/config.js`.
That file is loaded by `index.html` before the JS bundle, so a single
image can be repointed at a different ingest API without rebuilding.

## Features

- Multi-select session checklist (newest first)
- Auto-fit map bounds on selection
- Time cursor + trailing window (slider)
- Play / pause / rewind animation
- Per-segment color gradient by dose rate (configurable scale)
- Toggle sample-point markers, line-by-dose, nSv/h display
- Live aggregate stats (avg/max/min) over the visible window
