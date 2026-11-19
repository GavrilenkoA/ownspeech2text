package app.murmur.platform.a11y

import app.murmur.platform.overlay.VisibilityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSurfacesTest {
    private class Fake(val displayId: Int, private val attachResult: () -> Boolean) : BubbleSurface {
        var attached = false
        var destroyed = false
        override fun attach(): Boolean = attachResult().also { attached = it }
        override fun updateVisibility(input: VisibilityInput) = Unit
        override fun detach() { attached = false }
        override fun destroy() { detach(); destroyed = true }
    }

    private val created = mutableListOf<Fake>()

    /** Review F2: a surface whose attach failed is not kept, so the next attach for that display tries again. */
    @Test
    fun aFailedAttachIsDestroyedAndRetriedWithAFreshSurface() {
        var tries = 0
        val s = BubbleSurfaces { id -> Fake(id) { ++tries > 1 }.also { created += it } }
        assertEquals(BubbleSurfaces.Result.Failed(null), s.attach(0))
        assertFalse(0 in s)
        assertTrue("the failed surface is final", created[0].destroyed)
        assertEquals(BubbleSurfaces.Result.Attached, s.attach(0))
        assertTrue(0 in s)
        assertSame(created[1], s[0])
        assertTrue(created[1].attached)
        assertEquals(BubbleSurfaces.Result.Present, s.attach(0))
        assertEquals(2, created.size)
    }

    @Test
    fun anExceptionFromTheFactoryOrAttachIsAFailureWithTheClassNameOnly() {
        val boom = BubbleSurfaces { throw IllegalArgumentException("display 3 is gone") }
        assertEquals(BubbleSurfaces.Result.Failed("IllegalArgumentException"), boom.attach(3))
        assertEquals(0, boom.size)

        val s = BubbleSurfaces { id -> Fake(id) { throw IllegalStateException("bad token") }.also { created += it } }
        assertEquals(BubbleSurfaces.Result.Failed("IllegalStateException"), s.attach(1))
        assertFalse(1 in s)
        assertTrue(created.single().destroyed)
    }

    @Test
    fun destroyIsPerDisplayAndDestroyAllEmptiesTheSet() {
        val s = BubbleSurfaces { id -> Fake(id) { true }.also { created += it } }
        s.attach(0); s.attach(2)
        assertEquals(setOf(0, 2), s.displayIds)
        s.destroy(2)
        assertEquals(setOf(0), s.displayIds)
        assertTrue(created[1].destroyed)
        s.destroyAll()
        assertEquals(0, s.size)
        assertTrue(created[0].destroyed)
    }
}
