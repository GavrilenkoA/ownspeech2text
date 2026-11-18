# Murmur — build toolchain, emulator loop, and server setup

Status as of 2026-11-18. Everything marked **verified** was run on this build server during this session.
Sources are linked next to each claim. Anything not checked against a primary source is marked **UNVERIFIED**.

---

## 1. Decisions / Recommendation

| # | Decision | Why |
|---|---|---|
| D1 | **AGP 9.5.1 + Gradle 9.9.1 (wrapper) + Kotlin 2.4.21**, with AGP's **built-in Kotlin** (no `org.jetbrains.kotlin.android` plugin) | These are the latest stable releases. AGP 9 compiles Kotlin itself and refuses the old plugin. We pin KGP 2.4.21 on the root buildscript classpath because AGP 9.5.1 only pulls in KGP 2.2.10. Verified: `buildEnvironment` resolves `kotlin-gradle-plugin:2.2.10 -> 2.4.21`. |
| D2 | **Compose BOM 2026.09.00** (ui 1.12.1, material3 1.4.0) + Compose compiler plugin `org.jetbrains.kotlin.plugin.compose:2.4.21` | This is the latest BOM on Google Maven. The Compose compiler ships with Kotlin, so its version always equals the Kotlin version. Don't use `androidx.compose.compiler` (stuck at 1.5.15). |
| D3 | **compileSdk 37.2** (`release(37) { minorApiLevel = 2 }`), **targetSdk 37**, **minSdk 36** | 37.2 is the newest stable platform in the SDK repository. AGP 9.5 supports up to API 37. The Fold7 launched on Android 16 (API 36) and now has the Android 17 (API 37) update (§7). Verified: the boot classpath is `platforms/android-37.2/android.jar`. |
| D4 | **NDK r30 (30.0.16248370) + CMake 4.1.2**, both from the SDK, both pinned in the catalog | These are the latest stable releases. We don't rely on the host cmake/ninja (neither is installed). `.so` files are 16 KB page-aligned (verified with `llvm-readelf`: LOAD align `0x4000`). `zipalign -P 16` passes. |
| D5 | ABIs: **arm64-v8a** (phone) + **x86_64** (emulator only) | The x86_64 slice makes the debug APK about 2x larger. For a release build, drop x86_64 or use an ABI split. |
| D6 | **Emulator system image: `system-images;android-36;google_apis;x86_64` r7**, emulator **37.2.13 (stable)** | Both API 37 images crash-loop on this headless server (§8.1). API 36 is stable and matches the Fold7's launch OS. |
| D7 | Two AVDs: **`murmur_fold7_cover`** (1080x2520 @420) and **`murmur_fold7_inner`** (generic "7.6in Foldable", resized to 1968x2184 @420, hinge sensor) | No Samsung device definition ships with the SDK. The emulator's fold-in model takes the folded screen as a sub-region of the inner panel, so it can't reproduce the Fold7's separate 21:9 cover panel. That's why there are two AVDs. |
| D8 | Run at most **one emulator at a time**, with **3 GB guest RAM**. Gradle heap 3 GB, Kotlin daemon 1.5 GB | This agent session runs in a **systemd memory cgroup capped at 8 GB** (`memory.max = 8589934592`), not on the full 121 GB host. The first emulator attempt (4 GB guest + Gradle daemons) was OOM-killed. |

---

## 2. Exact versions (all verified 2026-11-18)

