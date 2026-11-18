#!/usr/bin/env bash
# Build sherpa-onnx's JNI library for Murmur's :asr process (ADR-006, ADR-008; docs/research/verify/asr-ocr.md A1).
#
#   tools/heavy native/sherpa/build-android.sh [arm64|x86_64|all] [workdir]   build, check, install
#   native/sherpa/build-android.sh verify                                     check the install dir only
#
#   arm64-v8a : CPU + QNN (HTP) code paths (QNN headers 2.40, libraries dlopen()ed at run time)
#   x86_64    : CPU only (emulator, debug builds)
#
# Inputs (all public, all pinned by sha256; no account, no token):
#   sherpa-onnx v2.0.1 = commit af2d8d81 (GitHub source archive)
#   QNN headers 2.40.0.251030 (sherpa-onnx "asr-models-qnn" release asset; headers only, nothing Qualcomm is linked)
#   onnxruntime-android 1.32.0 AAR (Maven Central). It is the ONE libonnxruntime.so of the APK: :infer:asr depends on the
#   same AAR, so sherpa-onnx, Silero VAD and OCR share it. Pass ORT_AAR=<path> to use a local copy (same sha256 required).
#   Android NDK r30 + CMake 4.1.2 from $ANDROID_HOME.
#
# Outputs:
#   <workdir>/out/<abi>/{lib,bin}   full sherpa install (CLI binaries + libonnxruntime.so for tools/device/asr_npu_smoke.sh)
#   <workdir>/out/<abi>/BUILD_INFO  versions, sha256 of the JNI library, build seconds
#   <libs>/sherpa/<abi>/libsherpa-onnx-jni.so  ONLY this file; <libs> = $MURMUR_NATIVE_LIBS_DIR or <repo>/.cache/native/jniLibs
#     (Gradle: -Pmurmur.nativeLibsDir=<libs>). The script refuses an install dir that holds libonnxruntime.so or the
#     sherpa C/C++ API libraries: a second libonnxruntime.so would clash with the AAR's in the APK.
#
# Checks (fail = exit != 0): source/AAR/header sha256; out/<abi>/lib/libonnxruntime.so byte-identical (cmp) to the AAR's;
#   libsherpa-onnx-jni.so NEEDED = libonnxruntime.so + Android system libraries only; arm64 contains the QNN code
#   (QnnInterface_getProviders), x86_64 does not need it; install dir contents.
# Heavy: run through tools/heavy; JOBS (default 6) compile threads.
set -euo pipefail

SHERPA_VERSION=2.0.1
SHERPA_COMMIT=af2d8d81dbfa61c4cea4c568da78f3982edca005
SHERPA_SHA256=afcafe3c34bc2f15a363ad791fa1644a69bf0977a58c1b4f617468976c2faeb5
SHERPA_URL=https://github.com/k2-fsa/sherpa-onnx/archive/$SHERPA_COMMIT.tar.gz
QNN_HDR_VER=2.40.0.251030
QNN_HDR_SHA256=fafe1c9dc3f60393134f22f2c708a9ede287492fa20dedc22c9fabdcf5ebe70d
QNN_HDR_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-$QNN_HDR_VER.tar.bz2
ORT_VER=1.32.0
ORT_AAR_SHA256=8e8dbb412ed4939675a157702f0c1e3621b436a645af7f7244536ac20bfad628
ORT_AAR_URL=https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VER/onnxruntime-android-$ORT_VER.aar
JOBS=${JOBS:-6}

what=${1:-all}
root=$(cd "$(dirname "$0")/../.." && pwd)
work=${2:-$root/.cache/native/build/sherpa}
libs=${MURMUR_NATIVE_LIBS_DIR:-$root/.cache/native/jniLibs}

SDK=${ANDROID_HOME:-/home/private/.local/share/android-sdk}
export ANDROID_NDK=${ANDROID_NDK:-$SDK/ndk/30.0.16248370}
READELF=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
STRINGS=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strings

