package app.murmur.core.engine

import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError

/**
 * Why an inference process went away (ADR-083). On Android a load that needs more memory than the device has is
 * rarely refused: lmkd kills the process, so the client sees `ProcessDied` during the load. The system records that
 * kill as `ApplicationExitInfo.REASON_LOW_MEMORY`; `:app` reads it (`AndroidProcessExits`), tests use a fake.
 */
fun interface ProcessExits {
    /** True when the process instance [pid] was killed by the system for memory. May wait briefly for the record. */
    suspend fun killedForMemory(pid: Int): Boolean

    companion object {
        /** No exit records (JVM tests, debug fake engines): every death during a load stays a death. */
        val NONE = ProcessExits { false }
    }
}

/** ADR-066 / ADR-083: the load-error rules shared by the executor, Preparing and the benchmark. */
object LoadFailures {
    /** A load error says the unit cannot run the artifact unless the device ran out of memory. */
    fun judgesUnit(error: MurmurError): Boolean = error !is MurmurError.OutOfMemory

    /**
     * A death of the process instance [pid] during the load of a [kind] model that the system made for memory is
     * `OutOfMemory(kind)` (no unit mark, the fallback is still tried); every other error is returned as is.
     */
    suspend fun classify(error: MurmurError, kind: ModelKind, pid: Int?, exits: ProcessExits): MurmurError =
        if (error is MurmurError.ProcessDied && pid != null && pid > 0 && exits.killedForMemory(pid)) MurmurError.OutOfMemory(kind)
        else error
}
