package app.murmur.core.prompt

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering runs on the phone's :main per dictation, so the largest golden must render in < 5 ms on the host JVM
 * (after JIT warm-up, median CPU time of repeated runs). Two numbers: a renderer that already rendered this profile once
 * (every dictation after the first) and a fresh renderer (the first dictation: reads the packaged files, renders
 * the few-shot turns, hashes the prefix).
 */
class RenderPerformanceTest {
    private val assets = ClasspathPromptAssets()

    private val threads = java.lang.management.ManagementFactory.getThreadMXBean()

    /**
     * Median CPU time of this thread per run: the build server runs many builds at once (load far above the core
     * count), so wall time measures the scheduler; the CPU time is the rendering work a phone core has to do.
     */
    private fun medianMs(runs: Int, block: () -> Unit): Pair<Double, Double> {
        val cpu = DoubleArray(runs)
        val wall = DoubleArray(runs)
        for (i in 0 until runs) {
            val c = threads.currentThreadCpuTime
            val w = System.nanoTime()
            block()
            cpu[i] = (threads.currentThreadCpuTime - c) / 1e6
            wall[i] = (System.nanoTime() - w) / 1e6
        }
        cpu.sort()
        wall.sort()
        return cpu[runs / 2] to wall[runs / 2]
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
        check(threads.isCurrentThreadCpuTimeSupported)
        val (warmMs, warmWall) = medianMs(200) { warm.render(input) }
        val (coldMs, coldWall) = medianMs(50) { RenderPyPort(assets).render(input) }
        println(("largest golden ${case.id} ($expectedChars chars), median CPU (wall): warm renderer %.3f (%.3f) ms, " +
            "fresh renderer %.3f (%.3f) ms").format(warmMs, warmWall, coldMs, coldWall))
        assertTrue("warm render %.3f ms CPU".format(warmMs), warmMs < 5.0)
        assertTrue("fresh render %.3f ms CPU".format(coldMs), coldMs < 5.0)
    }
}
