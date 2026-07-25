# Build a debug-signed Forge Android test APK on Windows.
# Requires: Maven 3.8.1 at C:\mvn-3.8.1, junctions C:\m2 / C:\asdk / C:\jdk17
# (short paths avoid Windows cmd.exe 8191-char limit on the D8 classpath)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$RepoRoot\pom.xml")) {
    $RepoRoot = "C:\Dev\forge"
}

foreach ($path in @("C:\mvn-3.8.1\bin\mvn.cmd", "C:\m2", "C:\asdk", "C:\jdk17")) {
    if (-not (Test-Path $path)) {
        throw "Missing required path: $path. Re-create junctions / install Maven 3.8.1 as in the Android local setup."
    }
}

$env:JAVA_HOME = "C:\jdk17"
$env:ANDROID_HOME = "C:\asdk"
$env:ANDROID_SDK_ROOT = "C:\asdk"
$env:MAVEN_HOME = "C:\mvn-3.8.1"
$env:Path = "C:\mvn-3.8.1\bin;C:\jdk17\bin;C:\asdk\platform-tools;$env:Path"

Set-Location $RepoRoot

& "C:\mvn-3.8.1\bin\mvn.cmd" -U -B -P android-test-build verify -e -T 1C `
    "-Dmaven.repo.local=C:\m2" `
    "-Dandroid.sdk.path=C:\asdk" `
    "-Dandroid.buildToolsVersion=35.0.0" `
    "-Dmaven.test.skip=true"

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "APKs:"
Get-ChildItem "$RepoRoot\forge-gui-android\target\*.apk" | ForEach-Object {
    Write-Host ("  {0} ({1:N0} bytes)" -f $_.FullName, $_.Length)
}
