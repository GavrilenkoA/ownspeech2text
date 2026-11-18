// One dictation's ASR session inside :asr. Audio arrives on the ParcelFileDescriptor pipe passed to
// IAsrService.startSession (PCM16 LE mono 16 kHz, 20 ms frames; EOF = end of audio).
package app.murmur.infer.api;

import app.murmur.infer.api.IResultCallback;

interface IAsrSession {
    /** Stop tap: drain the pipe, decode the open tail, reply with Transcript JSON. */
    void finish(IResultCallback cb);
    void cancel();
}
