package com.orgutil.data.agent.tools

import com.orgutil.domain.chat.AgentTool
import com.orgutil.domain.chat.ToolArgumentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared plumbing for the Org agent tools. */
abstract class BaseAgentTool : AgentTool {

    protected val json = Json { encodeDefaults = true }

    protected fun requireString(args: JsonObject, field: String): String {
        val value = args[field] ?: throw ToolArgumentException("Missing argument '$field'")
        return try {
            value.jsonPrimitive.content
        } catch (e: Exception) {
            throw ToolArgumentException("Argument '$field' must be a string")
        }
    }

    protected fun optionalString(args: JsonObject, field: String, default: String = ""): String {
        val value = args[field] ?: return default
        return try {
            value.jsonPrimitive.content
        } catch (e: Exception) {
            throw ToolArgumentException("Argument '$field' must be a string")
        }
    }

    protected fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    /** Compact schema: name -> description, all fields required. */
    protected fun objectSchema(vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                fields.forEach { (name, description) -> put(name, stringSchema(description)) }
            }
        )
        put(
            "required",
            kotlinx.serialization.json.JsonArray(
                fields.map { kotlinx.serialization.json.JsonPrimitive(it.first) }
            )
        )
    }
}
