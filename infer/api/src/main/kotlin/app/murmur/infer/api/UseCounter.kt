package app.murmur.infer.api

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many calls, ASR sessions and generations currently use one inference process. The idle timer may only be armed
 * when [end] reports that the last use ended. Never below 0: an unbalanced [end] is ignored instead of hiding a
 * later [begin].
 */
internal class UseCounter {
    private val n = AtomicInteger()

    val count: Int get() = n.get()

    fun begin() {
        n.incrementAndGet()
    }

    /**
     * True when this call ended the last use. One atomic step: a decrement followed by a separate reset to 0 would
     * wipe out a [begin] of another thread in between (the count would read 0 while that use is held).
     */
    fun end(): Boolean = n.updateAndGet { if (it > 0) it - 1 else 0 } == 0
}
