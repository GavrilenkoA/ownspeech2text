# source me: common helpers for the emulator spike runs
export ANDROID_HOME=${ANDROID_HOME:-/home/private/.local/share/android-sdk}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5560}
A="$ANDROID_HOME/platform-tools/adb"
EVD="${EVD_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/evidence}"
cmd() { local c="$1"; shift; "$A" shell am broadcast -a app.murmur.spike.CMD --es c "$c" "$@" >/dev/null; }
evlog() { "$A" logcat -d -s MurmurSpike:I MurmurTarget:I | grep -E " (EV|TGT) " | grep -v ime_selection | sed -E 's/^.{18}//'; }
save() { evlog > "$EVD/$1.log"; cat "$EVD/$1.log"; }
bubble_xy() { "$A" logcat -c; cmd where; sleep 1; "$A" logcat -d -s MurmurSpike | grep bubble_where | tail -1 | sed -E 's/.*"x":([0-9]+),"y":([0-9]+).*/\1 \2/'; }
