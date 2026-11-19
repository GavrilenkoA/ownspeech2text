package app.murmur.platform.a11y

/**
 * The service's bubbles, one per display. A surface is kept only when its [BubbleSurface.attach] succeeded: a failed
 * attach (a bad window token, a display that went away) is destroyed at once, so a later [attach] for that display
 * (the next display change) tries again with a fresh surface instead of finding a surface without a window.
 */
internal class BubbleSurfaces(private val create: (displayId: Int) -> BubbleSurface) {
    sealed interface Result {
        /** The display already had an attached bubble. */
        data object Present : Result
        data object Attached : Result
        /** No bubble on the display; [error] is the exception's class name, or null when attach() returned false. */
        data class Failed(val error: String?) : Result
    }

    private val surfaces = LinkedHashMap<Int, BubbleSurface>()

    val size: Int get() = surfaces.size
    val displayIds: Set<Int> get() = surfaces.keys.toSet()
    operator fun contains(displayId: Int): Boolean = displayId in surfaces
    operator fun get(displayId: Int): BubbleSurface? = surfaces[displayId]

    fun attach(displayId: Int): Result {
        if (displayId in surfaces) return Result.Present
        val surface = try {
            create(displayId)
        } catch (e: RuntimeException) {
            // e.g. a display that went away between the event and the host's constructor.
            return Result.Failed(e.javaClass.simpleName)
        }
        val failure: Result.Failed? = try {
            if (surface.attach()) null else Result.Failed(null)
        } catch (e: RuntimeException) {
            Result.Failed(e.javaClass.simpleName)
        }
        if (failure != null) {
            runCatching { surface.destroy() }
            return failure
        }
        surfaces[displayId] = surface
        return Result.Attached
    }

    fun forEach(action: (displayId: Int, surface: BubbleSurface) -> Unit) {
        for ((id, s) in surfaces) action(id, s)
    }

    /** Final for that display's surface ([BubbleSurface.destroy]); a re-added display gets a new one. */
    fun destroy(displayId: Int) {
        surfaces.remove(displayId)?.destroy()
    }

    fun destroyAll() = surfaces.keys.toList().forEach(::destroy)
}
