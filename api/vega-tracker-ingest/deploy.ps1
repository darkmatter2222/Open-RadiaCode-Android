#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Deploy Vega Tracker Ingest API to remote server.
.DESCRIPTION
    Copies the FastAPI service to the remote host over SSH (cert auth),
    then builds & runs the Docker container. Mirrors the deploy.ps1 in
    the other middleware services.

    Reads .env (gitignored) for SSH_*, REMOTE_PATH, API_PORT, MONGO_URI, etc.
#>
param(
    [switch]$SkipCopy,
    [switch]$TestOnly
)

$ErrorActionPreference = "Stop"
try { $global:PSNativeCommandUseErrorActionPreference = $false } catch { }

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "ERROR: .env not found. Copy .env.example to .env and fill in." -ForegroundColor Red
    exit 1
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        Set-Variable -Name $matches[1].Trim() -Value $matches[2].Trim() -Scope Script
    }
}

foreach ($var in @("SSH_USER", "SSH_HOST", "REMOTE_PATH", "API_PORT")) {
    if (-not (Get-Variable -Name $var -ValueOnly -ErrorAction SilentlyContinue)) {
        Write-Host "ERROR: Missing required variable in .env: $var" -ForegroundColor Red
        exit 1
    }
}
$SSH_TARGET = "$SSH_USER@$SSH_HOST"
$PROJECT_DIR = $PSScriptRoot

Write-Host "Server      : $SSH_TARGET"            -ForegroundColor Green
Write-Host "Remote path : $REMOTE_PATH"           -ForegroundColor Green
Write-Host "API port    : $API_PORT"              -ForegroundColor Green
Write-Host "Mongo URI   : $MONGO_URI"             -ForegroundColor Green
Write-Host ""

function Invoke-Remote {
    param([string]$Cmd, [switch]$Silent, [switch]$Stream)
    $sshArgs = @($SSH_TARGET, $Cmd)
    if ($SSH_KEY_PATH -and $SSH_KEY_PATH -ne "~/.ssh/id_rsa") {
        $sshArgs = @("-i", $SSH_KEY_PATH) + $sshArgs
    }
    if (-not $Silent) { Write-Host "  > $Cmd" -ForegroundColor DarkGray }
    if ($Stream) {
        $prevEap = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        try   { & ssh @sshArgs 2>&1 | ForEach-Object { Write-Host $_ } }
        finally { $ErrorActionPreference = $prevEap }
    } else {
        return (ssh @sshArgs 2>&1)
    }
}

function Test-Health {
    param([int]$TimeoutSeconds = 60)
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSeconds) {
        $r = Invoke-Remote "curl -s -f http://localhost:$API_PORT/health" -Silent
        if ($r -match '"status":\s*"healthy"') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

if ($TestOnly) {
    Write-Host "=== Test only ===" -ForegroundColor Cyan
    Invoke-Remote "docker ps --filter name=vega-tracker-ingest --format '{{.Names}}: {{.Status}}'" -Stream
    if (Test-Health) {
        Write-Host "`nAPI is healthy at http://${SSH_HOST}:${API_PORT}/health" -ForegroundColor Green
        Invoke-Remote "curl -s http://localhost:$API_PORT/info" -Stream
    } else {
        Write-Host "Health check failed." -ForegroundColor Red; exit 1
    }
    exit 0
}

# --- copy files -----------------------------------------------------------
if (-not $SkipCopy) {
    Write-Host "=== 1. Copy files ===" -ForegroundColor Cyan
    Invoke-Remote "mkdir -p $REMOTE_PATH"
    foreach ($f in @("tracker_ingest_api.py", "Dockerfile", "docker-compose.yml", "requirements.txt", ".env")) {
        $src = Join-Path $PROJECT_DIR $f
        if (-not (Test-Path $src)) { continue }
        $scpArgs = @($src, "${SSH_TARGET}:${REMOTE_PATH}/")
        if ($SSH_KEY_PATH -and $SSH_KEY_PATH -ne "~/.ssh/id_rsa") {
            $scpArgs = @("-i", $SSH_KEY_PATH) + $scpArgs
        }
        Write-Host "  scp $f"
        scp @scpArgs
    }
}

# --- build & run ----------------------------------------------------------
Write-Host "`n=== 2. Build & run container ===" -ForegroundColor Cyan
Invoke-Remote "cd $REMOTE_PATH && docker compose down 2>/dev/null || true" -Stream
Invoke-Remote "cd $REMOTE_PATH && docker compose build --progress=plain 2>&1" -Stream
Invoke-Remote "cd $REMOTE_PATH && docker compose up -d" -Stream

# --- verify ---------------------------------------------------------------
Write-Host "`n=== 3. Verify ===" -ForegroundColor Cyan
if (Test-Health -TimeoutSeconds 60) {
    Write-Host "API healthy at http://${SSH_HOST}:${API_PORT}" -ForegroundColor Green
    Invoke-Remote "curl -s http://localhost:$API_PORT/info"   -Stream
    Write-Host ""
    Write-Host "DEPLOYMENT SUCCESSFUL" -ForegroundColor Green
} else {
    Write-Host "Health check failed; printing recent logs:" -ForegroundColor Red
    Invoke-Remote "docker logs --tail=80 vega-tracker-ingest 2>&1" -Stream
    exit 1
}
