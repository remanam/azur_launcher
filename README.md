# Azur Launcher

Minecraft launcher for the Azur Launcher modpack. Fork of Azur (GPL-3.0), see `NOTICE`.

Status: stage 1 of the plan in `CLAUDE.md` (no Chinese, RU/EN interface, platform app directories, unneeded code removed).
Pack sync, pinned NeoForge install, signed server list and the release/debug split come in the next stages.

## What it does now
- Microsoft sign-in (device code) with a licence check; only licensed Java Edition accounts can play.
- Installs a vanilla version or NeoForge (official installer) and launches it with a per-version game folder.
- Settings: memory, Java runtime, download threads, JVM arguments, language (ru/en/system), theme.

## Build
JDK 21 and Maven 3.9.x. Run `mvn package` (tests included). The jar does not contain JavaFX; run it with
`mvn javafx:run`. Release packaging (jlink/jpackage) is a later stage.

## Data folders
Windows `%APPDATA%\AzurLauncher`, macOS `~/Library/Application Support/AzurLauncher`,
Linux `~/.local/share/azur-launcher` (or `$XDG_DATA_HOME/azur-launcher`). The system property `-Dazur.home=<dir>`
overrides the root. All paths come from `config/AppDirs.java`.

## Языки
Интерфейс: русский (по умолчанию) и английский, строки в `src/main/resources/messages_ru.properties` и
`messages_en.properties`. Язык выбирается в настройках; при значении «как в системе» берётся язык ОС.

## Licence
GPL-3.0, see `LICENSE`. Anyone who receives a binary must be able to get the corresponding source.
