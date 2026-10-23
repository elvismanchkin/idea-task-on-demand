package io.github.elvismanchkin.taskondemand.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Small helpers over the kotlinx JSON tree; REST payloads of legacy servers vary too much for strict models. */
internal val JSON = Json { ignoreUnknownKeys = true }

fun parseJson(text: String): JsonElement = JSON.parseToJsonElement(text)

/** Navigates a dotted path, e.g. `fields.status.name`. Returns null when any segment is missing. */
fun JsonElement?.at(path: String): JsonElement? {
    var current: JsonElement? = this
    for (segment in path.split('.')) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current?.takeUnless { it is JsonNull }
}

fun JsonElement?.str(path: String): String? = (at(path) as? JsonPrimitive)?.contentOrNull

fun JsonElement?.bool(path: String): Boolean? = (at(path) as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.arr(path: String): List<JsonElement> = (at(path) as? JsonArray).orEmpty()

/** For APIs that return either a bare array or an object with an array field (`values`, `results`, ...). */
fun JsonElement?.arrOrField(field: String): List<JsonElement> = when (this) {
    is JsonArray -> this
    else -> arr(field)
}

fun jsonString(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
