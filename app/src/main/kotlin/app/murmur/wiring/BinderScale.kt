package app.murmur.wiring

/**
 * The scale of the binder timeouts (`BinderTimeouts.scale`, ADR-090 item 2). Release builds always use 1. Debug builds
 * use Settings.debugTimeoutScale to lengthen them on the slow x86_64 emulator, but never shorten them: a binder timeout
 * means "the process is dead" (shutdown), and a load timeout also marks the compute unit as failed in the persistent
 * unit-health file (`LoadFailures.judgesUnit`). DC8's tmo10 case (scale 0.1, which shortens the orchestrator's own
 * timers) would otherwise cut a cold NPU load to 1.5 s and leave the phone on CPU until a benchmark clears the mark.
 */
fun binderScale(debug: Boolean, debugTimeoutScale: Float): Double =
    if (!debug) 1.0 else debugTimeoutScale.toDouble().takeIf { it > 0 }?.coerceAtLeast(1.0) ?: 1.0
