package app.murmur.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun accentIsUltramarine() {
        // DESIGN decision 2: one accent, #2F4BE8 (light) / #8296FF (dark).
        assertEquals(0xFF2F4BE8.toInt(), TokenColors.light.accent.value.shr(32).toInt())
        assertEquals(44, TokenType.display.size)
        assertEquals(4000, Motion.tuckAfterIdleMs)
    }
}
