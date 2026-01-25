#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Deploy Vega Ingress (API Gateway) to remote server

.DESCRIPTION
    This script:
    1. Loads credentials from .env
    2. Copies project files to remote server
    3. Builds and starts the Docker container

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -SkipCopy    # Just rebuild, don't copy files
    .\deploy.ps1 -TestOnly    # Just test existing deployment
#>

param(
    [switch]$SkipCopy,
    [switch]$TestOnly
)

$ErrorActionPreference = "Stop"

# PowerShell can treat native stderr output as an error record (NativeCommandError),
# which can abort the script when ErrorActionPreference=Stop. We intentionally stream
# remote docker output (which may write warnings to stderr) and do not want warnings
# to fail deployments.
try {
    $global:PSNativeCommandUseErrorActionPreference = $false
} catch { }

# ==============================================================================
# Load environment variables from .env
# ==============================================================================

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "ERROR: .env file not found!" -ForegroundColor Red
    Write-Host "Copy .env.example to .env and fill in your values"
    exit 1
}

Write-Host "Loading configuration from .env..." -ForegroundColor Cyan
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2].Trim()
        Set-Variable -Name $name -Value $value -Scope Script
    }
}

# Validate required variables
$required = @("SSH_USER", "SSH_HOST", "REMOTE_PATH", "API_PORT")
foreach ($var in $required) {
    if (-not (Get-Variable -Name $var -ValueOnly -ErrorAction SilentlyContinue)) {
        Write-Host "ERROR: Missing required variable: $var" -ForegroundColor Red
        exit 1
    }
}

$SSH_TARGET = "$SSH_USER@$SSH_HOST"
$PROJECT_DIR = $PSScriptRoot

Write-Host ""
Write-Host "Configuration:" -ForegroundColor Green
Write-Host "  Server: $SSH_TARGET"
Write-Host "  Remote Path: $REMOTE_PATH"
Write-Host "  API Port: $API_PORT"
Write-Host ""

# ==============================================================================
# Helper functions
# ==============================================================================

function Invoke-RemoteCommand {
    param([string]$Command, [switch]$Silent, [switch]$Stream)
    
    $sshArgs = @($SSH_TARGET, $Command)
    if ($SSH_KEY_PATH -and $SSH_KEY_PATH -ne "~/.ssh/id_rsa") {
        $sshArgs = @("-i", $SSH_KEY_PATH) + $sshArgs
    }
    
    if (-not $Silent) {
        Write-Host "  > $Command" -ForegroundColor DarkGray
    }
    
    if ($Stream) {
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & ssh @sshArgs 2>&1 | ForEach-Object { Write-Host $_ }
        } finally {
            $ErrorActionPreference = $prevEap
        }
    } else {
        $result = ssh @sshArgs 2>&1
        if ($LASTEXITCODE -ne 0 -and -not $Silent) {
            Write-Host "  Warning: Command returned exit code $LASTEXITCODE" -ForegroundColor Yellow
        }
        return $result
    }
}

# ==============================================================================
# Test Only Mode
# ==============================================================================

if ($TestOnly) {
    Write-Host "=== Testing Existing Deployment ===" -ForegroundColor Green
    
    Write-Host "`nChecking container status..." -ForegroundColor Cyan
    $containers = Invoke-RemoteCommand "docker ps --filter name=vega-ingress --format '{{.Names}}: {{.Status}}'"
    Write-Host "  $containers"
    
    Write-Host "`nChecking health..." -ForegroundColor Cyan
    $health = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/health"
    Write-Host "  $health"
    
    exit 0
}

# ==============================================================================
# Step 1: Copy files to server
# ==============================================================================

if (-not $SkipCopy) {
    Write-Host "=== Step 1: Copying files to server ===" -ForegroundColor Green
    
    # Create remote directory
    Write-Host "Creating remote directory..." -ForegroundColor Cyan
    Invoke-RemoteCommand "mkdir -p $REMOTE_PATH"
    
    # Files to copy
    $filesToCopy = @(
        "ingress_api.py",
        "Dockerfile", 
        "docker-compose.yml",
        "requirements.txt"
    )
    
    # Copy individual files
    Write-Host "Copying project files..." -ForegroundColor Cyan
    foreach ($file in $filesToCopy) {
        $localPath = Join-Path $PROJECT_DIR $file
        if (Test-Path $localPath) {
            Write-Host "  Copying $file..." -ForegroundColor DarkGray
            $scpArgs = @($localPath, "${SSH_TARGET}:${REMOTE_PATH}/")
            if ($SSH_KEY_PATH -and $SSH_KEY_PATH -ne "~/.ssh/id_rsa") {
                $scpArgs = @("-i", $SSH_KEY_PATH) + $scpArgs
            }
            scp @scpArgs
        }
    }

    # Ensure host-mounted logs directory exists on the remote host
    Write-Host "Ensuring logs directory exists on remote host..." -ForegroundColor Cyan
    Invoke-RemoteCommand "mkdir -p $REMOTE_PATH/logs"
    
    Write-Host "Files copied successfully!" -ForegroundColor Green
    Write-Host ""
}

# ==============================================================================
# Step 2: Build and deploy container
# ==============================================================================

Write-Host "=== Step 2: Building and deploying container ===" -ForegroundColor Green

# Stop existing container
Write-Host "Stopping existing container (if any)..." -ForegroundColor Cyan
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose down 2>/dev/null || true" -Stream

# Build and start
Write-Host ""
Write-Host "Building Docker image..." -ForegroundColor Cyan
Write-Host ("=" * 60) -ForegroundColor DarkGray
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose build --progress=plain 2>&1" -Stream
Write-Host ("=" * 60) -ForegroundColor DarkGray

Write-Host ""
Write-Host "Starting container..." -ForegroundColor Cyan
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose up -d" -Stream

# Wait for startup
Start-Sleep -Seconds 5

# ==============================================================================
# Step 3: Final verification
# ==============================================================================

Write-Host ""
Write-Host "=== Step 3: Final verification ===" -ForegroundColor Green

$containers = Invoke-RemoteCommand "docker ps --filter name=vega-ingress --format '{{.Names}}: {{.Status}}'" -Silent
Write-Host "Container: $containers"

$health = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/health" -Silent
Write-Host "Health: $health"

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  DEPLOYMENT SUCCESSFUL!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "API Gateway is running at:" -ForegroundColor Cyan
Write-Host "  http://${SSH_HOST}:${API_PORT}"
Write-Host ""
Write-Host "Routes:" -ForegroundColor Cyan
Write-Host "  /tts/*      -> TTS service (port 8000)"
Write-Host "  /llm/*      -> LLM service (port 8001)"
Write-Host "  /synthesize -> Direct TTS synthesize"
Write-Host "  /chat       -> Direct LLM chat"
Write-Host "  /generate   -> Direct LLM generate"
Write-Host ""
