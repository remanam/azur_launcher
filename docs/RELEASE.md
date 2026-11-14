# Release notes

## Build the jar
`./gradlew releaseJar` produces `build/libs/azur-launcher-all.jar` (about 11 MB): the launcher, Gson and JavaFX 21 with
natives for Windows x64, macOS arm64, macOS x64 and Linux x64 (`natives/<platform>/`; `FxNatives` extracts the set of the
running machine to the cache before JavaFX starts). It needs Java 21 installed: `java -jar azur-launcher-all.jar`.

## Native packages (optional, jpackage, bundle their own Java)
- macOS / Linux: `sh packaging/release.sh` (macOS: `.app` + `.dmg`; Linux: app-image + `.deb`). Output in `dist-release/`.
- Windows: `powershell -File packaging\jpackage.ps1` (WiX 3 for msi/exe; `-Type app-image` needs no WiX).
Packages are unsigned unless you sign them (Windows code-signing certificate, Apple Developer ID + notarization).

## Server list signing key
The launcher verifies `servers.json` (see `SERVERS_JSON.md`) with an Ed25519 public key embedded as
`src/main/resources/servers_pubkey.txt` (the repository ships a development key). A release build can embed its own public
key: `AZUR_SERVERS_PUBKEY_FILE=<file with the base64 public key> sh packaging/release.sh`. Never commit private keys.

## Licence
GPL-3.0 (`LICENSE`, `NOTICE`, `legal/THIRD_PARTY.txt` inside the jar, shown on the About page). Keep the source of exactly
what you ship available.
