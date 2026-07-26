# Shared helpers for packaging forge-gui/res into assets.zip and syncing it to a
# connected Android device. Layout matches android-release-build / android-test-build:
#   assets.zip
#     LICENSE.txt, …
#     res/
#       build.txt          (must match the APK's embedded build.txt)
#       cardsfolder/cardsfolder.zip
#       languages/, skins/, …

function Get-ForgeAndroidPackageName {
    param([string]$Adb)
    $pkgs = & $Adb shell pm list packages 2>$null | ForEach-Object { $_.ToString().Trim() }
    foreach ($name in @("forge.app", "forge.app.dev")) {
        if ($pkgs -contains "package:$name") { return $name }
    }
    return "forge.app"
}

function Get-ForgeAndroidAssetsDir {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string]$PackageName = "forge.app"
    )
    $obb = "/storage/emulated/0/Android/obb/$PackageName/Forge"
    $legacy = "/storage/emulated/0/Forge"
    $obbExists = (& $Adb shell "if [ -d '$obb' ]; then echo yes; fi" 2>$null | Out-String).Trim()
    if ($obbExists -eq "yes") { return $obb }
    $legacyExists = (& $Adb shell "if [ -d '$legacy' ]; then echo yes; fi" 2>$null | Out-String).Trim()
    if ($legacyExists -eq "yes") { return $legacy }
    # Prefer OBB path on modern devices (matches Main.java for API > Q).
    return $obb
}

function Test-ForgeResNewerThanAssets {
    param(
        [Parameter(Mandatory = $true)][string]$ResRoot,
        [Parameter(Mandatory = $true)][string]$AssetsZip
    )
    if (-not (Test-Path $AssetsZip)) { return $true }
    $zipTime = (Get-Item $AssetsZip).LastWriteTimeUtc
    # Sample common hot paths first; fall back to a shallow top-level check.
    $hot = @(
        (Join-Path $ResRoot "languages"),
        (Join-Path $ResRoot "adventure"),
        (Join-Path $ResRoot "editions"),
        (Join-Path $ResRoot "lists"),
        (Join-Path $ResRoot "skins")
    )
    foreach ($dir in $hot) {
        if (-not (Test-Path $dir)) { continue }
        $newer = Get-ChildItem $dir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -gt $zipTime } |
            Select-Object -First 1
        if ($newer) { return $true }
    }
    $topNewer = Get-ChildItem $ResRoot -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -gt $zipTime } |
        Select-Object -First 1
    return [bool]$topNewer
}

