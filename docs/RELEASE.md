# Owner guide: keys, client id, release

## 1. Azure client id (required for Microsoft sign-in)
portal.azure.com > Microsoft Entra ID > App registrations > New registration. Account type "Personal Microsoft accounts only", platform "Mobile and desktop" (public client), Authentication > "Allow public client flows" = Yes. No secret. Copy the Application (client) id.
Players do not type it: ship it with `accounts/auth-settings.json` or set it in the Accounts page of your own build; for a baked-in default add it to `AuthSettings` (stage 4). Minecraft services access for a new app may need Microsoft's approval form (aka.ms/AppRegInfo); until then sign-in answers "invalid app registration".

## 2. servers.json signing key
- Key file: `~/.azur-dist/signing.key` (base64 seed, same as `azur-dist`). Never put it in the repo or the jar.
- `azur-servers pubkey` prints the public key; put it into `src/main/resources/servers_pubkey.txt` (the embedded one is a DEV key) and rebuild.
- Sign: `azur-servers sign --in servers.json --out servers.signed.json --valid-days 30`; publish at an https URL players enter in Settings (or ship a default in `PackSettings`). Format: `SERVERS_JSON.md`. Optional per-server `neoforgeSha256` pins a NeoForge build other than the embedded one.

## 3. One build
There is a single jar for the owner and for players. It has a Microsoft sign-in and a plain "Offline account" (nickname only) for our offline-mode friends' server. `packaging/dev-servers.sh` only seeds a local signed server list for development. More: `DEBUG_BUILD.md`.

## 4. Release
**Primary: one cross-platform jar.** `packaging/release.sh` (or `./gradlew releaseJar`) copies the result to `launcher_builds/azur-launcher-beta.jar` and `dist-release/`; the jar is `build/libs/azur-launcher-all.jar` (about 11 MB): the launcher, Gson and JavaFX 21 with natives for Windows x64, macOS arm64, macOS x64 and Linux x64 (`natives/<platform>/`; `FxNatives` extracts the set of the running machine to the cache before JavaFX starts). Players only need **Java 21 installed** and run `java -jar azur-launcher-beta.jar` (double click works where `.jar` is associated with Java). Only the macOS arm64 run was tested; Windows and Linux are untested.

**Optional: native packages (jpackage, bundle their own Java).**
1. `cd tools/updater && ./build.sh` (binaries land in the ignored `dist-bin/`).
2. `cd azur_launcher && packaging/release.sh` (macOS: `Azur Launcher.app` + `.dmg`; Linux: app-image + `.deb`; types can be given as arguments). Everything lands in `dist-release/` (jar + packages); the script cleans only `target/` and `dist-release/`.
3. Windows (`.msi`/`.exe`): on Windows with WiX 3: `.\gradlew.bat releaseJar`, copy the jar to `build\dist\input\azur-launcher.jar`, then `powershell -File packaging\jpackage.ps1` (untested). Linux AppImage: run `appimagetool` over the app-image (not automated).
4. Not done, yours: Apple Developer ID signing + notarization (`--mac-sign`, `xcrun notarytool`), Windows code-signing certificate, public key replacement (step 2), client id (step 1). Unsigned macOS builds need right-click > Open.
Licences: GPL-3.0 (`LICENSE`, `NOTICE`, `legal/THIRD_PARTY.txt` inside the jar, shown in About with the written source offer). Keep the source of exactly what you ship available.
