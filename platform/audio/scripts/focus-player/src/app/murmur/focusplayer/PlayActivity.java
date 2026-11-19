package app.murmur.focusplayer;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Requests AUDIOFOCUS_GAIN and plays a 440 Hz tone (USAGE_MEDIA) for the "ms" extra, from its own app and UID, then
 * logs "WP09P_DONE focus=<result> played_ms=<n> uid=<uid>" (tag WP09P) and finishes. Started from the shell:
 * am start -n app.murmur.focusplayer/.PlayActivity --el ms 3000
 */
public class PlayActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        final long ms = getIntent().getLongExtra("ms", 3000L);
        new Thread(() -> {
            AudioManager am = getSystemService(AudioManager.class);
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs).build();
            int focusResult = am.requestAudioFocus(focus);
            Log.i("WP09P", "WP09P_FOCUS focus=" + focusResult + " uid=" + Process.myUid());
            int rate = 48000;
            AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            short[] buf = new short[rate / 50];
            long n = 0;
            long t0 = SystemClock.uptimeMillis();
            track.play();
            while (SystemClock.uptimeMillis() - t0 < ms) {
                for (int i = 0; i < buf.length; i++) {
                    buf[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (n + i) / rate));
                }
                n += buf.length;
                track.write(buf, 0, buf.length);
            }
            long played = SystemClock.uptimeMillis() - t0;
            track.stop();
            track.release();
            am.abandonAudioFocusRequest(focus);
            Log.i("WP09P", "WP09P_DONE focus=" + focusResult + " played_ms=" + played + " uid=" + Process.myUid());
            runOnUiThread(this::finish);
        }, "player").start();
    }
}
