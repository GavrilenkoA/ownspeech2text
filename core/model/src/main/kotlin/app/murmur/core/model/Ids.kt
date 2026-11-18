package app.murmur.core.model

import kotlinx.serialization.Serializable

/** Catalog id of a model entry, e.g. "parakeet-tdt-0.6b-v3" (one entry may have several artifacts). */
@Serializable
@JvmInline
value class ModelId(val value: String) {
    override fun toString() = value
}

/** Catalog id of one downloadable artifact of a model, e.g. "parakeet-tdt-0.6b-v3@qnn-sm8750-20s". */
@Serializable
@JvmInline
value class ArtifactId(val value: String) {
    override fun toString() = value
}

/** Primary key of a history entry (Room row id). */
@Serializable
@JvmInline
value class EntryId(val value: Long)

/**
 * One dictation attempt. Monotonic per :main process start; every async callback carries it and is
 * dropped when stale (ARCHITECTURE.md section 5, invariant I2).
 */
@Serializable
@JvmInline
value class SessionId(val value: Long)
