package com.avfstudio.nativeide.language

import com.google.gson.JsonElement
import com.google.gson.JsonObject

object SettingsResolver {
    fun merge(defaults: JsonObject, user: JsonObject, workspace: JsonObject, languageId: String? = null): JsonObject {
        val merged = mergeObjects(defaults, user, workspace)
        if (languageId != null) {
            val languageOverrides = merged.getAsJsonObject("[$languageId]")?.deepCopy() ?: JsonObject()
            merged.remove("[$languageId]")
            return mergeObjects(merged, languageOverrides)
        }
        return merged
    }

    fun sections(settings: JsonObject): Map<String, JsonElement> {
        val sections = linkedMapOf<String, JsonElement>()
        fun visit(value: JsonObject, prefix: String) {
            value.entrySet().forEach { (name, child) ->
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                sections[key] = child.deepCopy()
                if (child.isJsonObject) visit(child.asJsonObject, key)
            }
        }
        visit(settings, "")
        return sections
    }

    private fun mergeObjects(vararg scopes: JsonObject): JsonObject {
        val result = JsonObject()
        scopes.forEach { source ->
            source.entrySet().forEach { (name, value) ->
                val current = result.get(name)
                if (current?.isJsonObject == true && value.isJsonObject) {
                    result.add(name, mergeObjects(current.asJsonObject, value.asJsonObject))
                } else {
                    result.add(name, value.deepCopy())
                }
            }
        }
        return result
    }
}
