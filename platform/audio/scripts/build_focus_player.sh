#!/usr/bin/env bash
# Builds the focus-player test helper (a separate app with its own UID that takes the audio focus and plays) with the
# SDK tools alone: javac, d8, aapt2, zipalign, apksigner and a throwaway signing key. Test-only, never shipped.
# Usage: platform/audio/scripts/build_focus_player.sh <out-dir>    -> <out-dir>/focus-player.apk
set -euo pipefail
OUT="${1:?usage: build_focus_player.sh <out-dir>}"
: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
HERE="$(cd "$(dirname "$0")/focus-player" && pwd)"
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$OUT"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/classes" "$W/dex"
javac --release 17 -nowarn -cp "$JAR" -d "$W/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --min-api 34 --lib "$JAR" --output "$W/dex" $(find "$W/classes" -name '*.class')
"$BT/aapt2" link -o "$W/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" --version-code 1 --version-name 1
(cd "$W/dex" && zip -q -j "$W/base.apk" classes.dex)
"$BT/zipalign" -f 4 "$W/base.apk" "$W/aligned.apk"
keytool -genkeypair -keystore "$W/key.jks" -storepass helper -keypass helper -alias helper -keyalg RSA \
  -keysize 2048 -validity 1 -dname "CN=focus-player test helper" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$W/key.jks" --ks-pass pass:helper --out "$OUT/focus-player.apk" "$W/aligned.apk"
echo "$OUT/focus-player.apk"
