package app.murmur

import app.murmur.core.dictation.OpenTarget
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelKind
import app.murmur.shell.DeepLink
import app.murmur.shell.ShellNav
import app.murmur.platform.a11y.EnabledServices
import app.murmur.wiring.StatusNotificationController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {
    @Test
    fun fourTabsInBriefOrderWithHistoryStart() {
        assertEquals(listOf(DashboardRoute, HistoryRoute, ModelsRoute, SettingsRoute), TABS.map { it.route })
        assertEquals("History is the start destination (DESIGN decision 4)", HistoryRoute, ShellNav.tabOf(emptyList()))
        assertEquals(listOf(R.string.tab_dashboard, R.string.tab_history, R.string.tab_models, R.string.tab_settings), TABS.map { it.label })
        assertEquals(listOf(0, 1, 2, 3), ShellNav.tabRoots.map(ShellNav::indexOf))
    }

    @Test
    fun tabsAreRootsAndDetailsBelongToTheirTab() {
        assertEquals(listOf(ModelsRoute), ShellNav.selectTab(ModelsRoute))
        val entry = ShellNav.openEntry(EntryId(8))
        assertEquals(listOf(HistoryRoute, HistoryEntryRoute(8)), entry)
        assertEquals(HistoryRoute, ShellNav.tabOf(entry))
        val model = ShellNav.openModel("lfm3-1.7b")
        assertEquals(listOf(ModelsRoute, ModelDetailRoute("lfm3-1.7b")), model)
        assertEquals(ModelsRoute, ShellNav.tabOf(model))
    }

    @Test
    fun backClosesTheDetailThenReturnsToHistoryThenLeaves() {
        assertEquals(listOf(HistoryRoute), ShellNav.back(ShellNav.openEntry(EntryId(3))))
        assertEquals(listOf(ModelsRoute), ShellNav.back(ShellNav.openModel("x")))
        assertEquals(listOf(HistoryRoute), ShellNav.back(listOf(SettingsRoute)))
        assertEquals(listOf(HistoryRoute), ShellNav.back(listOf(DashboardRoute)))
        assertNull("Back on History's root leaves the app", ShellNav.back(listOf(HistoryRoute)))
    }

    @Test
    fun tryADictationPushesThePracticeOverTheCurrentTabOnce() {
        val fromDashboard = ShellNav.openPractice(listOf(DashboardRoute))
        assertEquals(listOf(DashboardRoute, PracticeRoute), fromDashboard)
        assertEquals(DashboardRoute, ShellNav.tabOf(fromDashboard))
        assertEquals("a second tap does not stack it", fromDashboard, ShellNav.openPractice(fromDashboard))
        assertEquals(listOf(HistoryRoute, PracticeRoute), ShellNav.openPractice(emptyList()))
        assertEquals("Back returns to where it was opened", listOf(DashboardRoute), ShellNav.back(fromDashboard))
    }

    @Test
    fun pushedScreensCoverTheBottomBar() {
        assertFalse(ShellNav.isPushed(listOf(HistoryRoute)))
        assertFalse(ShellNav.isPushed(listOf(SettingsRoute)))
        assertTrue(ShellNav.isPushed(ShellNav.openEntry(EntryId(1))))
        assertTrue(ShellNav.isPushed(ShellNav.openModel("m")))
        assertTrue(ShellNav.isPushed(ShellNav.openPractice(listOf(HistoryRoute))))
    }

    @Test
    fun deepLinksFromTheBubbleRoundTripThroughIntentExtras() {
        assertEquals(DeepLink.Entry(EntryId(42)), DeepLink.parse(DeepLink.OPEN_ENTRY, 42, null))
        assertEquals(DeepLink.Models(null), DeepLink.parse(DeepLink.OPEN_MODELS, null, null))
        assertEquals(DeepLink.Models(ModelKind.LLM), DeepLink.parse(DeepLink.OPEN_MODELS, null, "LLM"))
        assertEquals("unknown kind = the Speech segment", DeepLink.Models(null), DeepLink.parse(DeepLink.OPEN_MODELS, null, "NOPE"))
        assertNull("an entry link without an id is ignored", DeepLink.parse(DeepLink.OPEN_ENTRY, null, null))
        assertNull(DeepLink.parse(DeepLink.OPEN_ENTRY, -1, null))
        assertNull("an ordinary launch", DeepLink.parse(null, null, null))
        assertNull(DeepLink.parse("something", 1, null))
        assertEquals(DeepLink.Entry(EntryId(5)), DeepLink.of(OpenTarget.Entry(EntryId(5))))
        assertEquals(DeepLink.Models(ModelKind.ASR), DeepLink.of(OpenTarget.Models(ModelKind.ASR)))
        assertEquals(listOf(HistoryRoute, HistoryEntryRoute(42)), ShellNav.forLink(DeepLink.Entry(EntryId(42))))
        assertEquals(listOf(ModelsRoute), ShellNav.forLink(DeepLink.Models(ModelKind.LLM)))
    }

    @Test
    fun statusNotificationShowsOnlyWithTheServiceAndPermission() {
        assertNull(StatusNotificationController.decide(connected = false, notificationsAllowed = true, modelsLine = "A · B", hiddenUntil = null))
        assertNull(StatusNotificationController.decide(connected = true, notificationsAllowed = false, modelsLine = "A · B", hiddenUntil = null))
        assertEquals(
            StatusNotificationController.Shown("A · B", 123L),
            StatusNotificationController.decide(connected = true, notificationsAllowed = true, modelsLine = "A · B", hiddenUntil = 123L),
        )
    }

    @Test
    fun theServiceComponentMatchesBothSettingForms() {
        val pkg = "app.murmur"
        val cls = "app.murmur.platform.a11y.MurmurAccessibilityService"
        assertTrue(EnabledServices.contains("$pkg/$cls", pkg, cls))
        assertTrue(EnabledServices.contains("com.other/.Svc:$pkg/.platform.a11y.MurmurAccessibilityService", pkg, cls))
        assertFalse(EnabledServices.contains("com.other/.Svc", pkg, cls))
        assertFalse(EnabledServices.contains("", pkg, cls))
        assertFalse("another package's class of the same name", EnabledServices.contains("app.murmurx/$cls", pkg, cls))
    }
}
