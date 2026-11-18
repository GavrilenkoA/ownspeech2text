package app.murmur.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic VAD probability sequences, one per rule of [SegmenterConfig] (frames of 512 samples = 32 ms at 16 kHz). */
class SegmenterTest {
    private val f = 512L

    /** Feeds [probs] as consecutive frames starting at frame [firstFrame]; returns every closed segment. */
    private fun PauseSegmenter.feed(probs: List<Float>, firstFrame: Long = 0): List<Segment> =
        probs.mapIndexedNotNull { i, p -> onFrame(p, (firstFrame + i + 1) * f) }

    private fun speech(n: Int, p: Float = 0.9f) = List(n) { p }
    private fun silence(n: Int, p: Float = 0.05f) = List(n) { p }

    @Test
    fun pauseOfHalfASecondCutsWithPreAndPostRoll() {
        val s = PauseSegmenter()
        // 20 silent frames, 40 speech frames (frames 20..59), then silence.
        val segs = s.feed(silence(20) + speech(40) + silence(30))
        assertEquals(1, segs.size)
        val seg = segs[0]
        assertEquals(20 * f - 4_800, seg.startSample)          // onset frame start - 0.3 s
        assertEquals(60 * f + 4_800, seg.endSample)            // last speech end + 0.3 s
        assertFalse(seg.forced)
        assertEquals(40 * 32L, seg.speechMs)
    }

    @Test
    fun cutHappensOnTheSixteenthSilentFrameNotTheFifteenth() {
        val s = PauseSegmenter()
        // 15 silent frames = 480 ms < 500 ms: no cut; the 16th (512 ms) cuts.
        assertTrue(s.feed(speech(20) + silence(15)).isEmpty())
        val seg = s.onFrame(0.05f, 36 * f)
        requireNotNull(seg)
        assertEquals(20 * f + 4_800, seg.endSample)
        assertNull(s.openStartSample)
        assertFalse(s.speaking)
    }

    @Test
    fun speechInsideThePauseRestartsTheSilenceRun() {
        val s = PauseSegmenter()
        val segs = s.feed(speech(20) + silence(10) + speech(1) + silence(15))
        assertTrue(segs.isEmpty())
        assertEquals(31 * f, s.lastSpeechEndSample)
    }

    @Test
    fun hysteresisFramesNeitherCountAsSilenceNorResetIt() {
        val s = PauseSegmenter()
        // 10 silent, 20 frames at 0.4 (between 0.35 and 0.5), 5 silent: 15 silent frames in the run -> no cut yet.
        assertTrue(s.feed(speech(20) + silence(10) + List(20) { 0.4f } + silence(5)).isEmpty())
        // The 16th silent frame of the run cuts; the end is the last real speech frame + post-roll.
        val seg = requireNotNull(s.onFrame(0.05f, 56 * f))
        assertEquals(20 * f + 4_800, seg.endSample)
        assertEquals(20 * 32L, seg.speechMs)
    }

    @Test
    fun preRollNeverStartsBeforeSampleZeroOrThePreviousSegment() {
        val s = PauseSegmenter()
        val first = s.feed(speech(10) + silence(16)).single()
        assertEquals(0L, first.startSample)                     // onset at sample 0: no negative pre-roll
        assertEquals(10 * f + 4_800, first.endSample)
        // Next onset one frame after the cut frame: onset - 0.3 s would overlap the first segment's post-roll.
        val second = s.feed(silence(1) + speech(10) + silence(16), firstFrame = 26).single()
        assertEquals(first.endSample, second.startSample)
        assertEquals(1, second.index)
    }

    @Test
    fun softCutAfterFourteenSecondsNeedsOnlyAHundredFiftyMilliseconds() {
        val s = PauseSegmenter()
        // A segment that is shorter than 14 s does not cut on 5 silent frames (160 ms).
        val short = s.feed(speech(100) + silence(5) + speech(10))
        assertTrue(short.isEmpty())
        // Grow the same segment beyond 14 s (438 frames = 14.016 s from sample 0), then 5 silent frames cut it.
        val more = s.feed(speech(323), firstFrame = 115)
        assertTrue(more.isEmpty())
        val cut = s.feed(silence(5), firstFrame = 438)
        val seg = cut.single()
        assertFalse(seg.forced)
        // Post-roll is limited by "now": the cut cannot reach into audio that has not arrived.
        assertEquals(443 * f, seg.endSample)
        assertEquals((100 + 10 + 323) * 32L, seg.speechMs)
    }

    @Test
    fun softSilenceIsNotEnoughBeforeFourteenSeconds() {
        val s = PauseSegmenter()
        assertTrue(s.feed(speech(300) + silence(5) + speech(5) + silence(15)).isEmpty())
        assertTrue(s.speaking)
    }

