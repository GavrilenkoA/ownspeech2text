package app.murmur.infer.asr

import app.murmur.core.audio.PauseSegmenter
import app.murmur.core.audio.Segment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Host parity with the asr-ocr spike (docs/research/verify/asr-ocr.md section 6): the Kotlin segmenter, replayed on the
 * Silero v5 probabilities of the spike's five one-minute clips, closes the same segments at the same samples as
 * `scripts/seg_stream.py segment()`. Fixture: `segmenter/seg_parity.json`, written by
 * `.cache/spikes/wp-06/scripts/seg_parity_fixture.py` (probabilities rounded to 1e-5, spike segments computed on the
 * rounded values). Two intended differences, both applied to the expectation:
 *  - a segment never starts before the previous one ended (the spike let the pre-roll overlap the previous post-roll);
 *  - segments with less than 250 ms of speech are dropped (SegmenterConfig.minSpeechMs; the spike kept anything spanning
 *    0.4 s with its pre-roll). On these clips that drops exactly three blips of 96-128 ms of speech, which the spike's
 *    Parakeet and GigaAM runs decoded to empty text and Whisper to "(air whooshing)" / "(slamming)"
 *    (`logs/seg_{parakeet-v3,gigaam-v3-rnnt,whisper-small}.json`).
 */
class SegmenterParityTest {
    private val fixture = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader!!.getResource("segmenter/seg_parity.json")).readText(),
    ).jsonObject

    @Test
    fun sameCutPointsAsTheSpikeOnAllFiveLongClips() {
        val frame = fixture["frame"]!!.jsonPrimitive.long
        val clips = fixture["clips"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, clips.size)
        var compared = 0
        var dropped = 0
        for (clip in clips) {
            val id = clip["id"]!!.jsonPrimitive.content
            val probs = clip["probs"]!!.jsonArray.map { it.jsonPrimitive.float }
            val s = PauseSegmenter()
            val mine = probs.mapIndexedNotNull { k, p -> s.onFrame(p, (k + 1) * frame) }
            val spike = spikeSegments(clip)
            val expected = spike.filter { speechMs(probs, frame, it) >= 250 }.let(::clampStarts)
            dropped += spike.size - expected.size
            assertEquals("$id: segment count", expected.size, mine.size)
            for ((e, m) in expected.zip(mine)) {
                assertNear("$id #${m.index} start", e.startSample, m.startSample)
                assertNear("$id #${m.index} end", e.endSample, m.endSample)
                assertEquals("$id #${m.index} forced", e.forced, m.forced)
                compared++
            }
            val tail = clip["openStart"]!!.jsonPrimitive.longOrNull
            val expectedTail = tail?.let { maxOf(it, expected.lastOrNull()?.endSample ?: 0) }
            if (expectedTail == null) assertEquals("$id: open tail", null, s.openStartSample)
            else assertNear("$id: open tail", expectedTail, requireNotNull(s.openStartSample) { "$id: no open tail" })
            assertTrue("$id: speech", s.speechMs > 10_000)
        }
        assertEquals(27, compared)
        assertEquals(3, dropped)
    }

    private fun spikeSegments(clip: JsonObject): List<Segment> =
        clip["segments"]!!.jsonArray.mapIndexed { i, e ->
            val o = e.jsonObject
            Segment(i, o["start"]!!.jsonPrimitive.long, o["end"]!!.jsonPrimitive.long, o["forced"]!!.jsonPrimitive.boolean)
        }

    private fun clampStarts(segs: List<Segment>): List<Segment> {
        var prevEnd = 0L
        return segs.mapIndexed { i, s -> s.copy(index = i, startSample = maxOf(s.startSample, prevEnd)).also { prevEnd = s.endSample } }
    }

    /** Speech frames (p >= 0.5) whose end lies inside the segment. */
    private fun speechMs(probs: List<Float>, frame: Long, s: Segment): Long =
        probs.indices.count { k -> probs[k] >= 0.5f && (k + 1) * frame in (s.startSample + 1)..s.endSample } * frame * 1000 / 16_000

    /** The fixture stores seconds x 16,000 rounded to integers; allow that rounding (one sample). */
    private fun assertNear(what: String, expected: Long, actual: Long) =
        assertTrue("$what: expected $expected, got $actual", abs(expected - actual) <= 1)
}
