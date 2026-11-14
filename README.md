# Azur Launcher

A simple, free Minecraft launcher for playing with friends. One person runs the server and publishes a modpack, and
everyone else's launcher downloads the same mods and keeps them up to date, so nobody has to copy files by hand.
It installs Minecraft with NeoForge and starts the game with the right Java and settings.

Written in Java 21 and JavaFX. Free software under the GNU GPL v3.

**Downloads:** https://github.com/remanam/azur_launcher/releases

## What it can do
- Install a vanilla Minecraft version or NeoForge and run it, with a separate game folder for each profile.
- Sign in with a nickname only (for servers in offline mode) or through the website the launcher is configured for.
  Tokens are stored encrypted on your computer.
- Keep a modpack in sync: it downloads only the files that changed, checks signatures and hashes, and can roll back.
- Show a server list and news that are signed, and update itself from a signed manifest.
- Find or download a suitable Java runtime, and let you set memory, JVM arguments and download threads.
- Speak Russian and English, with a light and a dark theme.
- Send anonymous usage statistics, only if you agree; you can switch it off in Settings.

For safety it downloads only from official hosts (Mojang, NeoForged, Adoptium) and from the pack server you configure,
and always over https.

## What you need
- Windows x64, Linux x64, or macOS (Apple silicon or Intel). The one jar contains everything for all four;
  macOS on Apple silicon is the most tested.
- Java 21 to run it (the installers bring their own).

## Run it
```sh
java -jar azur-launcher-all.jar
```
Your data is kept in `%APPDATA%\AzurLauncher` on Windows, `~/Library/Application Support/AzurLauncher` on macOS and
`~/.local/share/azur-launcher` on Linux. Start with `-Dazur.home=<folder>` to keep it somewhere else.

## Build it yourself
You need JDK 21. Gradle is included, nothing else to install.

```sh
./gradlew test         # run the tests
./gradlew releaseJar   # build/libs/azur-launcher-all.jar
```
The first build downloads its libraries from Maven Central. Optional native installers: `sh packaging/release.sh`
(macOS, Linux) or `packaging/jpackage.ps1` (Windows); see `docs/RELEASE.md`.

## Settings for your own fork
- The server list address and the website are set in `config/PackSettings.java` and `BuiltinServers`;
  a user can also change the list address in Settings.
- Public keys that verify signed data are in `servers_pubkey.txt` and `root_keys.txt`. This repository contains only
  development keys; release builds use their own. Private keys must never be committed.
- How the signed formats work: `docs/SERVERS_JSON.md`, `docs/KEYSET.md`.

## Releases and signing
Releases are built from this repository. Installers are code-signed when the maintainer has a certificate; the
jar and the sources are always the reference.

## Credits
- Parts of the code come from earlier open-source work; its authors and licences are listed in `NOTICE`.
- OpenJFX (GPL-2.0 with Classpath Exception), Gson (Apache-2.0), JUnit 5 (EPL-2.0, tests only).
- Fonts Philosopher and PT Sans (SIL OFL 1.1).
- Full list: `src/main/resources/legal/THIRD_PARTY.txt`.

## Author and licence
Copyright (c) 2026 Erik Karamian (GitHub: [remanam](https://github.com/remanam)). Questions and bugs: open an issue here.

Licensed under the GNU General Public License v3.0, see `LICENSE`. Anyone who receives a binary can get the matching
source code from this repository.
