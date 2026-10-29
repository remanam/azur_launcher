#!/bin/sh
# Builds the installer packages of the RELEASE launcher with jpackage (JDK 21) from the staged input.
# Normally started by `mvn -Pdist package` (see docs/RELEASE.md); needs target/dist/input with the launcher jar and its
# runtime jars, and optionally target/dist/updater-src with the azur-updater binaries (tools/updater/build.sh).
# Usage: packaging/jpackage.sh <version> [type ...]      types: app-image dmg pkg deb rpm (default by OS)
# Windows: packaging/jpackage.ps1.  Output: target/dist/out.  Packages are UNSIGNED (signing is a separate owner step).
set -eu
cd "$(dirname "$0")/.."

VERSION=${1:?version missing}
shift
case "$(uname -s)" in
  Darwin) OS=darwin; DEFAULT_TYPES="app-image dmg" ;;
  Linux)  OS=linux;  DEFAULT_TYPES="app-image deb" ;;
  *) echo "Use packaging/jpackage.ps1 on Windows" >&2; exit 2 ;;
esac
TYPES=${*:-$DEFAULT_TYPES}

IN=target/dist/input
OUT=target/dist/out
[ -f "$IN/azur-launcher.jar" ] || { echo "Missing $IN/azur-launcher.jar: run mvn -Pdist package" >&2; exit 2; }
if ls "$IN" | grep -qi debug; then echo "A debug artifact is in $IN: refusing to package" >&2; exit 3; fi

# the updater binaries of this OS go next to the jars: $APPDIR/updater (UpdaterBinary looks there)
mkdir -p "$IN/updater"
FOUND=0
for f in target/dist/updater-src/azur-updater-"$OS"-*; do
  [ -f "$f" ] && cp "$f" "$IN/updater/" && FOUND=1
done
[ "$FOUND" = 1 ] || echo "WARNING: no azur-updater binary for $OS (run tools/updater/build.sh first); the package will not be able to update packs" >&2

# licence texts next to the jars (the app image has no licence option; installers show LICENSE in their wizard)
cp LICENSE "$IN/LICENSE.txt"
cp NOTICE "$IN/NOTICE.txt"

rm -rf "$OUT"
mkdir -p "$OUT"

ICON=""
if [ "$OS" = darwin ] && command -v iconutil >/dev/null 2>&1; then
  SET=target/dist/icon.iconset
  rm -rf "$SET"; mkdir -p "$SET"
  for s in 16 32 64 128 256; do sips -z $s $s src/main/resources/img/logo.png --out "$SET/icon_${s}x${s}.png" >/dev/null; done
  iconutil -c icns "$SET" -o target/dist/icon.icns && ICON="--icon target/dist/icon.icns"
elif [ "$OS" = linux ]; then
  ICON="--icon src/main/resources/img/logo.png"
fi

for TYPE in $TYPES; do
  LICENSE_OPT=""
  [ "$TYPE" = app-image ] || LICENSE_OPT="--license-file LICENSE"
  # shellcheck disable=SC2086
  jpackage --type "$TYPE" --name "Azur Launcher" --app-version "$VERSION" \
    --vendor "Azur Launcher" --copyright "GPL-3.0" --description "Azur Launcher" \
    $LICENSE_OPT --input "$IN" --main-jar azur-launcher.jar --main-class com.azur.launcher.Launcher \
    --java-options '-Dazur.app.dir=$APPDIR' --dest "$OUT" $ICON
done
ls -l "$OUT"
