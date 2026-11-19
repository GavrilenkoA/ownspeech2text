package app.murmur.ui.design.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a list row splits its width between the title column and the trailing value (DESIGN 8, large text). */
class TrailingWidthTest {
    @Test
    fun trailingKeepsItsLineWhenEverythingFits() {
        assertEquals(80, trailingWidth(300, titleMin = 90, titleMax = 200, trailingMin = 60, trailingMax = 80))
    }

    @Test
    fun titleWrapsBetweenWordsBeforeTheTrailingShrinks() {
        // Title needs 260 on one line, but its longest word is 130: the trailing value stays whole (164).
        assertEquals(164, trailingWidth(295, titleMin = 130, titleMax = 260, trailingMin = 164, trailingMax = 164))
    }

    @Test
    fun trailingWrapsBetweenItsWordsSoTheTitleWordFits() {
        // "Only model downloads" style value: one line 200, longest word 90; title's longest word 150.
        assertEquals(110, trailingWidth(260, titleMin = 150, titleMax = 300, trailingMin = 90, trailingMax = 200))
    }

    @Test
    fun trailingKeepsItsLongestWordWhenTwoLongestWordsCannotSitSideBySide() {
        assertEquals(130, trailingWidth(246, titleMin = 150, titleMax = 270, trailingMin = 130, trailingMax = 140))
        // ...but never more than 60 % of the row.
        assertEquals(147, trailingWidth(246, titleMin = 130, titleMax = 270, trailingMin = 200, trailingMax = 220))
    }

    @Test
    fun nothingLeft() {
        assertEquals(0, trailingWidth(0, 10, 20, 10, 20))
    }
}
