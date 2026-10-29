# Windows installer (.msi and .exe) of the RELEASE launcher. Not run in CI by the agent that wrote it: untested on Windows.
# Needs: JDK 21 (jpackage), WiX Toolset 3.x on PATH (for msi/exe), tools/updater/dist-bin built (tools/updater/build.sh).
# Usage (from azur_launcher): mvn -Pdist package   (stages target\dist\input), then
#        powershell -File packaging\jpackage.ps1 -Version 1.0.0
# The installers are UNSIGNED. Code signing with the owner's Windows certificate is a separate step (docs/RELEASE.md).
param([string]$Version = "1.0.0", [string[]]$Types = @("msi", "exe"))
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$in = "target\dist\input"; $out = "target\dist\out"
if (-not (Test-Path "$in\azur-launcher.jar")) { throw "Missing $in\azur-launcher.jar: run mvn -Pdist package" }
if (Get-ChildItem $in | Where-Object { $_.Name -match "debug" }) { throw "A debug artifact is in $in" }
New-Item -ItemType Directory -Force "$in\updater" | Out-Null
Copy-Item "target\dist\updater-src\azur-updater-windows-*" "$in\updater\" -ErrorAction SilentlyContinue
if (-not (Test-Path "$in\updater\azur-updater-windows-amd64.exe")) { Write-Warning "azur-updater.exe is missing: packs cannot be updated" }
Copy-Item LICENSE "$in\LICENSE.txt"; Copy-Item NOTICE "$in\NOTICE.txt"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($type in $Types) {
  jpackage --type $type --name "Azur Launcher" --app-version $Version --vendor "Azur Launcher" `
    --copyright "GPL-3.0" --description "Azur Launcher" --license-file LICENSE --input $in `
    --main-jar azur-launcher.jar --main-class com.azur.launcher.Launcher `
    --java-options '-Dazur.app.dir=$APPDIR' --dest $out `
    --win-menu --win-shortcut --win-dir-chooser --win-per-user-install
}
