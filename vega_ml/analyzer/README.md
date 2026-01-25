# Analyzer

This folder contains small utilities for inspecting the **last inference request/response** processed by your middleware API.

## Fetch last inference (PowerShell)

Runs against the server’s log endpoints:
- `GET /logs?limit=...`
- `GET /logs/{id}`

### Default (uses `http://99.122.58.29:443`)

```powershell
powershell -ExecutionPolicy Bypass -File analyzer/fetch_last_inference.ps1
```

### Override the base URL

```powershell
powershell -ExecutionPolicy Bypass -File analyzer/fetch_last_inference.ps1 -BaseUrl "http://99.122.58.29:443"
```

### Output

Artifacts are written to:
- `analyzer/out/last_inference_summary_*.json`
- `analyzer/out/last_inference_detail_*.json`

If the server includes `request` and/or `response` fields in the detail payload, those are also saved as:
- `analyzer/out/last_inference_request_*.json`
- `analyzer/out/last_inference_response_*.json`

## Notes

- This script assumes the log list items have at least: `id`, `method`, `path`.
- It selects the most recent matching `POST` to an `identify` endpoint.
- If your server uses HTTPS with a real cert, switch the URL to `https://...`.
