package com.orgutil.data.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * SSE delta fields on both wire protocols are string-or-null, and gateways
 * such as DeepSeek send explicit JSON nulls (e.g. `"content": null` inside
 * tool-call frames). kotlinx.serialization's JsonNull IS a JsonPrimitive
 * whose [JsonPrimitive.content] is the literal string "null", so naive
 * `?.jsonPrimitive?.content` accessors inject "null" into assistant text and
 * tool-call accumulators.
 *
 * Boundary rule: only genuine string primitives pass. Absent values, JSON
 * null, and non-strings (numbers, booleans, objects) yield null - while a
 * REAL "null" string from the model is a string literal and still passes.
 */
internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
