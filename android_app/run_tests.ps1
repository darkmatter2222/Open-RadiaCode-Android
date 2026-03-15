<#
.SYNOPSIS
    Automated test runner for RadiaCode Android app.
    Sends commands to the in-app TestReceiver via ADB broadcasts
    and parses structured JSON results from logcat.

.DESCRIPTION
    The app includes a debug-only BroadcastReceiver (TestReceiver) that can
    introspect UI state, switch tabs, toggle controls, and run a full test
    suite -- all reporting results through logcat with the "TestResult" tag.

    This script orchestrates the testing and presents results in a readable format.

.PARAMETER DeviceSerial
    ADB device serial number. Defaults to 37260DLJG003LN.

.PARAMETER Command
    Test command to run. Defaults to "run_suite".
    Options: ping, app_state, tab_info, switch_tab, scale_state,
             set_scale, toggle_auto_scale, prefs_dump, view_tree, run_suite

.EXAMPLE
    .\run_tests.ps1                          # Run full test suite
    .\run_tests.ps1 -Command ping            # Quick connectivity check
    .\run_tests.ps1 -Command app_state       # Get current app state
    .\run_tests.ps1 -Command scale_state     # Check scale control state
#>

param(
    [string]$DeviceSerial = "37260DLJG003LN",
    [string]$Command = "run_suite",
    [hashtable]$Extras = @{}
)

$ErrorActionPreference = "Stop"
$PACKAGE = "com.radiacode.ble"
$ACTION = "$PACKAGE.TEST"
$TAG = "TestResult"

function Write-Header {
    param([string]$Text)
    $line = "=" * 60
    Write-Host ""
    Write-Host $line -ForegroundColor DarkCyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host $line -ForegroundColor DarkCyan
}

function Write-TestResult {
    param(
        [string]$Name,
        [string]$Result,
        [string]$Error = ""
    )
    $icon = switch ($Result) {
        "PASS"  { "[PASS]" }
        "FAIL"  { "[FAIL]" }
        "ERROR" { "[ERR!]" }
        default { "[????]" }
    }
    $color = switch ($Result) {
        "PASS"  { "Green" }
        "FAIL"  { "Red" }
        "ERROR" { "Yellow" }
        default { "Gray" }
    }

    $line = "  $icon $Name"
    Write-Host $line -ForegroundColor $color -NoNewline
    if ($Error) {
        Write-Host " -- $Error" -ForegroundColor DarkGray
    } else {
        Write-Host ""
    }
}

function Send-TestCommand {
    param(
        [string]$Cmd,
        [hashtable]$ExtraParams = @{},
        [int]$TimeoutMs = 5000
    )

    # Clear logcat of old TestResult messages
    adb -s $DeviceSerial logcat -c 2>$null

    # Build the broadcast command string
    # -f 0x01000000 = FLAG_RECEIVER_INCLUDE_BACKGROUND (required on Android 14+)
    $extraStr = "--es cmd `"$Cmd`""
    foreach ($key in $ExtraParams.Keys) {
        $extraStr += " --es $key `"$($ExtraParams[$key])`""
    }

    $shellCmd = "am broadcast -a $ACTION -f 0x01000000 $extraStr"

    # Send the broadcast
    $broadcastOutput = adb -s $DeviceSerial shell $shellCmd 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  ADB broadcast failed: $broadcastOutput" -ForegroundColor Red
        return $null
    }

    # Wait for the TestResult to appear in logcat
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    $result = $null

    while ((Get-Date) -lt $deadline) {
        $logLines = adb -s $DeviceSerial logcat -d -v raw -s "${TAG}:*" 2>&1
        foreach ($line in $logLines) {
            $trimmed = $line.ToString().Trim()
            if ($trimmed.StartsWith("{") -and $trimmed.EndsWith("}")) {
                try {
                    $result = $trimmed | ConvertFrom-Json
                    if ($result.cmd -eq $Cmd) {
                        return $result
                    }
                } catch {
                    # Not valid JSON, skip
                }
            }
        }
        Start-Sleep -Milliseconds 200
    }

    Write-Host "  Timeout waiting for response to '$Cmd'" -ForegroundColor Yellow
    return $null
}

function Run-Ping {
    Write-Header "Ping Test"
    $result = Send-TestCommand -Cmd "ping"
    if ($null -eq $result) {
        Write-Host "  No response from TestReceiver" -ForegroundColor Red
        Write-Host "  Make sure:" -ForegroundColor Yellow
        Write-Host "    - The app is running (debug build)" -ForegroundColor Yellow
        Write-Host "    - Device serial is correct: $DeviceSerial" -ForegroundColor Yellow
        return $false
    }

    Write-Host "  Status:         $($result.status)" -ForegroundColor Green
    Write-Host "  Debug Build:    $($result.debug)" -ForegroundColor Gray
    Write-Host "  Version:        $($result.version)" -ForegroundColor Gray
    Write-Host "  Activity Alive: $($result.activity_alive)" -ForegroundColor Gray
    return $true
}

