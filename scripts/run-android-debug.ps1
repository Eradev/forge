# Install the latest signed Forge APK on a connected device, sync matching
# assets.zip when the device pack is outdated, and launch the app.
# Requires: adb (PATH or C:\asdk\platform-tools), a connected device/emulator,
# and a prior successful scripts/build-android-test.ps1 run.
#
# -WaitForDebugger starts the app suspended until a JDWP debugger attaches. That
# only works if the APK was built debuggable (android:debuggable="true"); the
# android-test-build profile does not set it, so plain launch is the default.
#
# -ForceAssets always rebuilds assets.zip from forge-gui/res and pushes it.
#
# If an existing forge.app was signed with a different key (Play Store / release /
# older debug keystore), Android refuses the upgrade - there is no force-update
# across signatures. The script removes the package with `adb uninstall -k`
# (keeps data/cache) and retries the install once.

param(
    [switch]$WaitForDebugger,
    [switch]$ForceAssets
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$RepoRoot\pom.xml")) {
    $RepoRoot = "C:\Dev\forge"
}

. (Join-Path $PSScriptRoot "android-assets.ps1")

$Adb = "adb"
if (Test-Path "C:\asdk\platform-tools\adb.exe") {
    $Adb = "C:\asdk\platform-tools\adb.exe"
}

$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    throw "No Android device/emulator found. Connect a phone (USB debugging) or start an emulator, then retry."
}

$TargetDir = Join-Path $RepoRoot "forge-gui-android\target"
$Apk = Get-ChildItem "$TargetDir\*-aligned-debugSigned.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $Apk) {
    throw "No *-aligned-debugSigned.apk in $TargetDir. Run 'Forge Android Build' first."
}

function Install-Apk {
    param([string]$Path)
    $result = Invoke-Adb -Adb $Adb -AdbArgs @("install", "-r", $Path)
    if ($result.Output) { Write-Host $result.Output }
    return $result
}

Write-Host "Installing $($Apk.Name)..."
$result = Install-Apk -Path $Apk.FullName

if ($result.Code -ne 0 -and $result.Output -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
    Write-Host "Existing forge.app has a different signature."
    Write-Host "Android cannot force-update across keys; removing the package but keeping data (-k)..."
    & $Adb uninstall -k forge.app | Out-Host
    $result = Install-Apk -Path $Apk.FullName
}

if ($result.Code -ne 0) {
    exit $result.Code
}

# Keep device resources on the same commit as the APK / local forge-gui/res.
# Otherwise Adventure settings (and anything else) show missing-translation
# strings while PC Adventure (which reads forge-gui/res live) looks fine.
Sync-ForgeAndroidAssets -Adb $Adb -RepoRoot $RepoRoot -Force:$ForceAssets

if ($WaitForDebugger) {
    Write-Host "Starting forge.app/.Launcher (waiting for debugger)..."
    & $Adb shell am start -D -n forge.app/.Launcher
} else {
    Write-Host "Starting forge.app/.Launcher..."
    & $Adb shell am start -n forge.app/.Launcher
}
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
if ($WaitForDebugger) {
    Write-Host "App is suspended. In IntelliJ: Ctrl+Shift+A -> Attach Debugger to Android Process -> forge.app"
    Write-Host "If it never resumes, the APK is not debuggable - rebuild with android:debuggable=true."
} else {
    Write-Host "Forge is starting on the device. Logs: adb logcat -s Forge:V AndroidRuntime:E"
}