die() { echo "build-android.sh: $*" >&2; exit 1; }
abi_of() { case $1 in arm64) echo arm64-v8a ;; x86_64) echo x86_64 ;; *) die "unknown target $1" ;; esac; }

# The install dir of one ABI may hold libsherpa-onnx-jni.so and nothing else that is a shared library.
check_install_dir() {
  local d=$1 bad
  [ -d "$d" ] || return 0
  bad=$(find "$d" -maxdepth 1 -name '*.so' ! -name libsherpa-onnx-jni.so -printf '%f ')
  [ -z "$bad" ] || die "refusing $d: it holds $bad(only libsherpa-onnx-jni.so belongs there; libonnxruntime.so comes from the AAR)"
}

if [ "$what" = verify ]; then
  for abi in arm64-v8a x86_64; do check_install_dir "$libs/sherpa/$abi"; done
  echo "install dir OK: $libs/sherpa"
  exit 0
fi

case $what in all) targets="arm64 x86_64" ;; arm64|x86_64) targets=$what ;; *) die "usage: $0 [arm64|x86_64|all|verify] [workdir]" ;; esac
# Refuse a polluted install dir before spending minutes on a build.
for t in $targets; do check_install_dir "$libs/sherpa/$(abi_of "$t")"; done

fetch() { # url sha256 dest
  local url=$1 sum=$2 dest=$3
  if [ ! -f "$dest" ] || ! echo "$sum  $dest" | sha256sum -c --status; then
    rm -f "$dest.part"
    curl -4 -fsSL --retry 3 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  echo "$sum  $dest" | sha256sum -c --status || die "sha256 mismatch: $dest"
}

mkdir -p "$work/dl"
fetch "$SHERPA_URL" "$SHERPA_SHA256" "$work/dl/sherpa-onnx-$SHERPA_COMMIT.tar.gz"
if [ -n "${ORT_AAR:-}" ]; then
  echo "$ORT_AAR_SHA256  $ORT_AAR" | sha256sum -c --status || die "ORT_AAR is not onnxruntime-android $ORT_VER (sha256)"
  aar=$ORT_AAR
else
  aar=$work/dl/onnxruntime-android-$ORT_VER.aar
  fetch "$ORT_AAR_URL" "$ORT_AAR_SHA256" "$aar"
fi

src=$work/sherpa-onnx-$SHERPA_COMMIT
if [ ! -d "$src" ]; then
  tar -C "$work" -xzf "$work/dl/sherpa-onnx-$SHERPA_COMMIT.tar.gz"
  cd "$src"
  # make -jN instead of the hard-coded -j4; CMake 4 refuses cmake_minimum_required(<3.5) in some FetchContent deps
  sed -i "s/^make -j4$/make -j${JOBS}/" build-android-arm64-v8a.sh build-android-x86-64.sh
  sed -i 's|^cmake -DCMAKE_TOOLCHAIN_FILE|cmake -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_TOOLCHAIN_FILE|' \
    build-android-arm64-v8a.sh build-android-x86-64.sh
  # the x86_64 script hard-codes android-21; ORT's libonnxruntime.so needs API >= 24 symbols (__write_chk@LIBC_N)
  sed -i 's/-DANDROID_PLATFORM=android-21 /-DANDROID_PLATFORM=android-26 /' build-android-x86-64.sh
fi
cd "$src"
grep -q "make -j${JOBS}" build-android-x86-64.sh || sed -i "s/^make -j[0-9]*$/make -j${JOBS}/" build-android-arm64-v8a.sh build-android-x86-64.sh

