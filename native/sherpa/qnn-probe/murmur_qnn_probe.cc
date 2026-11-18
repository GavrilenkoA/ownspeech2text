// QNN HTP probe for the :asr process (WP-06). sherpa-onnx ends the process (_Exit) when a QNN backend, device or
// context cannot be created, so before the first QNN recognizer the app asks this library whether the HTP backend
// really initialises on this device: dlopen(libQnnHtp.so) -> QnnInterface_getProviders -> logCreate ->
// backendCreate -> deviceCreate (+ platform info: SoC model, HTP arch) -> contextCreate, then frees everything.
// Built for arm64-v8a by native/sherpa/build-android.sh against the public QNN 2.40 headers; the libraries are the
// ones packaged in the APK (qnn-runtime 2.52, behind -Pmurmur.qnn). Returns codes and numbers only (D9).
#include <dlfcn.h>
#include <jni.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "HTP/QnnHtpDevice.h"
#include "QnnInterface.h"

typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t ***providers, uint32_t *num);

static void quiet_log(const char *, QnnLog_Level_t, uint64_t, va_list) {}

// Result: "stage=<ok|dlopen|symbol|providers|log|backend|device|context>;rc=<n>;build=<id>;soc=<n>;arch=<n>"
static void probe(const char *backend_lib, char *out, size_t out_len) {
  const char *stage = "dlopen";
  long rc = 0;
  const char *build = "";
  unsigned soc = 0, arch = 0;
  void *lib = dlopen(backend_lib, RTLD_NOW | RTLD_LOCAL);
  Qnn_LogHandle_t log = nullptr;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  GetProvidersFn get_providers = nullptr;
  const QnnInterface_t **providers = nullptr;
  uint32_t num = 0;
  QNN_INTERFACE_VER_TYPE api;
  std::memset(&api, 0, sizeof(api));
  if (!lib) goto done;

  stage = "symbol";
  get_providers = reinterpret_cast<GetProvidersFn>(dlsym(lib, "QnnInterface_getProviders"));
  if (!get_providers) goto done;

  stage = "providers";
  rc = static_cast<long>(get_providers(&providers, &num));
  if (rc != QNN_SUCCESS || !providers || num == 0) goto done;
  // Same choice as sherpa-onnx's QnnBackend: the first provider.
  api = providers[0]->QNN_INTERFACE_VER_NAME;
  if (api.backendGetBuildId) {
    const char *id = nullptr;
    if (api.backendGetBuildId(&id) == QNN_SUCCESS && id) build = id;
  }

  stage = "log";
  rc = static_cast<long>(api.logCreate(quiet_log, QNN_LOG_LEVEL_ERROR, &log));
  if (rc != QNN_SUCCESS) goto done;

  stage = "backend";
  rc = static_cast<long>(api.backendCreate(log, nullptr, &backend));
  if (rc != QNN_SUCCESS) goto done;

  stage = "device";
  if (api.deviceGetPlatformInfo) {
    const QnnDevice_PlatformInfo_t *info = nullptr;
    if (api.deviceGetPlatformInfo(log, &info) == QNN_SUCCESS && info) {
      if (info->version == QNN_DEVICE_PLATFORM_INFO_VERSION_1 && info->v1.numHwDevices > 0) {
        const QnnDevice_HardwareDeviceInfo_t *hw = &info->v1.hwDevices[0];
        const auto *ext = reinterpret_cast<const QnnHtpDevice_DeviceInfoExtension_t *>(hw->v1.deviceInfoExtension);
        if (ext && ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
          soc = ext->onChipDevice.socModel;
          arch = static_cast<unsigned>(ext->onChipDevice.arch);
        }
      }
      if (api.deviceFreePlatformInfo) api.deviceFreePlatformInfo(log, info);
    }
  }
  rc = static_cast<long>(api.deviceCreate(log, nullptr, &device));
  if (rc != QNN_SUCCESS) goto done;

  stage = "context";
  rc = static_cast<long>(api.contextCreate(backend, device, nullptr, &context));
  if (rc != QNN_SUCCESS) goto done;
  stage = "ok";

done:
  if (context) api.contextFree(context, nullptr);
  if (device) api.deviceFree(device);
  if (backend) api.backendFree(backend);
  if (log) api.logFree(log);
  std::snprintf(out, out_len, "stage=%s;rc=%ld;build=%s;soc=%u;arch=%u", stage, rc, build, soc, arch);
  // The library stays loaded: sherpa-onnx dlopen()s the same file next, and unloading a QNN backend that opened a
  // FastRPC session is not safe on every vendor stack.
}

extern "C" JNIEXPORT jstring JNICALL Java_app_murmur_infer_asr_QnnProbe_nativeProbe(JNIEnv *env, jclass,
                                                                                    jstring backend_lib) {
  char out[512];
  const char *path = env->GetStringUTFChars(backend_lib, nullptr);
  if (!path) return nullptr;
  probe(path, out, sizeof(out));
  env->ReleaseStringUTFChars(backend_lib, path);
  return env->NewStringUTF(out);
}
