#!/bin/sh
# Builds the installer packages of the RELEASE launcher with jpackage (JDK 21) from the staged input.
# Normally started by packaging/release.sh, which stages build/dist/input (the fat jar; the pack client is inside it).
# Usage: packaging/jpackage.sh <version> [type ...]      types: app-image dmg pkg deb rpm (default by OS)
# Windows: packaging/jpackage.ps1.  Output: build/dist/out.  Packages are UNSIGNED (signing is a separate maintainer step).
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

IN=build/dist/input
OUT=build/dist/out
[ -f "$IN/azur-launcher.jar" ] || { echo "Missing $IN/azur-launcher.jar: run packaging/release.sh (or ./gradlew releaseJar)" >&2; exit 2; }
if ls "$IN" | grep -qi debug; then echo "A debug artifact is in $IN: refusing to package" >&2; exit 3; fi

# licence texts next to the jars (the app image has no licence option; installers show LICENSE in their wizard)
cp LICENSE "$IN/LICENSE.txt"
cp NOTICE "$IN/NOTICE.txt"

rm -rf "$OUT"
mkdir -p "$OUT"

ICON=""
if [ "$OS" = darwin ] && command -v iconutil >/dev/null 2>&1; then
  SET=build/dist/icon.iconset
  rm -rf "$SET"; mkdir -p "$SET"
  for s in 16 32 64 128 256; do sips -z $s $s src/main/resources/img/logo.png --out "$SET/icon_${s}x${s}.png" >/dev/null; done
  iconutil -c icns "$SET" -o build/dist/icon.icns && ICON="--icon build/dist/icon.icns"
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
