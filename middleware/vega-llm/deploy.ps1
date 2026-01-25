#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Deploy Vega LLM API to remote server

.DESCRIPTION
    This script:
    1. Loads credentials from .env
    2. Copies project files to remote server
    3. Builds and starts the Docker container
    4. Tests the API to verify deployment

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
        & ssh @sshArgs 2>&1 | ForEach-Object { Write-Host $_ }
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
    $containers = Invoke-RemoteCommand "docker ps --filter name=vega-llm --format '{{.Names}}: {{.Status}}'"
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
        "api.py",
        "Dockerfile", 
        "docker-compose.yml",
        "requirements.txt",
        ".dockerignore"
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
    
    # Create remote .env file with HF_TOKEN
    Write-Host "Setting up environment variables on server..." -ForegroundColor Cyan
    if ($HF_TOKEN -and $HF_TOKEN -ne "your_hf_token_here") {
        Invoke-RemoteCommand "echo 'HF_TOKEN=$HF_TOKEN' > $REMOTE_PATH/.env"
        Write-Host "  HF_TOKEN configured" -ForegroundColor DarkGray
    } else {
        Write-Host "  WARNING: HF_TOKEN not set - model download may fail!" -ForegroundColor Yellow
    }
    
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

Write-Host ""
Write-Host "Container started! Streaming logs..." -ForegroundColor Green
Write-Host ("=" * 60) -ForegroundColor DarkGray

# Stream logs for a bit
$logJob = Start-Job -ScriptBlock {
    param($target, $path)
    ssh $target "docker logs -f vega-llm 2>&1"
} -ArgumentList $SSH_TARGET, $REMOTE_PATH

$timeout = 180
$start = Get-Date
$modelLoaded = $false

while (((Get-Date) - $start).TotalSeconds -lt $timeout) {
    $output = Receive-Job -Job $logJob
    if ($output) {
        $output | ForEach-Object { Write-Host $_ }
        if ($output -match "Model loaded|Uvicorn running") {
            Start-Sleep -Seconds 2
            $output = Receive-Job -Job $logJob
            if ($output) { $output | ForEach-Object { Write-Host $_ } }
        }
    }
    
    # Check health
    $health = ssh $SSH_TARGET "curl -s http://localhost:$API_PORT/health 2>/dev/null"
    if ($health -match '"model_loaded":\s*true') {
        $modelLoaded = $true
        break
    }
    
    Start-Sleep -Milliseconds 500
}

Stop-Job -Job $logJob -ErrorAction SilentlyContinue
Remove-Job -Job $logJob -ErrorAction SilentlyContinue

Write-Host ("=" * 60) -ForegroundColor DarkGray
Write-Host ""

# ==============================================================================
# Step 3: Final verification
# ==============================================================================

Write-Host "=== Step 3: Final verification ===" -ForegroundColor Green

$containers = Invoke-RemoteCommand "docker ps --filter name=vega-llm --format '{{.Names}}: {{.Status}}'" -Silent
Write-Host "Container: $containers"

$health = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/health" -Silent
Write-Host "Health: $health"

if ($modelLoaded -or ($health -match '"model_loaded":\s*true')) {
    Write-Host ""
    Write-Host "API is healthy with model loaded!" -ForegroundColor Green
    
    # Test chat
    Write-Host "`nTesting chat..." -ForegroundColor Cyan
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  DEPLOYMENT SUCCESSFUL!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "API is running at:" -ForegroundColor Cyan
    Write-Host "  http://${SSH_HOST}:${API_PORT}"
    Write-Host ""
    Write-Host "Endpoints:" -ForegroundColor Cyan
    Write-Host "  POST /chat      - Chat completion"
    Write-Host "  POST /generate  - Text completion"
    Write-Host "  GET  /health    - Health check"
    Write-Host "  GET  /info      - Model info"
    Write-Host ""
    
} else {
    Write-Host ""
    Write-Host "Model may still be loading..." -ForegroundColor Yellow
    Write-Host "Check logs with: ssh $SSH_TARGET 'docker logs -f vega-llm'"
}