function Run-AppState {
    Write-Header "App State"
    $result = Send-TestCommand -Cmd "app_state"
    if ($null -eq $result) { return }

    Write-Host "  Current Tab:      $($result.current_tab) ($($result.selected_tab_text))" -ForegroundColor Gray
    Write-Host "  Tab Count:        $($result.tab_count)" -ForegroundColor Gray
    Write-Host "  Status:           $($result.status_text)" -ForegroundColor Gray
    Write-Host "  Connected Devices: $($result.connected_count)" -ForegroundColor Gray

    if ($result.device_connections) {
        Write-Host "  Devices:" -ForegroundColor Gray
        $result.device_connections.PSObject.Properties | ForEach-Object {
            Write-Host "    ...$($_.Name): $($_.Value)" -ForegroundColor DarkGray
        }
    }
}

function Run-ScaleState {
    Write-Header "Scale Control State"
    $result = Send-TestCommand -Cmd "scale_state"
    if ($null -eq $result) { return }

    Write-Host "  Auto Mode:      $($result.pref_auto)" -ForegroundColor Gray
    Write-Host "  Pref Min:       $($result.pref_min)" -ForegroundColor Gray
    Write-Host "  Pref Max:       $($result.pref_max)" -ForegroundColor Gray
    if ($null -ne $result.view_min_text) {
        Write-Host "  View Min Text:  '$($result.view_min_text)'" -ForegroundColor Gray
        Write-Host "  View Max Text:  '$($result.view_max_text)'" -ForegroundColor Gray
        Write-Host "  Min Enabled:    $($result.view_min_enabled)" -ForegroundColor Gray
        Write-Host "  Max Enabled:    $($result.view_max_enabled)" -ForegroundColor Gray
    }
}

function Run-PrefsDump {
    Write-Header "Preferences Dump"
    $result = Send-TestCommand -Cmd "prefs_dump"
    if ($null -eq $result) { return }

    $result.PSObject.Properties | Where-Object { $_.Name -notin @("cmd", "status") } | ForEach-Object {
        Write-Host "  $($_.Name): $($_.Value)" -ForegroundColor Gray
    }
}

function Run-FullSuite {
    Write-Header "Full Test Suite"
    Write-Host "  Sending run_suite command..." -ForegroundColor Gray
    Write-Host "  (This runs 40+ tests across all tabs)" -ForegroundColor DarkGray
    Write-Host ""

    $result = Send-TestCommand -Cmd "run_suite" -TimeoutMs 15000
    if ($null -eq $result) {
        Write-Host "  No response -- test suite did not complete" -ForegroundColor Red
        return
    }

    $passed = $result.passed
    $failed = $result.failed
    $total = $result.total

    foreach ($test in $result.results) {
        $errorMsg = ""
        if ($test.error) { $errorMsg = $test.error }
        Write-TestResult -Name $test.test -Result $test.result -Error $errorMsg
    }

    Write-Host ""
    $line = "-" * 60
    Write-Host $line -ForegroundColor DarkGray

    $summaryColor = if ($failed -eq 0) { "Green" } else { "Red" }
    Write-Host "  Results: $passed passed, $failed failed, $total total" -ForegroundColor $summaryColor

    if ($failed -eq 0) {
        Write-Host "  All tests passed!" -ForegroundColor Green
    } else {
        Write-Host "  $failed test(s) FAILED" -ForegroundColor Red
    }
    Write-Host $line -ForegroundColor DarkGray
}

function Run-TabInfo {
    param([int]$TabIndex)
    Write-Header "Tab $TabIndex Info"
    $result = Send-TestCommand -Cmd "tab_info" -ExtraParams @{ "tab" = "$TabIndex" }
    if ($null -eq $result) { return }

    $result.PSObject.Properties | Where-Object { $_.Name -notin @("cmd", "status") } | ForEach-Object {
        Write-Host "  $($_.Name): $($_.Value)" -ForegroundColor Gray
    }
}

# ── Main ──────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "RadiaCode Test Runner" -ForegroundColor Cyan
Write-Host "Device: $DeviceSerial" -ForegroundColor DarkGray
Write-Host "Command: $Command" -ForegroundColor DarkGray

# Verify ADB connectivity
$deviceCheck = adb -s $DeviceSerial get-state 2>&1
if ($deviceCheck -ne "device") {
    Write-Host "Device not found or not connected: $DeviceSerial" -ForegroundColor Red
    exit 1
}

switch ($Command) {
    "ping"               { Run-Ping }
    "app_state"          { Run-AppState }
    "scale_state"        { Run-ScaleState }
    "prefs_dump"         { Run-PrefsDump }
    "run_suite"          {
        $alive = Run-Ping
        if ($alive) {
            Run-FullSuite
        }
    }
    "tab_info"           {
        $tabIdx = if ($Extras.ContainsKey("tab")) { [int]$Extras["tab"] } else { 0 }
        Run-TabInfo -TabIndex $tabIdx
    }
    default {
        Write-Host "Sending custom command: $Command" -ForegroundColor Yellow
        $result = Send-TestCommand -Cmd $Command -ExtraParams $Extras
        if ($result) {
            $result | ConvertTo-Json -Depth 5 | Write-Host
        }
    }
}

Write-Host ""
