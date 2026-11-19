package app.murmur.core.context

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin port of ml/common/jsonschema_lite.py: the JSON Schema subset prompts/context.schema.v2.json uses (type,
 * enum, const, properties, required, additionalProperties, items, maxItems, minItems, maxLength, minimum, maximum).
 * Same check order and semantics: const and enum stop at the first mismatch, "integer" excludes 1.0 and booleans,
 * maxLength counts code points. Keywords outside the subset are rejected, so a schema change cannot pass unchecked.
 */
object SchemaLite {
    private val KNOWN = setOf(
        "\$schema", "\$id", "title", "description", "type", "enum", "const", "properties", "required",
        "additionalProperties", "items", "maxItems", "minItems", "maxLength", "minimum", "maximum",
    )
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    fun validate(value: JsonElement, schema: JsonObject): List<String> = mutableListOf<String>().also { v(value, schema, "$", it) }

    /** Keywords in [schema] (recursively) that this validator does not implement. */
    fun unsupportedKeywords(schema: JsonObject, path: String = "$"): List<String> {
        val out = mutableListOf<String>()
        for ((k, sub) in schema) {
            if (k !in KNOWN) out += "$path.$k"
            when (k) {
                "properties" -> sub.jsonObject.forEach { (name, s) -> out += unsupportedKeywords(s.jsonObject, "$path.properties.$name") }
                "items" -> out += unsupportedKeywords(sub.jsonObject, "$path.items")
            }
        }
        return out
    }

    private fun typeMatches(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && value !is JsonNull && !value.isString && INTEGER.matches(value.content)
        "number" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull == null &&
            value.doubleOrNull != null
        else -> throw IllegalArgumentException("unknown type $type")
    }

    private fun v(value: JsonElement, s: JsonObject, path: String, errs: MutableList<String>) {
        s["const"]?.let { if (value != it) { errs += "$path: expected $it"; return } }
        s["enum"]?.let { if (value !in it.jsonArray) { errs += "$path: $value not in enum"; return } }
        val types = when (val t = s["type"]) {
            null -> emptyList()
            is JsonArray -> t.map { it.jsonPrimitive.content }
            else -> listOf(t.jsonPrimitive.content)
        }
        if (types.isNotEmpty() && types.none { typeMatches(value, it) }) {
            errs += "$path: type of $value not in $types"
            return
        }
        when {
            value is JsonObject -> {
                val props = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
                s["required"]?.jsonArray?.forEach { k -> if (k.jsonPrimitive.content !in value) errs += "$path: missing ${k.jsonPrimitive.content}" }
                for ((k, sub) in value) {
                    val ps = props[k]
                    if (ps != null) v(sub, ps.jsonObject, "$path.$k", errs)
                    else if (s["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) errs += "$path: unknown key $k"
                }
            }
            value is JsonArray -> {
                s["maxItems"]?.let { if (value.size > it.jsonPrimitive.content.toInt()) errs += "$path: ${value.size} items > $it" }
                s["minItems"]?.let { if (value.size < it.jsonPrimitive.content.toInt()) errs += "$path: ${value.size} items < $it" }
                s["items"]?.let { item -> value.forEachIndexed { i, x -> v(x, item.jsonObject, "$path[$i]", errs) } }
            }
            value is JsonPrimitive && value !is JsonNull && value.isString -> {
                val n = value.content.codePointCount(0, value.content.length)
                s["maxLength"]?.let { if (n > it.jsonPrimitive.content.toInt()) errs += "$path: length $n > $it" }
            }
            value is JsonPrimitive && value !is JsonNull && value.booleanOrNull == null -> {
                val d = value.doubleOrNull ?: return
                s["minimum"]?.let { if (d < it.jsonPrimitive.content.toDouble()) errs += "$path: $d < $it" }
                s["maximum"]?.let { if (d > it.jsonPrimitive.content.toDouble()) errs += "$path: $d > $it" }
            }
        }
    }
}