rm -rf "$work/ort-aar" && mkdir -p "$work/ort-aar" && unzip -q "$aar" 'headers/*' 'jni/*' -d "$work/ort-aar"
export PATH="$SDK/cmake/4.1.2/bin:$PATH"
export CMAKE_POLICY_VERSION_MINIMUM=3.5
export SHERPA_ONNX_ONNXRUNTIME_VERSION=$ORT_VER
export SHERPA_ONNX_ONNXRUNTIME_ROOT=$work/ort-aar
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_BINARY=ON   # CLI binaries for the phone smoke test (tools/device/asr_npu_smoke.sh)
export SHERPA_ONNX_ANDROID_PLATFORM=android-26

post_check() { # abi qnn
  local abi=$1 qnn=$2 out=$work/out/$1 needed extra
  cmp -s "$out/lib/libonnxruntime.so" "$work/ort-aar/jni/$abi/libonnxruntime.so" \
    || die "$abi: libonnxruntime.so differs from the onnxruntime-android $ORT_VER AAR"
  needed=$("$READELF" -d "$out/lib/libsherpa-onnx-jni.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
  echo "$needed" | grep -qw libonnxruntime.so || die "$abi: libsherpa-onnx-jni.so does not link libonnxruntime.so"
  extra=$(for n in $needed; do case $n in libonnxruntime.so|libandroid.so|liblog.so|libm.so|libdl.so|libc.so) ;; *) echo -n "$n " ;; esac; done)
  [ -z "$extra" ] || die "$abi: unexpected NEEDED $extra"
  if [ "$qnn" = ON ]; then
    "$STRINGS" "$out/lib/libsherpa-onnx-jni.so" | grep -q QnnInterface_getProviders || die "$abi: QNN code missing"
  fi
  echo "$abi NEEDED: $needed"
}

for t in $targets; do
  abi=$(abi_of "$t")
  t0=$(date +%s)
  if [ "$abi" = arm64-v8a ]; then
    qnn=ON
    if [ ! -d "$work/qnn-include-$QNN_HDR_VER" ]; then
      fetch "$QNN_HDR_URL" "$QNN_HDR_SHA256" "$work/dl/qnn-include-$QNN_HDR_VER.tar.bz2"
      tar -C "$work" -xjf "$work/dl/qnn-include-$QNN_HDR_VER.tar.bz2"
    fi
    export QNN_SDK_ROOT=$work/qnn-include-$QNN_HDR_VER
    SHERPA_ONNX_ENABLE_QNN=ON ./build-android-arm64-v8a.sh
    inst=build-android-arm64-v8a/install
  else
    qnn=OFF
    SHERPA_ONNX_ENABLE_QNN=OFF ./build-android-x86-64.sh
    inst=build-android-x86-64/install
  fi
  secs=$(( $(date +%s) - t0 ))
  rm -rf "$work/out/$abi" && mkdir -p "$work/out/$abi"
  cp -a "$inst/lib" "$inst/bin" "$work/out/$abi/"
  post_check "$abi" "$qnn"
  mkdir -p "$libs/sherpa/$abi"
  install -m 0644 "$work/out/$abi/lib/libsherpa-onnx-jni.so" "$libs/sherpa/$abi/libsherpa-onnx-jni.so"
  check_install_dir "$libs/sherpa/$abi"
  {
    echo "sherpa-onnx $SHERPA_VERSION $SHERPA_COMMIT"
    echo "onnxruntime-android $ORT_VER (AAR sha256 $ORT_AAR_SHA256)"
    if [ "$qnn" = ON ]; then echo "qnn ON (headers $QNN_HDR_VER)"; else echo "qnn OFF"; fi
    echo "ndk $(basename "$ANDROID_NDK"), jobs $JOBS, build seconds $secs"
    echo "libsherpa-onnx-jni.so sha256 $(sha256sum "$work/out/$abi/lib/libsherpa-onnx-jni.so" | cut -d' ' -f1)"
  } > "$work/out/$abi/BUILD_INFO"
  cat "$work/out/$abi/BUILD_INFO"
done
ls -la "$libs/sherpa"/*/
