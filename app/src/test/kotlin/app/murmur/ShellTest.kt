package app.murmur

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTest {
    @Test
    fun fourTabsInBriefOrderWithHistoryStart() {
        assertEquals(listOf(DashboardRoute, HistoryRoute, ModelsRoute, SettingsRoute), TABS.map { it.route })
    }
}
