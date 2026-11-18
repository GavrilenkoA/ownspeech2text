// Streaming ASR events (Partial / Committed / Speech / Failure), JSON-encoded AsrEvent.
package app.murmur.infer.api;

oneway interface IAsrSessionCallback {
    void onEvent(String asrEventJson);
}
