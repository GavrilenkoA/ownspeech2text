#!/usr/bin/env bash
# Creates the two Murmur AVDs that approximate the Galaxy Z Fold7 displays.
#   murmur_fold7_cover : phone AVD, 1080x2520 (Fold7 cover panel, 21:9)
#   murmur_fold7_inner : generic "7.6in Foldable" (fold-in, hinge sensor, postures)
#                        resized to 1968x2184 (Fold7 inner panel, portrait)
# Densities are an approximation (see docs/dev/TOOLCHAIN.md); tune with `adb shell wm density`.
# Idempotent: re-running recreates both AVDs (their userdata is wiped).
set -euo pipefail

: "${ANDROID_HOME:=$HOME/.local/share/android-sdk}"
export ANDROID_HOME
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
# android-37.0 google_apis r8 and android-37.2 google_apis_ps16k r7 crash-loop surfaceflinger
# under the software renderer on this server (see TOOLCHAIN.md), so the default is API 36.
IMAGE="${MURMUR_SYSTEM_IMAGE:-system-images;android-36;google_apis;x86_64}"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

set_prop() { # file key value  -- replace or append key=value
  local f="$1" k="$2" v="$3"
  if grep -q "^${k}=" "$f"; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$f"
  else
    echo "${k}=${v}" >>"$f"
  fi
}

common_props() {
  local cfg="$1"
  set_prop "$cfg" hw.ramSize 3072M   # session cgroup is 8 GB; see TOOLCHAIN.md
  set_prop "$cfg" hw.cpu.ncore 4
  set_prop "$cfg" disk.dataPartition.size 4G
  set_prop "$cfg" hw.gpu.enabled yes
  set_prop "$cfg" hw.gpu.mode swiftshader             # headless server, no host GPU
  set_prop "$cfg" hw.keyboard yes
  set_prop "$cfg" hw.audioInput yes                   # dictation app: keep the virtual mic
  set_prop "$cfg" showDeviceFrame no
  set_prop "$cfg" fastboot.forceColdBoot no
}

create() { # name device
  echo no | "$AVDMANAGER" create avd --force -n "$1" -k "$IMAGE" -d "$2" >/dev/null
  echo "$AVD_HOME/$1.avd/config.ini"
}

# --- cover display -----------------------------------------------------------
cfg=$(create murmur_fold7_cover medium_phone)
common_props "$cfg"
set_prop "$cfg" hw.lcd.width 1080
set_prop "$cfg" hw.lcd.height 2520
set_prop "$cfg" hw.lcd.density 420          # -> ~411 dp wide (compact width class)

# --- inner (unfolded) display, real foldable definition ----------------------
cfg=$(create murmur_fold7_inner "7.6in Foldable")
common_props "$cfg"
set_prop "$cfg" hw.lcd.width 1968
set_prop "$cfg" hw.lcd.height 2184
set_prop "$cfg" hw.lcd.density 420          # -> ~750 dp wide (expanded width class)
# Folded posture shows the left half of the panel. This is the emulator's model of a
# fold-in device, NOT the Fold7's separate 1080x2520 cover panel -- use the cover AVD for that.
set_prop "$cfg" hw.displayRegion.0.1.width 984
set_prop "$cfg" hw.displayRegion.0.1.height 2184
set_prop "$cfg" hw.sensor.hinge.areas 984-0-1-2184

"$AVDMANAGER" list avd -c | grep '^murmur_'
