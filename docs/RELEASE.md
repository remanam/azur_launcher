# Owner guide: keys, client id, release

## 1. Azure client id (required for Microsoft sign-in)
portal.azure.com > Microsoft Entra ID > App registrations > New registration. Account type "Personal Microsoft accounts only", platform "Mobile and desktop" (public client), Authentication > "Allow public client flows" = Yes. No secret. Copy the Application (client) id.
Players do not type it: ship it with `accounts/auth-settings.json` or set it in the Accounts page of your own build; for a baked-in default add it to `AuthSettings` (stage 4). Minecraft services access for a new app may need Microsoft's approval form (aka.ms/AppRegInfo); until then sign-in answers "invalid app registration".

## 2. servers.json signing key
- Key file: `~/.azur-dist/signing.key` (base64 seed, same as `azur-dist`). Never put it in the repo or the jar.
- `azur-servers pubkey` prints the public key; put it into `src/main/resources/servers_pubkey.txt` (the embedded one is a DEV key) and rebuild.
- Sign: `azur-servers sign --in servers.json --out servers.signed.json --valid-days 30`; publish at an https URL players enter in Settings (or ship a default in `PackSettings`). Format: `SERVERS_JSON.md`. Optional per-server `neoforgeSha256` pins a NeoForge build other than the embedded one.

## 3. Debug (offline) build, for you only
`mvn -Pdebug clean package` -> `target/azur-launcher-debug.jar`. It has an account without a Microsoft check and a red banner. Run with the JavaFX module path. Do not distribute; `packaging/jpackage.sh` refuses inputs with a "debug" file.

## 4. Release
1. `cd tools/updater && ./build.sh` (binaries land in the ignored `dist-bin/`).
2. `cd azur_launcher && packaging/release.sh` (macOS: `Azur Launcher.app` + `.dmg`; Linux: app-image + `.deb`) in `target/dist/out`. The script runs tests, the release jar guard and the guard over the image (same jar, updater binaries clean, no debug files).
3. Windows (`.msi`/`.exe`): on Windows with WiX 3: `mvn -Pdist package` then `powershell -File packaging\jpackage.ps1` (untested). Linux AppImage: run `appimagetool` over the app-image (not automated).
4. Not done, yours: Apple Developer ID signing + notarization (`--mac-sign`, `xcrun notarytool`), Windows code-signing certificate, public key replacement (step 2), client id (step 1). Unsigned macOS builds need right-click > Open.
Licences: GPL-3.0 (`LICENSE`, `NOTICE`, `legal/THIRD_PARTY.txt` inside the jar, shown in About with the written source offer). Keep the source of exactly what you ship available.