# Android's toybox unzip rejects ZIP entries whose names contain non-ASCII bytes
# unless the language-encoding (UTF-8) general-purpose bit is set. Windows Ant
# without encoding="UTF-8" stores CP1252 names with that bit clear.
function Test-ForgeAssetsZipAndroidCompatible {
    param([Parameter(Mandatory = $true)][string]$AssetsZip)
    if (-not (Test-Path $AssetsZip)) { return $false }
    $fs = [IO.File]::OpenRead($AssetsZip)
    $br = New-Object IO.BinaryReader($fs)
    try {
        while ($fs.Position -lt $fs.Length - 4) {
            $sig = $br.ReadUInt32()
            if ($sig -eq 0x02014b50) {
                $null = $br.ReadUInt16(); $null = $br.ReadUInt16()
                $gp = $br.ReadUInt16()
                $null = $br.ReadUInt16(); $null = $br.ReadUInt16(); $null = $br.ReadUInt16()
                $null = $br.ReadUInt32(); $null = $br.ReadUInt32(); $null = $br.ReadUInt32()
                $nlen = $br.ReadUInt16(); $elen = $br.ReadUInt16(); $clen = $br.ReadUInt16()
                $null = $br.ReadUInt16(); $null = $br.ReadUInt16(); $null = $br.ReadUInt32(); $null = $br.ReadUInt32()
                $nameBytes = $br.ReadBytes($nlen)
                $null = $br.ReadBytes($elen + $clen)
                $utf8 = ($gp -band 0x800) -ne 0
                if (-not $utf8) {
                    foreach ($b in $nameBytes) {
                        if ($b -gt 127) { return $false }
                    }
                }
            } elseif ($sig -eq 0x04034b50) {
                $null = $br.ReadUInt16(); $null = $br.ReadUInt16(); $null = $br.ReadUInt16()
                $null = $br.ReadUInt16(); $null = $br.ReadUInt16(); $null = $br.ReadUInt32()
                $csize = $br.ReadUInt32(); $null = $br.ReadUInt32()
                $nlen = $br.ReadUInt16(); $elen = $br.ReadUInt16()
                $null = $br.ReadBytes($nlen + $elen)
                if ($csize -ne 0xFFFFFFFF) { $fs.Seek($csize, 'Current') | Out-Null }
            } elseif ($sig -eq 0x08074b50) {
                $null = $br.ReadBytes(12)
            } else {
                break
            }
        }
        return $true
    } catch {
        return $false
    } finally {
        $br.Close()
        $fs.Close()
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$AdbArgs
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    # Merge stderr and stringify so PowerShell does not surface NativeCommandError
    # for benign adb chatter ("Serving...", "All files should be loaded...").
    $lines = & $Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return @{ Code = $code; Output = ($lines -join "`n") }
}

function Build-ForgeAndroidAssetsZip {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [switch]$Force
    )
    Add-Type -AssemblyName System.IO.Compression | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

    $ResRoot = Join-Path $RepoRoot "forge-gui\res"
    $TargetDir = Join-Path $RepoRoot "forge-gui-android\target"
    $BuildTxt = Join-Path $TargetDir "classes\assets\build.txt"
    $AssetsZip = Join-Path $TargetDir "assets.zip"

    if (-not (Test-Path $ResRoot)) {
        throw "Missing resource tree: $ResRoot"
    }
    if (-not (Test-Path $BuildTxt)) {
        throw "Missing $BuildTxt. Build the APK first so build.txt is generated."
    }
    if (-not $Force -and -not (Test-ForgeResNewerThanAssets -ResRoot $ResRoot -AssetsZip $AssetsZip)) {
        Write-Host "assets.zip is up to date with forge-gui/res."
        return $AssetsZip
    }

    $Work = Join-Path $env:TEMP "forge-android-assets-pack"
    $Stage = Join-Path $Work "stage"
    Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $Stage "res\cardsfolder") | Out-Null

    $Fastest = [System.IO.Compression.CompressionLevel]::Fastest
    $Store = [System.IO.Compression.CompressionLevel]::NoCompression

    Write-Host "Packaging cardsfolder.zip..."
    $CardsZip = Join-Path $Stage "res\cardsfolder\cardsfolder.zip"
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        (Join-Path $ResRoot "cardsfolder"), $CardsZip, $Fastest, $false)

    Write-Host "Staging forge-gui/res (excluding cardsfolder)..."
    $files = Get-ChildItem $ResRoot -Recurse -File |
        Where-Object { $_.FullName -notlike "$ResRoot\cardsfolder\*" -and $_.Extension -ne ".xcf" }
    $zipPath = Join-Path $Work "assets.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

    # Build the outer zip with the same layout Maven produces: top-level docs + res/.
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $license = Join-Path $RepoRoot "forge-gui\LICENSE.txt"
        if (Test-Path $license) {
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $license, "LICENSE.txt", $Fastest)
        }
        foreach ($doc in @("CONTRIBUTORS.txt", "INSTALLATION.txt", "ISSUES.txt")) {
            $p = Join-Path $RepoRoot "forge-gui\release-files\$doc"
            if (Test-Path $p) {
                [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $p, $doc, $Fastest)
            }
        }

        $n = 0
        foreach ($f in $files) {
            $rel = "res/" + $f.FullName.Substring($ResRoot.Length + 1).Replace("\", "/")
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, $Fastest)
            $n++
            if ($n % 5000 -eq 0) { Write-Host ("  ... {0:N0} files" -f $n) }
        }
        # Stamp must match the APK's embedded build.txt so AssetsDownloader does not
        # treat local resources as foreign and force a snapshot re-download.
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $BuildTxt, "res/build.txt", $Fastest)
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $CardsZip, "res/cardsfolder/cardsfolder.zip", $Store)
    } finally {
        $zip.Dispose()
    }

    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    Move-Item -Force $zipPath $AssetsZip
    Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue

    $sizeMb = (Get-Item $AssetsZip).Length / 1MB
    Write-Host ("Wrote {0} ({1:N1} MB)" -f $AssetsZip, $sizeMb)
    return $AssetsZip
}