| Component | Version | Primary source |
|---|---|---|
| Android Gradle Plugin | **9.5.1** (9.6.0 is alpha only) | [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml), [AGP 9.5 release notes](https://developer.android.com/build/releases/gradle-plugin) |
| AGP 9.5 compatibility | Gradle min 9.6.0, Build-Tools min 36.0.0, default NDK 28.2.13676358, JDK 17+, max API 37 | [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) |
| Gradle | **9.9.1** (dist sha256 `d8a18a7a…ba228`, wrapper jar sha256 `621008af…cdaf8`, matched) | [services.gradle.org/versions/current](https://services.gradle.org/versions/current) |
| Kotlin (KGP + compiler) | **2.4.21** (2.5.0 is RC) | [Maven Central KGP metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml), [GitHub release v2.4.21, 2026-10-08](https://api.github.com/repos/JetBrains/kotlin/releases/latest) |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` **2.4.21** | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/maven-metadata.xml) |
| Compose BOM | **2026.09.00**: ui/runtime/foundation 1.12.1, material3 1.4.0 | [BOM metadata](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml), BOM POM, [Compose release page](https://developer.android.com/jetpack/androidx/releases/compose) |
| activity-compose / core-ktx / lifecycle | 1.13.0 / 1.19.1 / 2.11.0 | Google Maven `maven-metadata.xml` per artifact |
| Test libs | junit 4.13.2, androidx.test.ext:junit 1.3.0, androidx.test:runner 1.7.0 | Maven Central / Google Maven metadata |
| JDK | OpenJDK **21.0.13** (`/usr/bin/java`, Ubuntu package). Bytecode target is 17. | local |
| Platform (compileSdk) | **android-37.2** rev 2 (extension level 24). Stable channel. | [SDK repository2-4.xml](https://dl.google.com/android/repository/repository2-4.xml) |
| Build-Tools | **37.0.0** | same |
| NDK | **r30 = 30.0.16248370** (released 2026-09-08) | same + [android/ndk releases](https://github.com/android/ndk/releases) |
| CMake | **4.1.2** (bundles ninja) | same |
| Platform-tools | 37.0.1 (already installed, stable latest) | same |
| cmdline-tools | 12.0 installed; 23.0 is latest stable (not upgraded, see §9) | same |
| Emulator | **37.2.13** (stable). 37.3.9 is beta and 37.4.1 is canary. | same |
| System image | `android-36;google_apis;x86_64` **r7** | [sys-img2-4.xml](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-4.xml) |

All library and plugin versions live in `gradle/libs.versions.toml`, which is the single source of truth. SDK levels, NDK and CMake are there too.

---

## 3. Project skeleton (repo root)

```
settings.gradle.kts         repositories, include(":app")
build.gradle.kts            root: KGP 2.4.21 on buildscript classpath; AGP + compose plugin (apply false)
gradle.properties           JVM heaps sized for the 8 GB session cgroup, config cache + build cache on
gradle/libs.versions.toml   version catalog
gradle/wrapper/*, gradlew   Gradle 9.9.1 wrapper (sha256-pinned distribution)
.gitignore                  build outputs, local.properties, keystores, model weights (*.gguf/*.tflite/*.onnx/*.bin/*.safetensors), .cache/
app/build.gradle.kts        applicationId app.murmur, Compose, CMake, abiFilters arm64-v8a + x86_64
app/src/main/kotlin/app/murmur/{MainActivity,NativeBridge,Greeting}.kt
app/src/main/cpp/{CMakeLists.txt,murmur_native.cpp}   libmurmur_native.so (JNI hello + add)
app/src/test/kotlin/...GreetingTest.kt                JVM unit test
app/src/androidTest/kotlin/...MainActivityTest.kt     Compose UI test + JNI-on-device test
tools/emulator/{create_avds,boot,screenshot,stop}.sh  emulator loop
docs/dev/screenshots/fold7_cover.png, fold7_inner_unfolded.png   proof screenshots
```

Notes for implementers:
- **Don't** add `org.jetbrains.kotlin.android` or `kotlin-kapt`. Use KSP, or `com.android.legacy-kapt` if you must ([migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin)). Kotlin compiler options go in `kotlin { compilerOptions { … } }`. `jvmTarget` defaults to `compileOptions.targetCompatibility`.
- To bump Kotlin, change `kotlin` in the catalog. That moves both KGP and the Compose compiler.
- `local.properties` isn't committed. The build finds the SDK through `ANDROID_HOME`.
- The C++ STL is `c++_shared`, so `libc++_shared.so` gets packaged. Keep it that way once a second native lib arrives (LiteRT/QNN/llama.cpp etc.). Two static STLs in one process cause undefined behaviour.
- `useLegacyPackaging = false`: native libs are stored uncompressed and page-aligned in the APK, so they're mmapped in place. That matters once multi-MB runtime `.so`s arrive.

---

## 4. Commands

Environment, once per shell:

```bash
export ANDROID_HOME=/home/private/.local/share/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
cd /home/private/projects/murmur
```

### 4.1 Build and unit test (verified, including from a clean copy of the repo)
```bash
./gradlew assembleDebug testDebugUnitTest        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew --stop                                 # stop daemons when done (memory cap, see D8)
```
Timings on this server: about 65 s for the first build with a cold dependency cache, then 7–11 s from a clean checkout with warm caches. The clean-checkout check was done by copying only the tracked files to an empty directory and running `./gradlew --no-build-cache --no-configuration-cache clean assembleDebug testDebugUnitTest`. It passed.

Result checks used:
```bash
BT=$ANDROID_HOME/build-tools/37.0.0
$BT/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "^package|native-code|targetSdk"
$BT/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk | tail -1     # "Verification successful"
$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -lW \
  app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libmurmur_native.so | grep LOAD   # align 0x4000
```

### 4.2 Install SDK packages (what was run)
```bash
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-37.2" "build-tools;37.0.0" "ndk;30.0.16248370" \
  "cmake;4.1.2" "emulator" "system-images;android-36;google_apis;x86_64"
```

### 4.3 Emulator loop (verified)
```bash
tools/emulator/create_avds.sh                         # (re)creates murmur_fold7_cover + murmur_fold7_inner
tools/emulator/boot.sh murmur_fold7_cover 5560        # headless; returns when sys.boot_completed=1 (~40 s)
tools/emulator/screenshot.sh emulator-5560 docs/dev/screenshots/fold7_cover.png   # install -r, am start -W, screencap
tools/emulator/stop.sh emulator-5560                  # or no args: stops all emulators on ports 5560-5589
```
What the scripts do, so you can run the steps by hand:
- Boot: `emulator -avd <name> -port 5560 -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader -accel on`, wrapped in `sg kvm -c …` if the current shell doesn't have the `kvm` group yet (§6). The script then polls `adb -s emulator-5560 shell getprop sys.boot_completed` and turns off the three animation scales.
- Install: `adb -s emulator-5560 install -r -t app/build/outputs/apk/debug/app-debug.apk`
- Launch: `adb -s emulator-5560 shell am start -W -n app.murmur/.MainActivity`
- Screenshot: `adb -s emulator-5560 exec-out screencap -p > out.png`
- Stop: `adb -s emulator-5560 emu kill`

We use ports 5560+ to stay off 5554, in case other sessions of the same user run emulators. Always pass `-s <serial>` / `ANDROID_SERIAL`.

Verified results:
- Cover AVD: `wm size` 1080x2520, 420 dpi, Compose reports **411 dp** width, title "Murmur - cover screen", JNI string `native ok (x86_64, clang 21.0.0 …)`. See `docs/dev/screenshots/fold7_cover.png`.
- Inner AVD: 1968x2184, 420 dpi, **750 dp** width, "Murmur - unfolded". See `docs/dev/screenshots/fold7_inner_unfolded.png`.

### 4.4 Instrumented / UI tests headless (verified: 2/2 passed on `murmur_fold7_cover`)
```bash
tools/emulator/boot.sh murmur_fold7_cover 5560
ANDROID_SERIAL=emulator-5560 ./gradlew connectedDebugAndroidTest
# results: app/build/outputs/androidTest-results/connected/debug/TEST-*.xml
./gradlew --stop; tools/emulator/stop.sh emulator-5560
```
- `MainActivityTest` uses `createAndroidComposeRule<MainActivity>()` (Compose UI test) and also calls the JNI library on the device.
- Peak memory for the session cgroup during this run was about 8.0 GB of 8 GB, which is at the limit. If memory runs short, build first (`./gradlew assembleDebug assembleDebugAndroidTest; ./gradlew --stop`) and run the tests without Gradle:
  ```bash
  adb -s emulator-5560 install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb -s emulator-5560 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb -s emulator-5560 shell am instrument -w app.murmur.test/androidx.test.runner.AndroidJUnitRunner
  ```
- Next steps (not set up yet): Gradle Managed Devices (`testOptions.managedDevices`) with an ATD image would give reproducible CI runs, but ATD images cost more disk. Screenshot testing: Compose Preview Screenshot Testing or Roborazzi on the JVM, which needs no emulator.

### 4.5 Fold / posture on the inner AVD
`adb -s emulator-5562 emu sensor set hinge-angle0 0` switches `dumpsys device_state` to `CLOSED`, and `… 180` switches it back to `OPENED`. Apps using Jetpack WindowManager see the posture change. **In headless (`-no-window`) mode the display does not shrink to the folded region.** `wm size` stayed 1968x2184 and `adb emu fold` had no visible effect. Use the cover AVD for cover-screen layouts, and the hinge sensor only for posture logic.

---

## 5. Emulator display configuration (approximation of the Fold7)

| | Fold7 hardware | AVD |
|---|---|---|
| Cover | 6.5", 2520x1080 (21:9), 120 Hz | `murmur_fold7_cover`: 1080x2520, density 420 → 411 dp wide |
| Inner | 8.0", 2184x1968, 120 Hz | `murmur_fold7_inner`: 1968x2184 portrait, density 420 → 750 dp wide; hinge at x=984 |

Panel sizes come from Samsung/carrier spec pages as summarised by search ([GSMArena](https://m.gsmarena.com/samsung_galaxy_z_fold7-13826.php), [O2 spec page](https://www.o2.co.uk/help/phones-sims-and-devices/samsung/galaxy-z-fold7-android-16/specifications), [Samsung B2B spec sheet](https://image-us.samsung.com/us/smartphones/galaxy-z-fold7/pdf/Q7_B2B_Spec_Sheet_R3Lc.pdf)). I didn't open the Samsung PDF itself.
**UNVERIFIED:** the Fold7's actual default density and smallest-width dp. A forum post mentions sw 420 dp (cover) and 437 dp (inner), which doesn't match my 750 dp inner assumption. On the real phone, run `adb shell wm size; adb shell wm density` on both screens and copy the values into `tools/emulator/create_avds.sh`. Until then, layouts should be tested at **both** a compact width (~411 dp) and a medium/expanded width (600–840 dp).

---

## 6. System-level changes (complete list)

| Change | Command | Why / scope | Revert |
|---|---|---|---|
| Added user `private` to group `kvm` | `sudo usermod -aG kvm private` | `/dev/kvm` is `root:kvm 0660` and the `kvm` group was empty. The emulator needs KVM. This is persistent. New login shells get the group; existing shells can use `sg kvm -c '<cmd>'`, which `boot.sh` does automatically. | `sudo gpasswd -d private kvm` |

There were no other sudo actions. No packages, udev rules or services were changed.
Side effect: AVD files created while running under `sg kvm` have group `kvm`. This is harmless.

Everything else was done at user level: SDK packages under `~/.local/share/android-sdk`, AVDs under `~/.android/avd`, Gradle and the wrapper under `~/.gradle`.

---

## 7. minSdk choice and trade-off

- **minSdk 36 (Android 16)**: every Galaxy Z Fold7 shipped with Android 16 (One UI 8). Samsung began rolling out One UI 9 / Android 17 to the Fold7 from 2026-09-28 (Korea) and early October (US) ([gHacks](https://www.ghacks.net/2026/09/29/samsung-rolls-out-stable-one-ui-9-to-the-galaxy-s25-z-fold-7-and-z-flip-7/), [Sammy Fans](https://www.sammyfans.com/2026/09/28/samsung-starts-stable-one-ui-9-rollout-for-galaxy-z-fold-7/), [nokiamob](https://nokiamob.net/2026/10/05/samsung-rolls-out-one-ui-9-and-android-17-to-galaxy-z-flip-7-and-fold-7-globally/)). These are secondary news sources.
- **Benefit:** no compat branches for Android 16+ APIs (predictive back, edge-to-edge, newer accessibility/overlay/foreground-service rules). The test matrix is small. The brief says to support other devices only if it's easy.
- **Cost:** the app won't install on anything older than Android 16. That rules out the owner's other phones and most of the 2023–2024 flagships still on Android 15 or older.
- **Cheap fallback:** dropping to minSdk 31–34 is a one-line catalog change as long as code guards 35+/36+ APIs. Lint `NewApi` will flag them. NNAPI is deprecated since Android 15, so NPU access won't depend on minSdk anyway. It depends on vendor SDKs, which is out of scope here.

---

## 8. What did not work, and what the emulator cannot tell us

### 8.1 API 37 system images crash-loop here
- `system-images;android-37.0;google_apis;x86_64` r8: `surfaceflinger` aborts every ~10–15 s with `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma` in `mapper.ranchu.so` (`GoldfishMapper::readFromHost`, from `RegionSamplingThread`). System_server restarts and installs are lost. Reproduced with emulator **37.2.13 (stable)** and **37.3.9 (beta)**, and with `-gpu swiftshader`, `swiftshader_indirect`, `swangle`, `lavapipe` and `guest` (which fell back to lavapipe).
- `system-images;android-37.2;google_apis_ps16k;x86_64` r7 (16 KB pages): the same surfaceflinger aborts, plus `android.hardware.uwb-service` aborting ("failed to open the serial device"). This image is also **8.3 GB** extracted.
- `android-36;google_apis;x86_64` r7: 0 crashes, stable. This is what we use.
- Likely cause (inference, not confirmed): a mismatch between the guest gralloc and the host renderer's DMA colour-buffer readback when no host GPU is present ([goldfish-opengl change that introduced the flag](https://fuchsia.googlesource.com/third_party/android/device/generic/goldfish-opengl/+/d420e5bda0a201ff74f7dbc342d6807cb101ad7f%5E1..d420e5bda0a201ff74f7dbc342d6807cb101ad7f/)). Re-test when a new emulator or API 37 image revision ships. This is a one-variable change: `MURMUR_SYSTEM_IMAGE=... tools/emulator/create_avds.sh`.
- Consequence: **16 KB page-size runtime behaviour is not tested on the emulator.** The build side is verified (ELF alignment + zipalign -P 16).

### 8.2 The emulator cannot validate
- **NPU / Hexagon / Samsung-specific acceleration**: none of it exists on x86_64. QNN/LiteRT NPU delegates, vendor HAL availability, quantised-op support, NPU latency and power can only be measured on the Fold7. The emulator only runs x86_64 CPU fallbacks. The arm64-v8a `.so` files are built but never executed here.
- **Performance of any kind**: tokens/s, real-time factor, model load time, thermal throttling, and memory pressure (AVD RAM is 3 GB vs 12 GB on the device). Benchmark-button numbers from the emulator are meaningless.
- **Real Fold7 displays**: the separate cover panel, Samsung default densities, Flex mode, the taskbar, One UI multi-window and pop-up view. Edge cases of the Samsung keyboard/IME and **One UI's accessibility and overlay behaviour** (floating bubble over other apps, AccessibilityService screenshot, text insertion into third-party fields) need the device. AOSP behaviour on the emulator is only a first pass.
- **Microphone audio quality**: the emulator mic is host-routed, and we boot with `-no-audio` (no host audio device on the server). Feed ASR tests from WAV files instead.
- **Android 17 behaviour changes**, since we test on API 36 (§8.1).

---

## 9. Disk usage

| Location | Before | After | New |
|---|---|---|---|
| `~/.local/share/android-sdk` | 461 MB | 8,363 MB | **+7.9 GB** (system image 4.4 GB, NDK 2.3 GB, emulator 0.86 GB, platforms 0.47 GB, build-tools 0.3 GB, CMake 0.09 GB) |
| `~/.android/avd` | 9 MB (whole `~/.android`) | 3.2 GB | **+3.2 GB** (cover 1.65 GB, inner 1.5 GB; each can grow to the 4 GB data partition) |
| `~/.gradle` | 1.8 GB | 2.75 GB | **+0.95 GB** (Gradle 9.9.1 dist 148 MB + dependency caches) |
| repo build outputs (`app/build`, `.gradle`, `.cxx`, …) | 0 | ~110 MB | +0.1 GB |
| **Total steady state** | | | **≈ 12.2 GB** |

Peak usage during image experiments was about 19 GB. The 37.0 and 37.2 images were removed afterwards, along with `platforms;android-37.0`. Free space on `/` went from 84.8 GB to about 68 GB. Other agents wrote to the disk at the same time (for example the 812 MB `.cache/` in this repo isn't mine), so `df` overstates my share.

---

## 10. Housekeeping state at hand-off

- No emulators are running. No Gradle or Kotlin daemons are running (checked with `pgrep`).
- `cmdline-tools` is still 12.0. It works, so I didn't upgrade it. `sdkmanager --install "cmdline-tools;latest"` would install 23.0.
- Pre-existing packages were left alone: `platforms;android-35` and `build-tools;34.0.0` (no longer used by this project).
