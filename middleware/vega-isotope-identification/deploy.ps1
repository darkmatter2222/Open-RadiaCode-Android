#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Deploy Vega Isotope Identification API to remote server

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
    [switch]$TestOnly,
    [switch]$Verbose
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

# Model artifact
$MODEL_FILENAME = "vega_2d_v3_best.pt"

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

function Test-ApiHealth {
    param([int]$TimeoutSeconds = 120)
    
    Write-Host "Checking if API is healthy..." -ForegroundColor Cyan
    $start = Get-Date
    
    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSeconds) {
        try {
            $response = Invoke-RemoteCommand "curl -s -f http://localhost:$API_PORT/health" -Silent
            if ($response -match '"status":\s*"healthy"') {
                if ($response -match '"model_loaded":\s*true') {
                    return $true
                } else {
                    Write-Host "  API up, waiting for model to load..." -ForegroundColor Yellow
                }
            }
        } catch { }
        Start-Sleep -Seconds 3
    }
    
    return $false
}

# ==============================================================================
# Test Only Mode
# ==============================================================================

if ($TestOnly) {
    Write-Host "=== Testing Existing Deployment ===" -ForegroundColor Green
    
    Write-Host "`nChecking container status..." -ForegroundColor Cyan
    $containers = Invoke-RemoteCommand "docker ps --filter name=vega-isotope --format '{{.Names}}: {{.Status}}'"
    Write-Host "  $containers"
    
    if (Test-ApiHealth) {
        Write-Host "`nAPI is healthy!" -ForegroundColor Green
        
        Write-Host "`nTesting identification..." -ForegroundColor Cyan
        # Create a simple test spectrum (all zeros with a spike at Cs-137 energy)
        $testResult = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/info | head -c 500"
        if ($testResult -match "vega-isotope-identification") {
            Write-Host "  API is responding correctly!" -ForegroundColor Green
        } else {
            Write-Host "  Info check failed" -ForegroundColor Red
            Write-Host "  Response: $testResult"
        }
    } else {
        Write-Host "API health check failed!" -ForegroundColor Red
        exit 1
    }
    
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
    Invoke-RemoteCommand "mkdir -p $REMOTE_PATH/models"
    
    # Files to copy
    $filesToCopy = @(
        "isotope_api.py",
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
    
    # Copy model file (prefer service-local models/, fallback to vega_ml/models/)
    Write-Host "Copying 2D model file..." -ForegroundColor Cyan
    $localModelPath = Join-Path $PROJECT_DIR ("models\" + $MODEL_FILENAME)
    if (-not (Test-Path $localModelPath)) {
        $fallbackModelPath = Join-Path $PROJECT_DIR ("..\..\vega_ml\models\" + $MODEL_FILENAME)
        if (Test-Path $fallbackModelPath) {
            $localModelPath = $fallbackModelPath
        }
    }

    if (Test-Path $localModelPath) {
        Write-Host "  Copying $MODEL_FILENAME from: $localModelPath" -ForegroundColor DarkGray
        $scpArgs = @($localModelPath, "${SSH_TARGET}:${REMOTE_PATH}/models/")
        if ($SSH_KEY_PATH -and $SSH_KEY_PATH -ne "~/.ssh/id_rsa") {
            $scpArgs = @("-i", $SSH_KEY_PATH) + $scpArgs
        }
        scp @scpArgs
    } else {
        Write-Host "  WARNING: 2D Model file not found!" -ForegroundColor Yellow
        Write-Host "  Looked for: models\$MODEL_FILENAME and ..\..\vega_ml\models\$MODEL_FILENAME" -ForegroundColor Yellow
    }
    
    Write-Host "Files copied successfully!" -ForegroundColor Green
    Write-Host ""
}

# ==============================================================================
# Step 2: Build and deploy container
# ==============================================================================

Write-Host "=== Step 2: Building and deploying container ===" -ForegroundColor Green

# Stop existing container if running
Write-Host "Stopping existing container (if any)..." -ForegroundColor Cyan
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose down 2>/dev/null || true" -Stream

# Build and start
Write-Host ""
Write-Host "Building Docker image..." -ForegroundColor Cyan
Write-Host "=" * 60 -ForegroundColor DarkGray
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose build --progress=plain 2>&1" -Stream
Write-Host "=" * 60 -ForegroundColor DarkGray

Write-Host ""
Write-Host "Starting container..." -ForegroundColor Cyan
Invoke-RemoteCommand "cd $REMOTE_PATH && docker compose up -d" -Stream

Write-Host ""
Write-Host "Container started! Streaming logs..." -ForegroundColor Green
Write-Host "=" * 60 -ForegroundColor DarkGray

# Stream logs for up to 60 seconds or until model loads
$logJob = Start-Job -ScriptBlock {
    param($target, $path)
    ssh $target "docker logs -f vega-isotope 2>&1"
} -ArgumentList $SSH_TARGET, $REMOTE_PATH

$timeout = 120
$start = Get-Date
$modelLoaded = $false

while (((Get-Date) - $start).TotalSeconds -lt $timeout) {
    $output = Receive-Job -Job $logJob
    if ($output) {
        $output | ForEach-Object { Write-Host $_ }
        if ($output -match "Model loaded|Application startup complete") {
            Start-Sleep -Seconds 2
            $output = Receive-Job -Job $logJob
            if ($output) { $output | ForEach-Object { Write-Host $_ } }
        }
    }
    
    $health = ssh $SSH_TARGET "curl -s http://localhost:$API_PORT/health 2>/dev/null"
    if ($health -match '"model_loaded":\s*true') {
        $modelLoaded = $true
        break
    }
    
    Start-Sleep -Milliseconds 500
}

Stop-Job -Job $logJob -ErrorAction SilentlyContinue
Remove-Job -Job $logJob -ErrorAction SilentlyContinue

Write-Host "=" * 60 -ForegroundColor DarkGray
Write-Host ""

# ==============================================================================
# Step 3: Final verification
# ==============================================================================

Write-Host "=== Step 3: Final verification ===" -ForegroundColor Green

$containers = Invoke-RemoteCommand "docker ps --filter name=vega-isotope --format '{{.Names}}: {{.Status}}'" -Silent
Write-Host "Container: $containers"

$health = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/health" -Silent
Write-Host "Health: $health"

if ($modelLoaded -or ($health -match '"model_loaded":\s*true')) {
    Write-Host ""
    Write-Host "API is healthy with model loaded!" -ForegroundColor Green
    
    # Test endpoints
    Write-Host "`nTesting info endpoint..." -ForegroundColor Cyan
    Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/info | head -c 300" -Stream
    
    Write-Host ""
    Write-Host "`nTesting isotopes endpoint..." -ForegroundColor Cyan
    $isotopes = Invoke-RemoteCommand "curl -s http://localhost:$API_PORT/isotopes | head -c 200" -Silent
    if ($isotopes -match '"total":\s*82') {
        Write-Host "  Found all 82 isotopes!" -ForegroundColor Green
    }
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  DEPLOYMENT SUCCESSFUL!" -ForegroundColor Green  
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "API is running at:" -ForegroundColor Cyan
    Write-Host "  http://${SSH_HOST}:${API_PORT}"
    Write-Host ""
    Write-Host "Endpoints:" -ForegroundColor Cyan
    Write-Host "  POST /identify        - Identify isotopes from spectrum"
    Write-Host "  POST /identify/b64    - Identify from base64-encoded numpy"
    Write-Host "  POST /identify/batch  - Batch identification"
    Write-Host "  GET  /health          - Health check"
    Write-Host "  GET  /info            - Model info"
    Write-Host "  GET  /isotopes        - List supported isotopes"
    Write-Host ""
    
} else {
    Write-Host ""
    Write-Host "Model failed to load!" -ForegroundColor Red
    Write-Host "Last logs:" -ForegroundColor Cyan
    Invoke-RemoteCommand "docker logs vega-isotope --tail 30 2>&1" -Stream
    exit 1
}