function Sync-ForgeAndroidAssets {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [switch]$Force
    )

    $TargetDir = Join-Path $RepoRoot "forge-gui-android\target"
    $AssetsZip = Join-Path $TargetDir "assets.zip"
    $LocalBuildTxt = Join-Path $TargetDir "classes\assets\build.txt"
    $LocalVersionTxt = Join-Path $TargetDir "classes\assets\version.txt"

    if (-not (Test-Path $LocalBuildTxt) -or -not (Test-Path $LocalVersionTxt)) {
        throw "Missing build.txt/version.txt under $TargetDir\classes\assets. Run the Android build first."
    }

    $ResRoot = Join-Path $RepoRoot "forge-gui\res"
    $rebuilt = $false
    $needsPackage = $Force -or -not (Test-Path $AssetsZip) -or
        (Test-ForgeResNewerThanAssets -ResRoot $ResRoot -AssetsZip $AssetsZip)
    if (-not $needsPackage -and -not (Test-ForgeAssetsZipAndroidCompatible -AssetsZip $AssetsZip)) {
        Write-Host "assets.zip has non-UTF-8 entry names (Android unzip would reject it); repackaging..."
        $needsPackage = $true
    }
    if ($needsPackage) {
        Write-Host "Packaging assets.zip from forge-gui/res..."
        Build-ForgeAndroidAssetsZip -RepoRoot $RepoRoot -Force | Out-Null
        $rebuilt = $true
    }

    if (-not (Test-Path $AssetsZip)) {
        throw "No assets.zip in $TargetDir after packaging."
    }
    if (-not (Test-ForgeAssetsZipAndroidCompatible -AssetsZip $AssetsZip)) {
        throw "Packaged assets.zip is still not Android-unzip compatible."
    }

    $localBuild = (Get-Content -Raw $LocalBuildTxt).Trim()
    $localVersion = (Get-Content -Raw $LocalVersionTxt).Trim()
    $zipInfo = Get-Item $AssetsZip
    # build.txt alone is not enough: editing forge-gui/res after an APK build keeps
    # the same stamp, so also fingerprint the zip we are about to deploy.
    $localStamp = "{0}|{1}|{2}" -f $localVersion, $localBuild, $zipInfo.Length

    $pkg = Get-ForgeAndroidPackageName -Adb $Adb
    $assetsDir = Get-ForgeAndroidAssetsDir -Adb $Adb -PackageName $pkg
    $deviceBuild = (Invoke-Adb -Adb $Adb -AdbArgs @("shell", "cat '$assetsDir/res/build.txt' 2>/dev/null")).Output.Trim()
    $deviceVersion = (Invoke-Adb -Adb $Adb -AdbArgs @("shell", "cat '$assetsDir/version.txt' 2>/dev/null")).Output.Trim()
    $deviceStamp = (Invoke-Adb -Adb $Adb -AdbArgs @("shell", "cat '$assetsDir/assets-sync.stamp' 2>/dev/null")).Output.Trim()

    if (-not $Force -and -not $rebuilt -and $deviceStamp -eq $localStamp -and
        $deviceBuild -eq $localBuild -and $deviceVersion -eq $localVersion) {
        Write-Host "Device assets already match this build ($localVersion / $localBuild)."
        return
    }

    Write-Host "Syncing assets to device..."
    Write-Host "  package : $pkg"
    Write-Host "  dir     : $assetsDir"
    Write-Host "  local   : $localStamp"
    Write-Host "  device  : $deviceStamp"

    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "am force-stop $pkg"))

    $remoteZip = "/sdcard/Download/forge-assets-sync.zip"
    $backup = "${assetsDir}_bak_sync"
    Write-Host "Pushing assets.zip..."
    $push = Invoke-Adb -Adb $Adb -AdbArgs @("push", $AssetsZip, $remoteZip)
    if ($push.Output) { Write-Host $push.Output }
    if ($push.Code -ne 0) {
        throw "adb push of assets.zip failed (exit $($push.Code))."
    }

    Write-Host "Extracting on device (this can take a minute)..."
    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "mkdir -p '$assetsDir'"))
    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "if [ -d '$assetsDir/res' ]; then rm -rf '$backup'; mv '$assetsDir/res' '$backup'; fi"))

    $unzip = Invoke-Adb -Adb $Adb -AdbArgs @("shell", "unzip -q -o '$remoteZip' -d '$assetsDir'; echo UNZIP_EXIT=`$?")
    if ($unzip.Output -notmatch "UNZIP_EXIT=0") {
        [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "rm -rf '$assetsDir/res'; if [ -d '$backup' ]; then mv '$backup' '$assetsDir/res'; fi"))
        throw "Device unzip failed. Output:`n$($unzip.Output)"
    }

    # Stamp version.txt so AssetsDownloader skips the master snapshot download.
    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "printf '%s' '$localVersion' > '$assetsDir/version.txt'"))
    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "printf '%s' '$localStamp' > '$assetsDir/assets-sync.stamp'"))
    [void](Invoke-Adb -Adb $Adb -AdbArgs @("shell", "rm -f '$remoteZip'; rm -rf '$backup'"))

    $verifyBuild = (Invoke-Adb -Adb $Adb -AdbArgs @("shell", "cat '$assetsDir/res/build.txt'")).Output.Trim()
    $verifyVersion = (Invoke-Adb -Adb $Adb -AdbArgs @("shell", "cat '$assetsDir/version.txt'")).Output.Trim()
    if ($verifyBuild -ne $localBuild -or $verifyVersion -ne $localVersion) {
        throw "Post-sync mismatch. Expected '$localVersion' / '$localBuild', got '$verifyVersion' / '$verifyBuild'."
    }
    Write-Host "Device assets updated ($verifyVersion / $verifyBuild)."
}
