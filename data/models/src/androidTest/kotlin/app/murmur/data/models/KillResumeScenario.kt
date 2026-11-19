package app.murmur.data.models

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.model.ArtifactId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process death in the middle of a download (WP-13 acceptance), driven by data/models/tools/kill_resume.sh:
 *  - the script starts [VisibleActivity] with `--es enqueue t-one@onnx` (144 MB): a normal app process, no
 *    instrumentation, queues the UIDT job;
 *  - once model.onnx.part holds at least 20 MB it sends the app home and runs `adb shell am kill <package>`;
 *  - JobScheduler restarts the job in a new process; the script waits for installed.json and runs [verify]:
 *    the job ran twice, the second run sent a Range request from the .part offset and got HTTP 206, and every file's
 *    sha256 matches the catalog.
 * It needs that orchestration, so the plain suite run excludes it (`-e notClass`, see the script).
 */
@RunWith(AndroidJUnit4::class)
class KillResumeScenario {
    private val app = TestModelsApp.app
    private val downloads = app.modelDownloads
    private val tone = ArtifactId("t-one@onnx")
    private val spec = CatalogParser.bundled().artifact(tone)!!.second

    @Test
    fun verify() {
        val events = app.eventLog.readLines()
        events.forEach { Log.i("MurmurModelsTest", "event $it") }
        val starts = events.filter { " job_start " in it && "artifact=${tone.value}" in it }
        assertTrue("the job ran again after the kill: $starts", starts.size >= 2)
        val resumed = events.filter { " dl_http " in it && "file=model.onnx" in it && "code=206" in it }
        assertTrue("a 206 from the part offset: $events", resumed.isNotEmpty())
        val from = resumed.first().substringAfter("rangeFrom=").substringBefore(' ').toLong()
        assertTrue(from >= 20L shl 20)
        assertTrue(resumed.first(), "contentRange=bytes $from-" in resumed.first())
        val marker = downloads.storage.readMarker(tone)!!
        for (f in spec.files) {
            val file = downloads.storage.file(tone, f.name)
            assertEquals(f.sha256, sha256Hex(file))
            assertEquals(f.sha256, marker.files[f.name])
        }
        Log.i("MurmurModelsTest", "kill_resume_verified resumeFrom=$from bytes=${spec.downloadBytes}")
    }
}
