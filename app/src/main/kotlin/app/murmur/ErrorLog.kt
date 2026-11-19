package app.murmur

/**
 * The logcat line for an unexpected error (rule 10 / D9: codes and numbers only). It names the exception classes of
 * the cause chain and the throwing frame, never a message: messages are not controlled and could carry fragments of
 * transcript or screen text.
 */
internal fun internalErrorLine(t: Throwable): String {
    val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CAUSES)
        .joinToString(" <- ") { it.javaClass.name }
    val frame = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "?"
    return "internal_error $chain at $frame"
}

private const val MAX_CAUSES = 5
