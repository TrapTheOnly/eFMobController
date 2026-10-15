param([switch]$Stop)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$jar = Join-Path $root "bridge\build\dist\padlayer-bridge.jar"
$so = Join-Path $root "bridge\build\libpadlayer.so"

if (-not (Test-Path $adb)) {
    Write-Error "adb was not found. Install the Android SDK platform-tools."
}
if (-not $Stop -and (-not (Test-Path $apk) -or -not (Test-Path $jar) -or -not (Test-Path $so))) {
    Write-Error "Build padlayer first. The APK, bridge jar, or native library is missing."
}

function Invoke-Adb([string]$Command) {
    $output = & $adb shell $Command 2>&1 | Out-String
    return $output
}

& $adb start-server
& $adb wait-for-device

$existing = Invoke-Adb "ps -A -o PID,ARGS"
foreach ($line in ($existing -split "`n")) {
    if ($line -match "com.padlayer.bridge.BridgeMain" -and $line -match "app_process" -and $line -match "^\s*(\d+)") {
        & $adb shell "kill $($Matches[1])" | Out-Null
    }
}
Invoke-Adb "rm -f /data/local/tmp/padlayer.pid" | Out-Null

if ($Stop) {
    Write-Output "Translator stopped."
    exit 0
}

& $adb install -r $apk | Out-Null
& $adb push $jar /data/local/tmp/padlayer-bridge.jar | Out-Null
& $adb push $so /data/local/tmp/libpadlayer.so | Out-Null

$events = Invoke-Adb "getevent -lp 2>/dev/null || true"
$padOn = $false
foreach ($line in ($events -split "`n")) {
    $lower = $line.ToLower()
    if ($lower.Contains("name:") -and $lower.Contains("xbox") -and -not $lower.Contains("padlayer")) {
        $padOn = $true
    }
}

if ($padOn) {
    Write-Output "The Xbox pad is already on. Bluetooth will drop for a few seconds so the translator connects first."
    Invoke-Adb "cmd bluetooth_manager disable >/dev/null 2>&1 || true" | Out-Null
    $gone = $false
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 400
        $again = Invoke-Adb "getevent -lp 2>/dev/null || true"
        $still = $false
        foreach ($line in ($again -split "`n")) {
            $lower = $line.ToLower()
            if ($lower.Contains("name:") -and $lower.Contains("xbox") -and -not $lower.Contains("padlayer")) {
                $still = $true
            }
        }
        if (-not $still) {
            $gone = $true
            break
        }
    }
    if (-not $gone) {
        Invoke-Adb "cmd bluetooth_manager enable >/dev/null 2>&1 || true" | Out-Null
        Write-Error "The Xbox pad stayed connected. Turn it off and run this again."
    }
}

$launch = "rm -f /data/local/tmp/padlayer.log; toybox nohup toybox setsid /system/bin/sh -c 'CLASSPATH=/data/local/tmp/padlayer-bridge.jar exec app_process /system/bin com.padlayer.bridge.BridgeMain' </dev/null >/data/local/tmp/padlayer.log 2>&1 &"
Invoke-Adb $launch | Out-Null

$ready = $false
$deadline = (Get-Date).AddSeconds(20)
$log = ""
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    $log = Invoke-Adb "cat /data/local/tmp/padlayer.log 2>/dev/null || true"
    if ($log -match "Virtual controller failed") {
        if ($padOn) {
            Invoke-Adb "cmd bluetooth_manager enable >/dev/null 2>&1 || true" | Out-Null
        }
        Write-Error $log
    }
    if ($log -match "Virtual controller is live") {
        $ready = $true
        break
    }
}

if ($padOn) {
    Invoke-Adb "cmd bluetooth_manager enable >/dev/null 2>&1 || true" | Out-Null
}

if (-not $ready) {
    Write-Error "The translator did not stay running.`n$log"
}

Write-Output "Translator is running in the background until the phone reboots. You can unplug the cable."
if ($padOn) {
    Write-Output "Bluetooth is coming back. Turn the Xbox pad on if it does not reconnect, then open a VS AI match."
} else {
    Write-Output "Turn the Xbox pad on, then open a VS AI match."
}
