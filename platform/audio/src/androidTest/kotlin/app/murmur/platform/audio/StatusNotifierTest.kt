package app.murmur.platform.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
    private companion object {
        /** setWindow(RTC, t, 60 s): the platform may stretch the window; measured on the emulator, see the report. */
        const val ALARM_WAIT_MS = 120_000L
    }

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

    /**
     * The in-process timer runs on the uptime clock and dies with the process; the wall-clock alarm does not. Here the
     * timer's thread is gone right after show() (as after a process death), and the alarm alone must flip it back.
     */
    @Test
    fun theWallClockAlarmFlipsItBackWithoutTheInProcessTimer() {
        val thread = HandlerThread("timer-that-dies").also { it.start() }
        val n = AndroidStatusNotifier(context, timer = Handler(thread.looper))
        val until = System.currentTimeMillis() + 2_000
        n.show("m", until)
        thread.quitSafely()
        val alarm = pendingUnhideAlarm()
        Log.i(MicCaptureTest.TAG, "unhide_alarm=" + alarm.joinToString(" | ") { it.trim() })
        assertTrue("an RTC alarm to the receiver is set", alarm.isNotEmpty())
        assertEquals("Show now", posted()!!.notification.actions[0].title.toString())
        val t0 = SystemClock.uptimeMillis()
        val after = Shell.waitFor(ALARM_WAIT_MS, { posted()?.notification?.actions?.get(0)?.title?.toString() }) { it == "Hide 1 h" }
        Log.i(MicCaptureTest.TAG, "unhide_by_alarm after_ms=${System.currentTimeMillis() - until} waited_ms=${SystemClock.uptimeMillis() - t0}")
        assertEquals("Hide 1 h", after)
        assertTrue("the alarm is gone once it fired", pendingUnhideAlarm().isEmpty())
    }

    @Test
    fun anAlarmNeverBringsBackAHiddenNotification() {
        notifier.show("m", System.currentTimeMillis() + 1_000)
        notifier.hide()
        assertTrue("hide() cancels the alarm", pendingUnhideAlarm().isEmpty())
        // Even a late delivery of the alarm's broadcast does nothing once the notification is gone.
        AndroidStatusNotifier(context).flipBackIfShown("m")
        Thread.sleep(1_500)
        assertNull(nm.activeNotifications.firstOrNull { it.id == AndroidStatusNotifier.NOTIFICATION_ID })
    }

    /** Our entry in the "N pending alarms:" section of dumpsys alarm (the history sections keep fired ones). */
    private fun pendingUnhideAlarm(): List<String> {
        val lines = Shell.run("dumpsys alarm").lines()
        val from = lines.indexOfFirst { it.trim().endsWith("pending alarms:") }
        if (from < 0) return emptyList()
        val pending = lines.drop(from + 1).takeWhile { it.startsWith("    ") }
        val i = pending.indexOfFirst { it.contains("action.UNHIDE") }
        return if (i < 0) emptyList() else pending.subList(maxOf(0, i - 1), minOf(pending.size, i + 2))
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
