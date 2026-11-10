<#
 Windows packaging of the RELEASE launcher. Builds the jar itself, then runs jpackage. NOT yet run on Windows by its author:
 first try -Type app-image (needs no WiX). Instructions: docs/plans/LAUNCHER_WINDOWS_BUILD.md.
 Usage (from anywhere):  powershell -ExecutionPolicy Bypass -File packaging\jpackage.ps1 [-Type msi|exe|app-image|all] [-Version 1.0.13]
 Production keys (PUBLIC, not secret): -ServersPubKeyFile servers.pub -RootKeysFile root_keys.txt, or the environment
 variables AZUR_SERVERS_PUBKEY_FILE / AZUR_ROOT_KEYS_FILE, or files servers.pub / root_keys.txt next to this script.
 Without them the build uses the TEST key: it will not accept the signed server list of the VPS. Installers are UNSIGNED.
#>
param(
  [ValidateSet("msi", "exe", "app-image", "all")][string]$Type = "all",
  [string]$Version = "",
  [string]$ServersPubKeyFile = $env:AZUR_SERVERS_PUBKEY_FILE,
  [string]$RootKeysFile = $env:AZUR_ROOT_KEYS_FILE,
  [switch]$AllowNoRoots
)
$ErrorActionPreference = "Stop"
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

# --- version from build.gradle (single source of truth) ---
if (-not $Version) {
  $m = Select-String -Path "build.gradle" -Pattern "^version\s*=\s*'([^']+)'" | Select-Object -First 1
  if (-not $m) { Fail "cannot read version from build.gradle; pass -Version" }
  $Version = $m.Matches[0].Groups[1].Value
}

# --- environment checks ---
if (-not (Test-Path "gradlew.bat")) { Fail "gradlew.bat not found in $root (copy the whole azur_launcher folder)" }
$jp = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jp) { Fail "jpackage not found. Install JDK 21 (Temurin) and make sure its bin folder is in PATH (a JRE has no jpackage)." }
$jv = (& java -version 2>&1 | Out-String)
if ($jv -notmatch 'version "21') { Fail "JDK 21 is required, java reports: $($jv.Split("`n")[0]). Set JAVA_HOME to JDK 21 and put %JAVA_HOME%\bin first in PATH." }
$types = if ($Type -eq "all") { @("msi", "exe") } else { @($Type) }
if ($types -contains "msi" -or $types -contains "exe") {
  foreach ($tool in "candle.exe", "light.exe") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
      Fail "$tool (WiX Toolset 3.x) not found in PATH. Install WiX 3.14 (wixtoolset.org), reopen the terminal, or use -Type app-image which needs no WiX."
    }
  }
}

# --- production keys (public); the tracked resources are restored at the end ---
if (-not $ServersPubKeyFile) { $c = Join-Path $PSScriptRoot "servers.pub"; if (Test-Path $c) { $ServersPubKeyFile = $c } }
if (-not $RootKeysFile) { $c = Join-Path $PSScriptRoot "root_keys.txt"; if (Test-Path $c) { $RootKeysFile = $c } }
$pubRes = "src\main\resources\servers_pubkey.txt"; $rootRes = "src\main\resources\root_keys.txt"
$backups = @{}
function Swap($res, $src) { $backups[$res] = [IO.File]::ReadAllText((Resolve-Path $res)); [IO.File]::WriteAllText((Resolve-Path $res), $src) }
try {
  if ($ServersPubKeyFile) {
    if (-not (Test-Path $ServersPubKeyFile)) { Fail "public key file not found: $ServersPubKeyFile" }
    $key = (Get-Content $ServersPubKeyFile -Raw) -replace "\s", ""
    if ($key.Length -ne 44) { Fail "servers public key must be 44 base64 characters" }
    Swap $pubRes ($key + "`n")
    Write-Host "Embedding the production servers public key from $ServersPubKeyFile"
  } else {
    Write-Host "WARNING: no servers public key: building with the TEST key (will not accept the VPS server list)." -ForegroundColor Yellow
  }
  if ($RootKeysFile) {
    if (-not (Test-Path $RootKeysFile)) { Fail "root keys file not found: $RootKeysFile" }
    $lines = Get-Content $RootKeysFile | Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") }
    if ($lines.Count -lt 2) { Fail "root keys file needs at least 2 root lines (working + emergency)" }
    Swap $rootRes (($lines -join "`n") + "`n")
    Write-Host "Embedding $($lines.Count) root public keys from $RootKeysFile"
  } elseif (-not $AllowNoRoots) {
    Write-Host "WARNING: no root keys file: building WITHOUT roots (a test build; key rotation will not work)." -ForegroundColor Yellow
  }

  # --- build the jar ---
  & .\gradlew.bat --offline --quiet clean releaseJar
  if ($LASTEXITCODE -ne 0) { & .\gradlew.bat --quiet clean releaseJar }
  if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (see output above)" }
} finally {
  foreach ($k in $backups.Keys) { [IO.File]::WriteAllText((Resolve-Path $k), $backups[$k]) }
}

$in = "build\dist\input"; $out = "build\dist\out"
Remove-Item -Recurse -Force "build\dist" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $in, $out | Out-Null
$jar = Get-ChildItem "build\libs\azur-launcher-all.jar" -ErrorAction SilentlyContinue
if (-not $jar) { Fail "build\libs\azur-launcher-all.jar was not produced" }
Copy-Item $jar.FullName "$in\azur-launcher.jar"
Copy-Item LICENSE "$in\LICENSE.txt"; Copy-Item NOTICE "$in\NOTICE.txt"

# --- jpackage ---
foreach ($t in $types) {
  Write-Host "jpackage $t ..."
  $jpArgs = @("--type", $t, "--name", "Azur Launcher", "--app-version", $Version, "--vendor", "Azur Launcher",
    "--copyright", "GPL-3.0", "--description", "Azur Launcher", "--input", $in, "--main-jar", "azur-launcher.jar",
    "--main-class", "com.azur.launcher.Launcher", "--java-options", '-Dazur.app.dir=$APPDIR', "--dest", $out)
  if ($t -ne "app-image") {
    $jpArgs += @("--license-file", "LICENSE", "--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install")
  }
  & jpackage @args
  if ($LASTEXITCODE -ne 0) { Fail "jpackage ($t) failed" }
}
Write-Host ""
Write-Host "Done, version $Version. Result:" -ForegroundColor Green
Get-ChildItem $out | ForEach-Object { Write-Host "  $($_.FullName)" }
if ($Type -eq "app-image") { Write-Host "Run: $out\Azur Launcher\Azur Launcher.exe" }
