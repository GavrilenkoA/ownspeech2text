#!/usr/bin/env bash
# Reproducible build of llama.cpp (pinned, see versions.env) + libmurmur_llm.so for Murmur.
#
#   native/llama/build_android.sh [variant ...]
#
# Variants (default: npu cpu-arm64 cpu-x86_64):
#   npu         arm64-v8a: CPU (all Android arm64 variants + KleidiAI) + Hexagon HTP (v73..v85 skels) + OpenCL
#   cpu-arm64   arm64-v8a: CPU only (no Qualcomm SDKs needed)
#   cpu-x86_64  x86_64 Android (emulator): CPU only
#   host        x86_64 Linux, host gcc: used for host measurements (README.md "Host checks")
#   jnilibs     only (re)install the APK set from already-built npu / cpu-arm64 / cpu-x86_64 outputs into
#               $MURMUR_NATIVE_LIBS_DIR/llama/<abi>/ (the -Pmurmur.nativeLibsDir layout :infer:llm packages)
# The APK set is always (re)installed at the end, also after building variants.
#
# Environment:
#   MURMUR_LLAMA_WORK  work dir (sources, SDKs, build trees). Default: <repo>/.cache/native-llama
#   MURMUR_LLAMA_OUT   output dir. Default: $MURMUR_LLAMA_WORK/out
#   ANDROID_HOME       Android SDK (default ~/.local/share/android-sdk)
#   JOBS               parallel jobs (default 6)
#   NO_HEAVY=1         do not wrap cmake in tools/heavy (e.g. inside docker)
#   MURMUR_NATIVE_LIBS_DIR  install root of the APK libs (= Gradle -Pmurmur.nativeLibsDir). Default: <repo>/.cache/native/jniLibs
#   HEXAGON_SDK_DIR    parent of an extracted Hexagon SDK <version>/ dir to reuse. Default: $MURMUR_LLAMA_WORK/hexagon-sdk
#   PROFILE            fold7 (default) or all, see assemble_jnilibs
#
# Output per variant: $OUT/<variant>/{lib,bin}, $OUT/<variant>/MANIFEST.txt (size, sha256, NEEDED, align),
# $OUT/<variant>/apk-libs.txt (the subset an APK ships). Needs network on first run (git, GitHub, KleidiAI).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
# shellcheck source=versions.env
source "$here/versions.env"

