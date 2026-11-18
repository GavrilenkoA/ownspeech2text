// One-shot async result. json = the Kotlin @Serializable result (LoadInfo, Transcript, ScreenText, ...) or,
// in onError, a serialized app.murmur.core.model.MurmurError. ARCHITECTURE.md section 4.
package app.murmur.infer.api;

oneway interface IResultCallback {
    void onResult(String json);
    void onError(String errorJson);
}
