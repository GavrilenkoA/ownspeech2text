// Streaming generation from the :llm process. Text is plain String over binder (UTF-16 Parcel, emoji-safe);
// the JNI boundary inside :llm converts to UTF-8 bytes (llm spike D11).
package app.murmur.infer.api;

oneway interface ILlmStreamCallback {
    void onPiece(String text);
    /** statsJson = LlmStats. */
    void onDone(String text, String statsJson);
    void onError(String errorJson);
}
