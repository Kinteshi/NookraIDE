package com.avfstudio.nativeide.language

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsResolverTest {
    @Test
    fun mergesScopesDeeplyAndAppliesLanguageOverrides() {
        val defaults = json("""{"editor":{"fontSize":14,"tabSize":2},"python":{"analysis":{"mode":"basic"}}}""")
        val user = json("""{"editor":{"fontSize":16},"[python]":{"editor":{"tabSize":8}},"user.extra":true}""")
        val workspace = json("""{"editor":{"fontSize":18},"python":{"analysis":{"diagnosticMode":"openFiles"}}}""")

        val resolved = SettingsResolver.merge(defaults, user, workspace, languageId = "python")

        assertEquals(18, resolved.getAsJsonObject("editor").get("fontSize").asInt)
        assertEquals(8, resolved.getAsJsonObject("editor").get("tabSize").asInt)
        assertEquals("basic", resolved.getAsJsonObject("python").getAsJsonObject("analysis").get("mode").asString)
        assertEquals(
            "openFiles",
            resolved.getAsJsonObject("python").getAsJsonObject("analysis").get("diagnosticMode").asString,
        )
        // Dotted keys are VS Code shorthand for nested objects.
        assertEquals(true, resolved.getAsJsonObject("user").get("extra").asBoolean)
    }

    @Test
    fun exposesNestedSectionsForWorkspaceConfigurationRequests() {
        val settings = json("""{"python":{"analysis":{"typeCheckingMode":"strict"}}}""")

        val sections = SettingsResolver.sections(settings)

        assertEquals(
            "strict",
            sections.getValue("python.analysis").asJsonObject.get("typeCheckingMode").asString,
        )
        assertEquals(
            "strict",
            sections.getValue("python.analysis.typeCheckingMode").asString,
        )
    }

    @Test
    fun dottedKeysMergeWithNestedObjectsAcrossScopes() {
        val user = json("""{"editor.fontSize":16,"editor":{"tabSize":2},"[python]":{"editor.tabSize":8}}""")
        val resolved = SettingsResolver.merge(JsonObject(), user, json("""{"editor.wordWrap":"on"}"""), "python")

        assertEquals(16, resolved.getAsJsonObject("editor").get("fontSize").asInt)
        assertEquals(8, resolved.getAsJsonObject("editor").get("tabSize").asInt)
        assertEquals("on", resolved.getAsJsonObject("editor").get("wordWrap").asString)
    }

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
}
