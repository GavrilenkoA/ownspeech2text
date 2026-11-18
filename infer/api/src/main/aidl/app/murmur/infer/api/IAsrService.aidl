// Bound service of the :asr process (sherpa-onnx + ORT 1.32 + Silero VAD + OCR + QNN 2.52, ADR-005).
// Every method returns fast; work happens on the service's engine threads and replies via callbacks.
// Payloads are JSON of the :core:engine types (versioned by API_VERSION, checked at bind).
package app.murmur.infer.api;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import app.murmur.infer.api.IAsrSession;
import app.murmur.infer.api.IAsrSessionCallback;
import app.murmur.infer.api.IResultCallback;

interface IAsrService {
    int apiVersion();
    /** ProcessProbe JSON: implemented runtime keys, units that initialised (QNN HTP?), facts. */
    String probe();
    /** ProcessMemory JSON. */
    String memory();
    /** asrSpec / vadSpec = LoadSpec JSON (vad may be null for streaming models). Replies LoadInfo JSON. */
    void loadAsr(String asrSpecJson, String vadSpecJson, IResultCallback cb);
    void loadOcr(String detSpecJson, String recSpecJson, IResultCallback cb);
    /** Starts reading PCM from pcmRead immediately (buffers until the model is loaded). */
    IAsrSession startSession(String optionsJson, in ParcelFileDescriptor pcmRead, IAsrSessionCallback cb);
    /**
     * ARGB_8888 pixels (width*height ints, little-endian) in a read-only SharedMemory created by :main;
     * excludeJson = List<Rect>. Replies ScreenText JSON. The service unmaps and drops it after the call.
     */
    void recognizeScreen(in SharedMemory pixels, int width, int height, String excludeJson, IResultCallback cb);
    /** Unload every runtime; the process exits on unbind (InferProcessLifecycle). */
    void unloadAll();
}
