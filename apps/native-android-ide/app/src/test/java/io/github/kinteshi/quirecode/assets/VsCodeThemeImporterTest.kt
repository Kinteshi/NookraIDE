package io.github.kinteshi.quirecode.assets

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class VsCodeThemeImporterTest {
    @Test
    fun convertsThemeWorkbenchAndTokenColors() {
        val directory = Files.createTempDirectory("theme-import-test").toFile()
        try {
            val themeFile = File(directory, "theme.json").apply {
                writeText(
                    """
                    {
                      "name":"Community Dark",
                      "colors":{"editor.background":"#101010","editor.foreground":"#eeeeee"},
                      "tokenColors":[
                        {"scope":["keyword","storage"],"settings":{"foreground":"#c586c0","fontStyle":"bold"}},
                        {"scope":"comment","settings":{"foreground":"#6a9955"}}
                      ],
                      "semanticTokenColors":{"variable":"#9cdcfe","function":{"foreground":"#dcdcaa"}}
                    }
                    """.trimIndent(),
                )
            }

            val imported = VsCodeThemeImporter().import(themeFile, directory)
            val result = JsonParser.parseString(imported.textMateThemeJson).asJsonObject
            val settings = result.getAsJsonArray("settings")

            assertEquals("Community Dark", imported.name)
            assertEquals("#101010", settings[0].asJsonObject.getAsJsonObject("settings").get("background").asString)
            assertEquals("keyword", settings[1].asJsonObject.get("scope").asJsonArray[0].asString)
            assertEquals("#9cdcfe", imported.semanticTokenColors["variable"])
            assertEquals("#dcdcaa", imported.semanticTokenColors["function"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resolvesRelativeTokenColorFilesWithoutEscapingExtension() {
        val directory = Files.createTempDirectory("theme-import-test").toFile()
        try {
            File(directory, "tokens.json").writeText(
                """[{"scope":"constant.numeric","settings":{"foreground":"#b5cea8"}}]""",
            )
            val theme = File(directory, "theme.json").apply {
                writeText("""{"name":"Linked","tokenColors":"tokens.json"}""")
            }

            val imported = VsCodeThemeImporter().import(theme, directory)

            assertTrue(imported.textMateThemeJson.contains("constant.numeric"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importsStandaloneThemeJsonWithInlineTokens() {
        val imported = VsCodeThemeImporter().importJson(
            """{"name":"Standalone","tokenColors":[{"scope":"keyword","settings":{"foreground":"#c586c0"}}]}""",
            "theme.json",
        )

        assertEquals("Standalone", imported.name)
        assertTrue(imported.textMateThemeJson.contains("keyword"))
    }

    @Test
    fun expandsChainedThemeIncludesAndInlineRules() {
        val directory = Files.createTempDirectory("theme-import-test").toFile()
        try {
            File(directory, "base.json").writeText(
                """{"include":"middle.json","tokenColors":[{"scope":"string","settings":{"foreground":"#ce9178"}}]}""",
            )
            File(directory, "middle.json").writeText(
                """{"tokenColors":[{"scope":"comment","settings":{"foreground":"#6a9955"}}]}""",
            )
            val theme = File(directory, "theme.json").apply {
                writeText(
                    """{"name":"Chained","include":"base.json","tokenColors":[{"scope":"keyword","settings":{"foreground":"#c586c0"}}]}""",
                )
            }

            val imported = VsCodeThemeImporter().import(theme, directory)

            assertTrue(imported.textMateThemeJson.contains("string"))
            assertTrue(imported.textMateThemeJson.contains("comment"))
            assertTrue(imported.textMateThemeJson.contains("keyword"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsTokenColorFileOutsideExtension() {
        val directory = Files.createTempDirectory("theme-import-test").toFile()
        val parent = directory.parentFile
        val outside = File(parent, "${directory.name}-outside.json")
        try {
            outside.writeText("[]")
            val theme = File(directory, "theme.json").apply {
                writeText("""{"name":"Unsafe","tokenColors":"../${outside.name}"}""")
            }

            try {
                VsCodeThemeImporter().import(theme, directory)
                throw AssertionError("Expected include outside extension to be rejected")
            } catch (_: IOException) {
                assertTrue(outside.isFile)
            }
        } finally {
            outside.delete()
            directory.deleteRecursively()
        }
    }
}
