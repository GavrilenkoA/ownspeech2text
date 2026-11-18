package app.murmur

/** Pure-Kotlin logic kept separate so it is covered by a plain JVM unit test. */
object Greeting {
    fun title(widthDp: Int): String = when {
        widthDp >= 600 -> "Murmur - unfolded"
        else -> "Murmur - cover screen"
    }
}
