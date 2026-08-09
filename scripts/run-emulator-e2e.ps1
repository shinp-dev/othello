param(
    [string]$AvdName = "Pixel_8a",
    [string]$AvdNameB = "Pixel_8a_B",
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$PlayerAEmail = $env:OTHELLO_E2E_PLAYER_A_EMAIL,
    [string]$PlayerAPassword = $env:OTHELLO_E2E_PLAYER_A_PASSWORD,
    [string]$PlayerBEmail = $env:OTHELLO_E2E_PLAYER_B_EMAIL,
    [string]$PlayerBPassword = $env:OTHELLO_E2E_PLAYER_B_PASSWORD,
    [int]$TimeoutSeconds = 120,
    [switch]$StartSupabase,
    [switch]$KeepEmulators,
    [switch]$AutoPlay
)

$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path $emulator)) { throw "emulator not found: $emulator" }
if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath. Run :app:assembleDebug first." }

if ($StartSupabase) {
    if (-not (Get-Command supabase -ErrorAction SilentlyContinue)) { throw 'Supabase CLI is required for -StartSupabase.' }
    & supabase start
    if ($LASTEXITCODE -ne 0) { throw 'supabase start failed.' }
}

$evidence = Join-Path (Get-Location) ("build/e2e/{0:yyyyMMdd-HHmmss}" -f (Get-Date))
New-Item -ItemType Directory -Force $evidence | Out-Null
$devices = @(@{ Name = 'A'; Id = 'emulator-5554'; Email = $PlayerAEmail; Password = $PlayerAPassword }, @{ Name = 'B'; Id = 'emulator-5556'; Email = $PlayerBEmail; Password = $PlayerBPassword })
$started = @()
$uiPlay = -join ([char[]](0x5bfe, 0x5c40, 0x3059, 0x308b))
$uiLogin = -join ([char[]](0x30ed, 0x30b0, 0x30a4, 0x30f3))
$uiLoggedIn = $uiLogin + -join ([char[]](0x4e2d, 0x3a))
$uiWaiting = -join ([char[]](0x5bfe, 0x6226, 0x76f8, 0x624b, 0x3092, 0x5f85, 0x3063, 0x3066, 0x3044, 0x307e, 0x3059))
$uiOnline = -join ([char[]](0x30aa, 0x30f3, 0x30e9, 0x30a4, 0x30f3, 0x5bfe, 0x5c40))

function Invoke-Adb([string]$device, [string[]]$arguments) {
    & $adb -s $device @arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed for ${device}: $($arguments -join ' ')" }
}

function Wait-DeviceBooted([string]$device) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $boot = (& $adb -s $device shell getprop sys.boot_completed 2>$null).Trim()
            $packageService = (& $adb -s $device shell pm path android 2>$null) -join ''
        } catch { $boot = ''; $packageService = '' }
        if ($boot -eq '1' -and $packageService -like 'package:*') { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$device did not finish booting in $TimeoutSeconds seconds."
}

function Get-UiXml([string]$device) {
    try {
        & $adb -s $device shell uiautomator dump /sdcard/othello-window.xml 2>$null | Out-Null
        return (& $adb -s $device shell cat /sdcard/othello-window.xml 2>$null) -join ""
    } catch { return '' }
}

function Wait-UiText([string]$device, [string]$text) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ((Get-UiXml $device) -like "*$text*") { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$device did not show UI text: $text"
}

function Get-TextBounds([string]$xml, [string]$text) {
    foreach ($node in ($xml -split '<node ')) {
        if ($node -like ('*text="{0}"*' -f $text)) {
            $match = [regex]::Match($node, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if ($match.Success) { return @([int]$match.Groups[1].Value, [int]$match.Groups[2].Value, [int]$match.Groups[3].Value, [int]$match.Groups[4].Value) }
        }
    }
    return $null
}

function Tap-UiText([string]$device, [string]$text) {
    $bounds = Get-TextBounds (Get-UiXml $device) $text
    if ($null -eq $bounds) { throw "$device cannot find tappable UI text: $text" }
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $y = [int](($bounds[1] + $bounds[3]) / 2)
    Invoke-Adb $device @('shell', 'input', 'tap', "$x", "$y") | Out-Null
}

function Enter-UiText([string]$device, [string]$label, [string]$value) {
    Tap-UiText $device $label
    Invoke-Adb $device @('shell', 'input', 'text', $value) | Out-Null
}

function Capture-Evidence($player) {
    $device = $player.Id
    $prefix = Join-Path $evidence "$($player.Name)-$(Get-Date -Format 'HHmmss')"
    $xml = Get-UiXml $device
    Set-Content -Path "$prefix.xml" -Value $xml -Encoding UTF8
    & $adb -s $device exec-out screencap -p | Set-Content -Path "$prefix.png" -AsByteStream
    & $adb -s $device logcat -d -v time -s OthelloSignaling OthelloWebRTC OthelloMatch OthelloProtocol | Set-Content -Path "$prefix.log" -Encoding UTF8
}

try {
    foreach ($player in $devices) {
        $selectedAvd = if ($player.Name -eq 'A') { $AvdName } else { $AvdNameB }
        $args = @('-avd', $selectedAvd, '-port', ($player.Id -replace 'emulator-', ''), '-no-snapshot', '-no-audio', '-no-boot-anim')
        Start-Process -FilePath $emulator -ArgumentList $args -WindowStyle Hidden | Out-Null
        $started += $player.Id
        Wait-DeviceBooted $player.Id
    }
    foreach ($player in $devices) {
        Invoke-Adb $player.Id @('install', '-r', (Resolve-Path $ApkPath).Path) | Out-Null
        Invoke-Adb $player.Id @('shell', 'pm', 'clear', 'com.example.othello') | Out-Null
        $launchArgs = @('shell', 'am', 'start', '-n', 'com.example.othello/.MainActivity')
        if ($AutoPlay) { $launchArgs += @('--ez', 'othello.e2e.autoplay', 'true') }
        Invoke-Adb $player.Id $launchArgs | Out-Null
        Wait-UiText $player.Id 'OTHELLO'
        Capture-Evidence $player
    }

    if ($PlayerAEmail -and $PlayerAPassword -and $PlayerBEmail -and $PlayerBPassword) {
        foreach ($player in $devices) {
            Enter-UiText $player.Id "Auth email" $player.Email
            Enter-UiText $player.Id "Password" $player.Password
            Tap-UiText $player.Id $uiLogin
            Wait-UiText $player.Id $uiLoggedIn
            Capture-Evidence $player
        }
        Tap-UiText "emulator-5554" $uiPlay
        Wait-UiText "emulator-5554" $uiWaiting
        Tap-UiText "emulator-5556" $uiPlay
        Wait-UiText "emulator-5554" $uiOnline
        Wait-UiText "emulator-5556" $uiOnline
        Wait-UiText "emulator-5554" "DC"
        Wait-UiText "emulator-5556" "DC"
        Capture-Evidence $devices[0]
        Capture-Evidence $devices[1]
        Write-Output "Emulator E2E signaling/DataChannel checkpoints passed. Evidence: $evidence"
    } else {
        Write-Warning "No E2E credentials supplied; completed two-emulator install/launch smoke test only."
        Write-Output "Evidence: $evidence"
    }
} finally {
    if (-not $KeepEmulators) {
        foreach ($device in $started) {
            & $adb -s $device emu kill 2>$null | Out-Null
            $global:LASTEXITCODE = 0
        }
    }
}
