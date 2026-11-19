package app.murmur.platform.audio

import android.media.AudioManager.MODE_CALL_SCREENING
import android.media.AudioManager.MODE_IN_CALL
import android.media.AudioManager.MODE_IN_COMMUNICATION
import android.media.AudioManager.MODE_NORMAL
import android.media.AudioManager.MODE_RINGTONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallWatchTest {
    @Test
    fun anIncomingCallDuringTheRecordingStartsACall() {
        // The emulator's incoming call: RINGTONE while it rings, IN_CALL once answered, NORMAL after hang-up.
        val w = CallWatch(MODE_NORMAL)
        // Answering is a change to another call mode too; CaptureSession turns both into one CallStarted.
        assertEquals(listOf(true, true, false), listOf(MODE_RINGTONE, MODE_IN_CALL, MODE_NORMAL).map(w::onMode))
        assertTrue("an outgoing VoIP call", CallWatch(MODE_NORMAL).onMode(MODE_IN_COMMUNICATION))
        assertTrue(CallWatch(MODE_NORMAL).onMode(MODE_CALL_SCREENING))
    }

    @Test
    fun aCallAlreadyUpAtTheStartIsNotSignalled() {
        val voip = CallWatch(MODE_IN_COMMUNICATION)
        assertFalse("the same mode reported again", voip.onMode(MODE_IN_COMMUNICATION))
        assertFalse(CallWatch(MODE_IN_CALL).onMode(MODE_IN_CALL))
    }

    @Test
    fun aNewCallAfterOneThatWasUpAtTheStartIsSignalled() {
        assertTrue("a phone call arrives during a leaked VoIP mode", CallWatch(MODE_IN_COMMUNICATION).onMode(MODE_RINGTONE))
        val w = CallWatch(MODE_IN_COMMUNICATION)
        assertFalse("the VoIP call ends", w.onMode(MODE_NORMAL))
        assertTrue("and a new one starts", w.onMode(MODE_IN_COMMUNICATION))
    }

    @Test
    fun leavingACallOrStayingNormalIsNotACall() {
        val w = CallWatch(MODE_IN_CALL)
        assertFalse(w.onMode(MODE_NORMAL))
        assertFalse(w.onMode(MODE_NORMAL))
        assertFalse(CallWatch(MODE_NORMAL).onMode(MODE_NORMAL))
    }
}
