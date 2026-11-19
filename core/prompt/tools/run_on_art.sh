#!/usr/bin/env bash
# Run the :core:prompt golden suites on ART (Android's runtime: ICU-backed Character, regex and Normalizer) instead
# of the host JVM. The port's Python semantics must hold on the phone, not only on OpenJDK.
#
# Usage: core/prompt/tools/run_on_art.sh <adb-serial>      e.g. emulator-5566 (a booted API 34+ device or AVD)
#
# Dexes the module's main + test classes and their runtime classpath with d8, adds the packaged prompts and the
# goldens as jar resources, pushes prompts/ (the tests read prompts/examples and prompts/system from murmur.repoRoot)
# and runs JUnit through dalvikvm64. JIT is off: the standalone dalvikvm crashes in JIT-compiled kotlinx.serialization
# code on the x86_64 emulator; the interpreter uses the same libcore/ICU classes. RenderPerformanceTest is host-only
# (java.lang.management does not exist on Android).
set -euo pipefail
SERIAL="${1:?usage: run_on_art.sh <adb-serial>}"
root="$(cd "$(dirname "$0")/../../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
ADB=("$ANDROID_HOME/platform-tools/adb" -s "$SERIAL")
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)"
work="$root/.cache/spikes/wp-04/art"
rm -rf "$work" && mkdir -p "$work/res"

cp_line="$("$root/tools/gw" --max-workers=6 --no-configuration-cache -q \
  -I "$root/core/prompt/tools/art-classpath.init.gradle.kts" :core:prompt:testClasses :core:prompt:printTestRuntimeClasspath \
  | grep '^CLASSPATH=')"
jars=()
IFS=':' read -r -a entries <<< "${cp_line#CLASSPATH=}"
for e in "${entries[@]}"; do
  if [ -d "$e" ]; then
    case "$e" in
      */resources/*) cp -r "$e"/. "$work/res/" ;;
      *) if [ -n "$(ls -A "$e" 2>/dev/null)" ]; then j="$work/$(echo "$e" | md5sum | cut -c1-8).jar"; (cd "$e" && jar cf "$j" .); jars+=("$j"); fi ;;
    esac
  elif [ -f "$e" ]; then
    jars+=("$e")
  fi
done
"$BT/d8" --release --min-api 34 --lib "$PLATFORM/android.jar" --output "$work/art.jar" "${jars[@]}" 2> >(grep -vi '^warning' >&2)
(cd "$work/res" && zip -qr ../art.jar .)
(cd "$root" && tar cf "$work/prompts.tar" prompts/examples prompts/system prompts/profiles prompts/shots prompts/tones)

dev=/data/local/tmp/murmur-core-prompt
"${ADB[@]}" shell "rm -rf $dev && mkdir -p $dev"
"${ADB[@]}" push "$work/art.jar" "$work/prompts.tar" "$dev/" >/dev/null
"${ADB[@]}" shell "cd $dev && tar xf prompts.tar"
"${ADB[@]}" shell "cd $dev && dalvikvm64 -Xusejit:false -Xmx1g -Dmurmur.repoRoot=$dev -cp art.jar org.junit.runner.JUnitCore \
  app.murmur.core.prompt.GoldenCorpusTest app.murmur.core.prompt.RenderExtraTest app.murmur.core.prompt.GuardGoldenTest \
  app.murmur.core.prompt.PyTextTest app.murmur.core.prompt.PromptAssetsTest" | tee "$work/result.txt" | grep -v '^\s*at '
"${ADB[@]}" shell "rm -rf $dev"
grep -q '^OK (' "$work/result.txt"
