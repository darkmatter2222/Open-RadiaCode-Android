param(
  [Parameter(Mandatory=$false)]
  [string]$BaseUrl = "http://99.122.58.29:443",

  [Parameter(Mandatory=$false)]
  [int]$Limit = 200,

  [Parameter(Mandatory=$false)]
  [string]$OutDir = "analyzer/out"
)

$ErrorActionPreference = "Stop"

function Ensure-Directory([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) {
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
  }
}

function Save-Json([object]$Obj, [string]$Path) {
  # Windows PowerShell 5.1 enforces a max ConvertTo-Json depth of 100.
  # Use the maximum allowed, and fall back to a shallower depth if needed.
  try {
    ($Obj | ConvertTo-Json -Depth 100) | Set-Content -Encoding UTF8 -Path $Path
  } catch {
    ($Obj | ConvertTo-Json -Depth 50) | Set-Content -Encoding UTF8 -Path $Path
  }
}

Write-Host "BaseUrl: $BaseUrl"
Write-Host "Limit:   $Limit"
Ensure-Directory $OutDir

$logsUrl = "$BaseUrl/logs?limit=$Limit"
Write-Host "Fetching: $logsUrl"
$list = Invoke-RestMethod -Method GET -Uri $logsUrl

if (-not $list -or -not $list.logs) {
  throw "Unexpected response: missing .logs from $logsUrl"
}

# Candidate definition: last POST to one of the inference endpoints.
$candidates = @($list.logs | Where-Object {
  $_.method -eq 'POST' -and (
    $_.path -like '/identify*' -or
    $_.path -like '/isotope/*identify*' -or
    $_.path -like '/api/isotope/*identify*'
  )
})

if ($candidates.Count -eq 0) {
  throw "No isotope inference logs found in last $Limit."
}

# Pick the most recent. Prefer a timestamp field if present, otherwise assume list already ordered.
$candidate = $candidates | Sort-Object -Property @('timestamp','time','createdAt','created_at') -Descending | Select-Object -First 1

if (-not $candidate.id) {
  throw "Candidate log entry has no .id. Keys: $($candidate.PSObject.Properties.Name -join ', ')"
}

$id = $candidate.id
Write-Host "Selected log id: $id"

$detailUrl = "$BaseUrl/logs/$id"
Write-Host "Fetching: $detailUrl"
$detail = Invoke-RestMethod -Method GET -Uri $detailUrl

# Save raw artifacts for offline review.
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$summaryPath = Join-Path $OutDir "last_inference_summary_$stamp.json"
$detailPath  = Join-Path $OutDir "last_inference_detail_$stamp.json"

Save-Json $candidate $summaryPath
Save-Json $detail $detailPath

Write-Host "Saved: $summaryPath"
Write-Host "Saved: $detailPath"

# Convenience: if the server includes request/response sub-objects, save them too.
if ($detail.request) {
  Save-Json $detail.request (Join-Path $OutDir "last_inference_request_$stamp.json")
}
if ($detail.response) {
  Save-Json $detail.response (Join-Path $OutDir "last_inference_response_$stamp.json")
}

# Print a short on-screen hint.
Write-Host "Done. Open the JSON files under $OutDir to inspect the last inference input/output."