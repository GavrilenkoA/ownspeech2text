package app.murmur.platform.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.ProductName
import app.murmur.platform.audio.test.Shell
import app.murmur.platform.audio.test.TestApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StatusNotifierTest {
    private val context = Shell.context
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidStatusNotifier(context)

    @Before
    fun setUp() {
        Shell.grant(Manifest.permission.POST_NOTIFICATIONS)
        TestApp.actions.clear()
    }

    @After
    fun tearDown() = notifier.hide()

    private fun posted(): StatusBarNotification? = Shell.waitFor(
        2_000,
        { nm.activeNotifications.firstOrNull { it.id == AndroidStatusNotifier.NOTIFICATION_ID } },
    ) { it != null }

    private fun actions(n: Notification) = n.actions.map { it.title.toString() }

    @Test
    fun readyNotificationWithModelsHideAndStart() {
        notifier.show("Parakeet v3 · LFM3 1.7B", null)
        val sbn = posted()
        assertNotNull(sbn)
        val n = sbn!!.notification
        assertEquals("${ProductName.NAME} is ready", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Parakeet v3 · LFM3 1.7B", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Hide 1 h", "Start dictation"), actions(n))
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("not a foreground-service notification", n.flags and Notification.FLAG_FOREGROUND_SERVICE == 0)
        val ch = nm.getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertNull("silent channel", ch.sound)
        assertEquals("${ProductName.NAME} status", ch.name.toString())

        val dump = Shell.run("dumpsys notification --noredact")
        val i = dump.lines().indexOfFirst { it.contains("pkg=${context.packageName}") && it.contains("id=${AndroidStatusNotifier.NOTIFICATION_ID}") }
        Log.i(MicCaptureTest.TAG, "dumpsys_notification=" + dump.lines().drop(i).take(4).joinToString(" | ") { it.trim() })
        assertTrue("listed by dumpsys notification", i >= 0)
    }

    @Test
    fun actionsReachTheHandler() {
        notifier.show("m", null)
        val n = posted()!!.notification
        n.actions[1].actionIntent.send()
        assertEquals(StatusAction.StartDictation, TestApp.actions.poll(3, TimeUnit.SECONDS))
        val before = System.currentTimeMillis()
        n.actions[0].actionIntent.send()
        val hide = TestApp.actions.poll(3, TimeUnit.SECONDS)
        assertTrue("hide action: $hide", hide is StatusAction.Hide)
        val until = (hide as StatusAction.Hide).untilEpochMs
        assertTrue("hidden for one hour", until - before in 3_590_000L..3_610_000L)

        notifier.show("m", until)
        val hidden = Shell.waitFor(2_000, { posted()?.notification?.let(::actions) }) { it?.first() == "Show now" }
        assertEquals(listOf("Show now", "Start dictation"), hidden)
        posted()!!.notification.actions[0].actionIntent.send()
        assertEquals(StatusAction.ShowNow, TestApp.actions.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun showNowFlipsBackToHideWhenTheHiddenTimeIsOver() {
        notifier.show("m", System.currentTimeMillis() + 1_500)
        assertEquals("Show now", posted()!!.notification.actions[0].title.toString())
        val after = Shell.waitFor(4_000, { posted()?.notification?.actions?.get(0)?.title?.toString() }) { it == "Hide 1 h" }
        assertEquals("Hide 1 h", after)
    }

    @Test
    fun hideRemovesIt() {
        notifier.show("m", null)
        assertNotNull(posted())
        notifier.hide()
        val left = Shell.waitFor(2_000, { nm.activeNotifications.any { it.id == AndroidStatusNotifier.NOTIFICATION_ID } }) { !it }
        assertEquals(false, left)
    }
}
