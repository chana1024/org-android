package com.orgutil.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire schemas for tool parameters must be JSON Schema type "object" on every
 * protocol this app speaks (Anthropic `input_schema`, OpenAI-compatible
 * `parameters`). Strict gateways reject a schema whose top-level `type` is
 * absent - e.g. DeepSeek answers 400 "Invalid schema for function ...: schema
 * must be JSON Schema type object, got type null." Parameterless tools declare
 * an empty schema object, so every registered tool is normalized where the
 * request body is built:
 * - `type`: kept when the tool declares a string type, defaulted to "object";
 * - `properties`: defaulted to `{}` when absent or not an object;
 * - `required`: kept only as an array of strings naming declared properties
 *   (invalid entries dropped, key omitted when nothing valid remains);
 * - every other field (descriptions, nested property schemas,
 *   additionalProperties, ...) passes through untouched.
 */
internal fun JsonObject.normalizedAsObjectSchema(): JsonObject {
    val type = (this["type"] as? JsonPrimitive)?.takeIf { it.isString } ?: JsonPrimitive("object")
    val properties = (this["properties"] as? JsonObject) ?: JsonObject(emptyMap())
    val required = (this["required"] as? JsonArray)
        ?.filterIsInstance<JsonPrimitive>()
        ?.filter { it.isString && it.content in properties.keys }
        ?.takeIf { it.isNotEmpty() }
    return buildJsonObject {
        put("type", type)
        put("properties", properties)
        if (required != null) put("required", JsonArray(required))
        this@normalizedAsObjectSchema.forEach { (key, value) ->
            if (key != "type" && key != "properties" && key != "required") put(key, value)
        }
    }
}