    @Test
    fun forcedCutAtHardMaxOnTheLowestFrameOfTheLastTwoSeconds() {
        val s = PauseSegmenter()
        val hardFrames = (19_500 * 16 + 511) / 512             // 610 frames: first frame end >= 19.5 s
        val probs = MutableList(hardFrames) { 0.9f }
        probs[100] = 0.0f                                      // lower, but more than 2 s before the cut: ignored
        probs[hardFrames - 20] = 0.55f                         // lowest inside the window
        probs[hardFrames - 10] = 0.55f                         // same value later: the earliest wins
        val segs = s.feed(probs)
        val seg = segs.single()
        assertTrue(seg.forced)
        assertEquals(0L, seg.startSample)
        val lowEnd = (hardFrames - 20 + 1) * f
        assertEquals(lowEnd - 256, seg.endSample)              // centre of the lowest frame
        // The next segment starts exactly at the cut (no overlap) and is still speaking.
        assertEquals(seg.endSample, s.openStartSample)
        assertTrue(s.speaking)
        // Speech frames after the cut frame belong to the next segment.
        assertEquals((hardFrames - 20 + 1 - 1) * 32L, seg.speechMs) // frame 100 is silence-level, the rest speech
        assertEquals(hardFrames.toLong() - 1, s.speechMs / 32)
        val rest = requireNotNull(s.flush(hardFrames * f + 1_000))
        assertEquals(seg.endSample, rest.startSample)
        assertEquals(19 * 32L, rest.speechMs)
        assertEquals(1, rest.index)
    }

    @Test
    fun forcedCutFollowsTheModelWindow() {
        val s = PauseSegmenter(SegmenterConfig.forWindow(30.0))
        val frames = (29_500 * 16 + 511) / 512
        assertTrue(s.feed(speech(frames - 1)).isEmpty())
        assertTrue(s.feed(speech(1), firstFrame = frames.toLong() - 1).single().forced)
    }

    @Test
    fun aSecondForcedCutSearchesOnlyTheNewSegment() {
        val s = PauseSegmenter()
        val hardFrames = (19_500 * 16 + 511) / 512
        val probs = MutableList(2 * hardFrames + 10) { 0.9f }
        val segs = s.feed(probs)
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.forced })
        // Equal probabilities everywhere: the earliest candidate frame of each window is chosen.
        assertEquals(segs[0].endSample, segs[1].startSample)
        assertTrue(segs[1].endSample - segs[1].startSample in (19_500 * 16 - 2_000 * 16).toLong()..(19_500 * 16).toLong())
    }

    @Test
    fun segmentsWithLessThanMinSpeechAreDropped() {
        val s = PauseSegmenter()
        // 7 speech frames = 224 ms < 250 ms: dropped, and its speech is not counted.
        assertTrue(s.feed(speech(7) + silence(20)).isEmpty())
        assertEquals(0L, s.speechMs)
        assertNull(s.openStartSample)
        // 8 frames = 256 ms: kept.
        val seg = s.feed(speech(8) + silence(20), firstFrame = 27).single()
        assertEquals(0, seg.index)
        assertEquals(256L, seg.speechMs)
        assertEquals(256L, s.speechMs)
    }

    @Test
    fun flushClosesTheOpenTailAtTheStopSample() {
        val s = PauseSegmenter()
        val closed = s.feed(speech(20) + silence(20) + speech(30) + silence(5))
        assertEquals(1, closed.size)
        val tail = requireNotNull(s.flush(75 * f + 100))
        assertEquals(40 * f - 4_800, tail.startSample)
        assertEquals(75 * f + 100, tail.endSample)
        assertEquals(1, tail.index)
        assertFalse(tail.forced)
        assertEquals(50 * 32L, s.speechMs)
        assertNull(s.flush(80 * f))                             // nothing open any more
    }

    @Test
    fun flushDropsATailWithTooLittleSpeechAndNothingOpenGivesNull() {
        val s = PauseSegmenter()
        assertNull(s.flush(0))
        s.feed(silence(10) + speech(5))
        assertNull(s.flush(15 * f))
        assertEquals(0L, s.speechMs)
    }

    @Test
    fun speechMsCountsOnlySpeechFramesOfKeptSegments() {
        val s = PauseSegmenter()
        s.feed(speech(10) + List(10) { 0.4f } + silence(16) + speech(3) + silence(16) + speech(12))
        // 10 frames kept (first segment), 3 frames dropped, 12 frames open.
        assertEquals((10 + 12) * 32L, s.speechMs)
    }
}
