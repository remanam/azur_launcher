# Azur Launcher

Minecraft launcher for the Azur Launcher modpack. Licence and third-party notices: `LICENSE`, `NOTICE`.

Pack sync, pinned NeoForge install, a signed server list and a debug/release split are implemented; the details are in `docs/`.

## What it does now
- Nickname-only account for our offline-mode server (Microsoft sign-in removed 2026-10-30).
- Installs a vanilla version or NeoForge (official installer) and launches it with a per-version game folder.
- Settings: memory, Java runtime, download threads, JVM arguments, language (ru/en/system), theme.

## Build
JDK 21 and the Gradle wrapper (`./gradlew`, the same toolchain the mods use).

- Unit tests: `./gradlew test`.
- Local build with the offline test account (never distribute): `sh packaging/dev.sh` -> `launcher_builds/dev/`
  (`azur-launcher-dev.jar` plus `run.command` for macOS and `run.bat` for Windows). Details: `docs/DEBUG_BUILD.md`.
- Cross-platform jar for Windows/macOS/Linux: `./gradlew releaseJar` -> `build/libs/azur-launcher-all.jar`
  (JavaFX natives for win x64, mac arm64/x64, linux x64; needs Java 21 on the player machine).
- Installers: `sh packaging/release.sh` (jpackage; macOS/Linux; `docs/RELEASE.md`).

## Data folders
Windows `%APPDATA%\AzurLauncher`, macOS `~/Library/Application Support/AzurLauncher`,
Linux `~/.local/share/azur-launcher` (or `$XDG_DATA_HOME/azur-launcher`). The system property `-Dazur.home=<dir>`
overrides the root. All paths come from `config/AppDirs.java`.

## Языки
Интерфейс: русский (по умолчанию) и английский, строки в `src/main/resources/messages_ru.properties` и
`messages_en.properties`. Язык выбирается в настройках; при значении «как в системе» берётся язык ОС.

## Licence
GPL-3.0, see `LICENSE`. Anyone who receives a binary must be able to get the corresponding source.
