package app.murmur.core.prompt

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering runs on the phone's :main per dictation, so the largest golden must render in < 5 ms on the host JVM
 * (after JIT warm-up, median of repeated runs). Two numbers: a renderer that already rendered this profile once
 * (every dictation after the first) and a fresh renderer (the first dictation: reads the packaged files, renders
 * the few-shot turns, hashes the prefix).
 */
class RenderPerformanceTest {
    private val assets = ClasspathPromptAssets()

    private fun medianMs(runs: Int, block: () -> Unit): Double {
        val t = DoubleArray(runs)
        for (i in 0 until runs) {
            val s = System.nanoTime()
            block()
            t[i] = (System.nanoTime() - s) / 1e6
        }
        t.sort()
        return t[runs / 2]
    }

    @Test
    fun largestGoldenRendersUnderFiveMs() {
        val largest = Goldens.json("index.json").getValue("largest").jsonObject
        val case = Goldens.byId.getValue(largest.getValue("case").jsonPrimitive.content)
        val profile = assets.profile(largest.getValue("profile").jsonPrimitive.content)
        val input = RenderInput(profile, largest.getValue("template").jsonPrimitive.content, case.ctx, case.transcript,
            case.preferences, case.styleOverride, case.systemOverride)
        val expectedChars = largest.getValue("full_chars").jsonPrimitive.content.toInt()
        val warm = RenderPyPort(assets)
        check(Py.len(warm.render(input).full) == expectedChars)
        repeat(300) { warm.render(input); RenderPyPort(assets).render(input) }
        val warmMs = medianMs(200) { warm.render(input) }
        val coldMs = medianMs(50) { RenderPyPort(assets).render(input) }
        println("largest golden ${case.id} (${expectedChars} chars): warm renderer %.3f ms, fresh renderer %.3f ms".format(warmMs, coldMs))
        assertTrue("warm render %.3f ms".format(warmMs), warmMs < 5.0)
        assertTrue("fresh render %.3f ms".format(coldMs), coldMs < 5.0)
    }
}