WORK="${MURMUR_LLAMA_WORK:-$repo/.cache/native-llama}"
OUT="${MURMUR_LLAMA_OUT:-$WORK/out}"
NATIVE_LIBS="${MURMUR_NATIVE_LIBS_DIR:-$repo/.cache/native/jniLibs}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CMAKE="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake"
NINJA="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/ninja"
JOBS="${JOBS:-6}"
HEAVY=("$repo/tools/heavy"); [[ "${NO_HEAVY:-0}" == 1 ]] && HEAVY=()
VARIANTS=("$@"); [[ ${#VARIANTS[@]} -eq 0 ]] && VARIANTS=(npu cpu-arm64 cpu-x86_64)

log() { printf '\033[1m[build_android] %s\033[0m\n' "$*" >&2; }
die() { printf '[build_android] ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "missing tool: $1"; }
need git; need curl; need python3; need sha256sum; need tar
[[ -x "$CMAKE" && -x "$NINJA" ]] || die "CMake $CMAKE_VERSION not found in $ANDROID_HOME/cmake (sdkmanager --install 'cmake;$CMAKE_VERSION')"
mkdir -p "$WORK/dl" "$OUT"

fetch() { # url sha256 dest
    local url="$1" sum="$2" dst="$3"
    if [[ -f "$dst" ]] && echo "$sum  $dst" | sha256sum -c --quiet 2>/dev/null; then return; fi
    log "download $(basename "$dst")"
    curl -fL --retry 3 -o "$dst.part" "$url"
    echo "$sum  $dst.part" | sha256sum -c --quiet || die "sha256 mismatch for $url"
    mv "$dst.part" "$dst"
}

# ---------------------------------------------------------------- llama.cpp source at the pinned commit
SRC="$WORK/llama.cpp"
if [[ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$LLAMA_COMMIT" ]]; then
    log "fetch llama.cpp $LLAMA_TAG ($LLAMA_COMMIT)"
    rm -rf "$SRC"; mkdir -p "$SRC"
    git -C "$SRC" init -q
    git -C "$SRC" remote add origin "$LLAMA_REPO"
    git -C "$SRC" fetch -q --depth 1 origin "$LLAMA_COMMIT"
    git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
# local patches (none at the pinned commit; keep the hook so bumps can carry fixes)
shopt -s nullglob
for p in "$here"/patches/*.patch; do
    if git -C "$SRC" apply --check "$p" 2>/dev/null; then log "apply $(basename "$p")"; git -C "$SRC" apply "$p";
    elif git -C "$SRC" apply --reverse --check "$p" 2>/dev/null; then :; else die "patch does not apply: $p"; fi
done
shopt -u nullglob

# ---------------------------------------------------------------- Qualcomm Hexagon SDK (npu only)
setup_hexagon() {
    local sdk_parent="${HEXAGON_SDK_DIR:-$WORK/hexagon-sdk}"
    HEXAGON_SDK_ROOT="$sdk_parent/$HEXAGON_SDK_VERSION"
    HEXAGON_TOOLS_ROOT="$HEXAGON_SDK_ROOT/tools/HEXAGON_Tools/$HEXAGON_TOOLS_VERSION"
    if [[ ! -f "$HEXAGON_SDK_ROOT/hexagon_sdk.json" ]]; then
        local tb="$WORK/dl/$(basename "$HEXAGON_SDK_URL")"
        fetch "$HEXAGON_SDK_URL" "$HEXAGON_SDK_SHA256" "$tb"
        log "extract Hexagon SDK (3.3 GB)"
        mkdir -p "$sdk_parent"
        "${HEAVY[@]}" tar -xJf "$tb" -C "$sdk_parent"
    fi
    [[ -d "$HEXAGON_TOOLS_ROOT" ]] || die "Hexagon tools not found at $HEXAGON_TOOLS_ROOT"
}

# ---------------------------------------------------------------- OpenCL headers + link stub (npu only)
setup_opencl() {
    OCL="$WORK/opencl/android-arm64"
    [[ -f "$OCL/lib/libOpenCL.so" ]] && return
    local base=https://github.com/KhronosGroup
    fetch "$base/OpenCL-Headers/archive/refs/tags/v$OPENCL_REL.tar.gz"    "$OPENCL_HEADERS_SHA256" "$WORK/dl/OpenCL-Headers-v$OPENCL_REL.tar.gz"
    fetch "$base/OpenCL-CLHPP/archive/refs/tags/v$OPENCL_REL.tar.gz"      "$OPENCL_CLHPP_SHA256"   "$WORK/dl/OpenCL-CLHPP-v$OPENCL_REL.tar.gz"
    fetch "$base/OpenCL-ICD-Loader/archive/refs/tags/v$OPENCL_REL.tar.gz" "$OPENCL_ICD_SHA256"     "$WORK/dl/OpenCL-ICD-Loader-v$OPENCL_REL.tar.gz"
    local t="$WORK/opencl/src"; rm -rf "$t"; mkdir -p "$t" "$OCL/include/CL" "$OCL/lib"
    for n in Headers CLHPP ICD-Loader; do tar -xzf "$WORK/dl/OpenCL-$n-v$OPENCL_REL.tar.gz" -C "$t"; done
    cp -r "$t/OpenCL-Headers-$OPENCL_REL/CL/." "$OCL/include/CL/"
    cp -r "$t/OpenCL-CLHPP-$OPENCL_REL/include/CL/." "$OCL/include/CL/"
    log "build OpenCL ICD loader (link stub) for arm64-v8a"
    "${HEAVY[@]}" "$CMAKE" -S "$t/OpenCL-ICD-Loader-$OPENCL_REL" -B "$t/build" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_STL=c++_shared \
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$OCL/include" -DBUILD_TESTING=OFF >/dev/null
    "${HEAVY[@]}" "$CMAKE" --build "$t/build" -j "$JOBS" >/dev/null
    cp "$t/build/libOpenCL.so" "$OCL/lib/"
}

# ---------------------------------------------------------------- one variant
build_variant() {
    local v="$1" abi args=() cflags="" bdir="$WORK/build/$1" targets=(murmur_llm murmur-llm-bench llama-bench)
    local common=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_BUILD_TYPE=Release
                  -DMURMUR_LLAMA_SRC="$SRC" -DMURMUR_BUILD_VARIANT="$1" -DBUILD_SHARED_LIBS=ON
                  -DLLAMA_BUILD_COMMON=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF
                  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_OPENSSL=OFF
                  -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=OFF -DGGML_OPENMP=OFF)
    local android=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_PLATFORM="$ANDROID_PLATFORM"
                   -DANDROID_STL=c++_shared -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON)
    [[ "$v" != host ]] && targets+=(murmur_llm_jni)     # the JNI shim of the :llm process (Android only)
    case "$v" in
        npu)
            abi=arm64-v8a; setup_hexagon; setup_opencl
            targets+=(htp-v73 htp-v75 htp-v79 htp-v81 htp-v85)   # DSP skels are ExternalProjects, not deps of ggml-hexagon
            # flags from upstream docs/backend/snapdragon/CMakeUserPresets.json minus -march (per-variant CPU libs set it)
            cflags="-fvectorize -ffp-model=fast -fno-finite-math-only -flto -D_GNU_SOURCE"
            args=("${android[@]}" -DANDROID_ABI=$abi -DGGML_CPU_KLEIDIAI=ON
                  -DGGML_HEXAGON=ON -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" -DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT"
                  -DPREBUILT_LIB_DIR=android_aarch64
                  -DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON
                  -DOpenCL_INCLUDE_DIR="$OCL/include" -DOpenCL_LIBRARY="$OCL/lib/libOpenCL.so") ;;
        cpu-arm64)
            abi=arm64-v8a; cflags="-fvectorize -ffp-model=fast -fno-finite-math-only -flto -D_GNU_SOURCE"
            args=("${android[@]}" -DANDROID_ABI=$abi -DGGML_CPU_KLEIDIAI=ON) ;;
        cpu-x86_64)
            abi=x86_64; cflags="-D_GNU_SOURCE"
            args=("${android[@]}" -DANDROID_ABI=$abi) ;;
        host)
            abi=host; cflags=""
            args=(-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++) ;;
        *) die "unknown variant $v" ;;
    esac
    log "configure $v ($abi)"
    local t0=$SECONDS
    "${HEAVY[@]}" "$CMAKE" -S "$here" -B "$bdir" "${common[@]}" "${args[@]}" \
        -DCMAKE_C_FLAGS="$cflags" -DCMAKE_CXX_FLAGS="$cflags" > "$WORK/build-$v.configure.log" 2>&1 \
        || { tail -40 "$WORK/build-$v.configure.log"; die "configure $v failed (log: $WORK/build-$v.configure.log)"; }
    local t1=$SECONDS
    log "build $v (-j$JOBS)"
    "${HEAVY[@]}" "$CMAKE" --build "$bdir" -j "$JOBS" --target "${targets[@]}" \
        > "$WORK/build-$v.build.log" 2>&1 || { tail -60 "$WORK/build-$v.build.log"; die "build $v failed (log: $WORK/build-$v.build.log)"; }
    local t2=$SECONDS
    package_variant "$v" "$abi" "$bdir"
    echo "configure_s=$((t1 - t0)) build_s=$((t2 - t1))" >> "$OUT/$v/MANIFEST.txt"
    log "$v done: configure $((t1 - t0)) s, build $((t2 - t1)) s -> $OUT/$v"
}

package_variant() {
    local v="$1" abi="$2" bdir="$3" o="$OUT/$1"
    rm -rf "$o"; mkdir -p "$o/lib" "$o/bin"
    local strip="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    local readelf="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    # shared objects: resolve symlinks, keep only the real soname file names Android can load (lib*.so)
    find "$bdir/bin" -maxdepth 1 -name 'lib*.so*' | while read -r f; do cp -L "$f" "$o/lib/$(basename "$f")"; done
    find "$bdir" -name 'libggml-htp-v*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$o/lib/" \;
    cp "$bdir/bin/murmur-llm-bench" "$bdir/bin/llama-bench" "$o/bin/"
    case "$abi" in
        arm64-v8a) cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$o/lib/" ;;
        x86_64)    cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so" "$o/lib/" ;;
    esac
    if [[ "$abi" != host ]]; then
        for f in "$o"/lib/*.so "$o"/bin/*; do
            [[ "$(basename "$f")" == libggml-htp-v* ]] && continue      # Hexagon DSP ELF, built stripped by the SDK
            "$strip" --strip-unneeded "$f"
        done
    fi
    {
        echo "variant=$v abi=$abi llama=$LLAMA_TAG/$LLAMA_COMMIT ndk=$NDK_VERSION built=$(date -u +%FT%TZ)"
        printf '%-44s %10s  %-16s %s\n' file bytes load_align needed
        for f in "$o"/lib/* "$o"/bin/*; do
            local al needed
            al=$("$readelf" -lW "$f" 2>/dev/null | awk '$1=="LOAD" && !f {print $NF; f=1}')
            needed=$("$readelf" -dW "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ',' | sed 's/,$//')
            printf '%-44s %10s  %-16s %s\n' "${f#$o/}" "$(stat -c %s "$f")" "${al:--}" "${needed:--}"
        done
        echo "sha256:"; (cd "$o" && sha256sum lib/* bin/*)
    } > "$o/MANIFEST.txt"
    # what an APK ships: everything in lib/ except the CLI-only helper libs (llama-bench)
    (cd "$o/lib" && ls | grep -v -E '^lib(llama-common|llama-bench-impl)\.so' ) > "$o/apk-libs.txt"
    local total; total=$(cd "$o/lib" && cat ../apk-libs.txt | xargs stat -c %s | awk '{s+=$1} END{print s}')
    echo "apk_libs_total_bytes=$total" >> "$o/MANIFEST.txt"
}

for v in "${VARIANTS[@]}"; do [[ "$v" == jnilibs ]] || build_variant "$v"; done

# ---------------------------------------------------------------- APK libs for the app
# Installs $NATIVE_LIBS/llama/<abi>/ (Gradle: -Pmurmur.nativeLibsDir=$NATIVE_LIBS, :infer:llm adds <dir>/llama as jniLibs).
# PROFILE=fold7 (default): arm64 CPU baseline + i8mm variant, HTP v79 only (SM8750); x86_64 baseline + AVX + AVX2.
# PROFILE=all: every CPU variant + HTP v73..v85. arm64 comes from the npu build, else from cpu-arm64.
# The manifest (sizes + sha256 of exactly what was installed) goes to $OUT/jniLibs-<abi>.txt, not into jniLibs.
install_abi() { # abi variant
    local abi="$1" v="$2" profile="${PROFILE:-fold7}" d="$NATIVE_LIBS/llama/$1"
    rm -rf "$d"; mkdir -p "$d"
    while read -r f; do
        if [[ "$profile" == fold7 ]]; then
            case "$abi" in
                arm64-v8a)
                    [[ "$f" == libggml-cpu-* && "$f" != libggml-cpu-android_armv8.0_1.so && "$f" != libggml-cpu-android_armv8.6_1.so ]] && continue
                    [[ "$f" == libggml-htp-v* && "$f" != libggml-htp-v79.so ]] && continue ;;
                x86_64)
                    # emulator only: baseline + AVX/F16C (ivybridge: the API 36 emulator's default virtual CPU, no AVX2/FMA)
                    # + AVX2 (haswell: emulators started with -qemu -cpu host, ADR-038)
                    [[ "$f" == libggml-cpu-* && "$f" != libggml-cpu-x64.so && "$f" != libggml-cpu-ivybridge.so && "$f" != libggml-cpu-haswell.so ]] && continue ;;
            esac
        fi
        cp "$OUT/$v/lib/$f" "$d/"
    done < "$OUT/$v/apk-libs.txt"
    [[ -f "$d/libmurmur_llm_jni.so" ]] || die "$v has no libmurmur_llm_jni.so (rebuild the variant)"
    {
        echo "abi=$abi from=$v profile=$profile llama=$LLAMA_TAG/$LLAMA_COMMIT installed=$(date -u +%FT%TZ) dir=$d"
        (cd "$d" && stat -c '%s %n' -- *.so | sort -k2)
        echo "total_bytes=$(cat "$d"/*.so | wc -c)"
        echo "sha256:"; (cd "$d" && sha256sum -- *.so | sort -k2)
    } > "$OUT/jniLibs-$abi.txt"
    log "installed $abi ($v, $profile): $(ls "$d" | wc -l) libs, $(cat "$d"/*.so | wc -c) bytes -> $d"
}
assemble_jnilibs() {
    if   [[ -f "$OUT/npu/apk-libs.txt" ]];       then install_abi arm64-v8a npu
    elif [[ -f "$OUT/cpu-arm64/apk-libs.txt" ]]; then install_abi arm64-v8a cpu-arm64; fi
    [[ -f "$OUT/cpu-x86_64/apk-libs.txt" ]] && install_abi x86_64 cpu-x86_64
    return 0
}
assemble_jnilibs
log "outputs:"; for v in "${VARIANTS[@]}"; do [[ -f "$OUT/$v/MANIFEST.txt" ]] && echo "  $OUT/$v  ($(grep apk_libs_total_bytes "$OUT/$v/MANIFEST.txt"))"; done
echo "  $NATIVE_LIBS/llama  (manifests: $OUT/jniLibs-<abi>.txt)"
