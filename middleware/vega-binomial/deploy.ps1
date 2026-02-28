#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Deploy Vega Bulk Binomial Detection API to remote server

.DESCRIPTION
    This script:
    1. Loads credentials from .env
    2. Copies project files and models to remote server
    3. Builds and starts the Docker container
    4. Tests the API to verify deployment

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -SkipCopy    # Just rebuild, don't copy files
    .\deploy.ps1 -TestOnly    # Just test existing deployment
    .\deploy.ps1 -SkipModels  # Copy code but not model files
#>

param(
    [switch]$SkipCopy,
    [switch]$TestOnly,
    [switch]$SkipModels,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"

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

# Optional variables with defaults
if (-not $SSH_KEY_PATH) { $SSH_KEY_PATH = "" }
if (-not $LOCAL_MODELS_DIR) { $LOCAL_MODELS_DIR = "../../vega_ml/models/binary_isotope_v5_micro_2000per" }

# Build SSH options
$sshOpts = @("-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes")
if ($SSH_KEY_PATH) {
    $expandedKeyPath = [System.Environment]::ExpandEnvironmentVariables($SSH_KEY_PATH.Replace("~", $env:USERPROFILE))
    if (Test-Path $expandedKeyPath) {
        $sshOpts += @("-i", $expandedKeyPath)
    }
}
$sshTarget = "${SSH_USER}@${SSH_HOST}"

Write-Host "Configuration:" -ForegroundColor Yellow
Write-Host "  Server: $sshTarget"
Write-Host "  Remote path: $REMOTE_PATH"
Write-Host "  API port: $API_PORT"
Write-Host "  Models dir: $LOCAL_MODELS_DIR"
Write-Host ""

# ==============================================================================
# Test existing deployment
# ==============================================================================

function Test-Deployment {
    Write-Host "`nTesting API..." -ForegroundColor Cyan
    
    try {
        $healthUrl = "http://${SSH_HOST}:${API_PORT}/health"
        Write-Host "  Health: $healthUrl"
        $response = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 10
        Write-Host "  Status: $($response.status)" -ForegroundColor Green
        Write-Host "  Models loaded: $($response.models_loaded)"
        Write-Host "  Device: $($response.device)"
        
        # Get detailed info
        $infoUrl = "http://${SSH_HOST}:${API_PORT}/info"
        $info = Invoke-RestMethod -Uri $infoUrl -Method Get -TimeoutSec 10
        Write-Host "  Version: $($info.version)"
        Write-Host "  CUDA: $($info.cuda_device_name)"
        
        return $true
    } catch {
        Write-Host "  API test failed: $_" -ForegroundColor Red
        return $false
    }
}

if ($TestOnly) {
    $result = Test-Deployment
    if ($result) { exit 0 } else { exit 1 }
}

# ==============================================================================
# Copy files to server
# ==============================================================================

if (-not $SkipCopy) {
    Write-Host "`nCreating remote directory..." -ForegroundColor Cyan
    & ssh @sshOpts $sshTarget "mkdir -p $REMOTE_PATH/models"
    
    Write-Host "Copying project files..." -ForegroundColor Cyan
    $projectFiles = @(
        "binomial_api.py",
        "requirements.txt",
        "Dockerfile",
        "docker-compose.yml"
    )
    
    foreach ($file in $projectFiles) {
        $localPath = Join-Path $PSScriptRoot $file
        if (Test-Path $localPath) {
            Write-Host "  $file"
            & scp @sshOpts $localPath "${sshTarget}:${REMOTE_PATH}/"
        }
    }
    
    # Copy model files
    if (-not $SkipModels) {
        $modelsPath = Join-Path $PSScriptRoot $LOCAL_MODELS_DIR
        if (Test-Path $modelsPath) {
            Write-Host "`nCopying model files..." -ForegroundColor Cyan
            
            # Count model files
            $modelFiles = Get-ChildItem -Path $modelsPath -Filter "binary_*.pt" -File
            $metaFiles = Get-ChildItem -Path $modelsPath -Filter "binary_*_meta.json" -File
            Write-Host "  Found $($modelFiles.Count) model checkpoints"
            Write-Host "  Found $($metaFiles.Count) metadata files"
            
            # Copy all model files
            $allFiles = Get-ChildItem -Path $modelsPath -Filter "binary_*" -File
            foreach ($file in $allFiles) {
                Write-Host "  $($file.Name)"
                & scp @sshOpts $file.FullName "${sshTarget}:${REMOTE_PATH}/models/"
            }
        } else {
            Write-Host "`nWARNING: Models directory not found: $modelsPath" -ForegroundColor Yellow
            Write-Host "  The service will start but no models will be loaded."
        }
    }
}

# ==============================================================================
# Build and start container
# ==============================================================================

Write-Host "`nBuilding Docker image on server..." -ForegroundColor Cyan
& ssh @sshOpts $sshTarget "cd $REMOTE_PATH && docker compose build"

Write-Host "`nStarting container..." -ForegroundColor Cyan
& ssh @sshOpts $sshTarget "cd $REMOTE_PATH && docker compose down 2>/dev/null; docker compose up -d"

# Wait for startup
Write-Host "`nWaiting for API to start (loading models)..." -ForegroundColor Cyan
$maxWait = 180  # 3 minutes for many models
$waited = 0
$interval = 5

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds $interval
    $waited += $interval
    
    try {
        $response = Invoke-RestMethod -Uri "http://${SSH_HOST}:${API_PORT}/health" -Method Get -TimeoutSec 5
        if ($response.status -eq "healthy") {
            Write-Host "  API is ready! ($waited seconds)" -ForegroundColor Green
            break
        }
    } catch {
        Write-Host "  Waiting... ($waited/$maxWait seconds)"
    }
}

if ($waited -ge $maxWait) {
    Write-Host "  Timeout waiting for API!" -ForegroundColor Yellow
    Write-Host "  Checking logs..."
    & ssh @sshOpts $sshTarget "docker logs --tail 50 vega-binomial"
}

# ==============================================================================
# Final test
# ==============================================================================

Write-Host ""
Test-Deployment

Write-Host "`nDeployment complete!" -ForegroundColor Green
Write-Host "API available at: http://${SSH_HOST}:${API_PORT}" -ForegroundColor Cyan
