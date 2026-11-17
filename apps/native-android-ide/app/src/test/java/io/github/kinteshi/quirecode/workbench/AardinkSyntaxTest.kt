package io.github.kinteshi.quirecode.workbench

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import io.github.kinteshi.quirecode.assets.LANGUAGE_PACKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AardinkSyntaxTest {
    @Test
    fun builtInLanguagesCoverExpectedFilesAndLeavePlainTextAndThirdPartyLanguagesToFallback() {
        val expected = listOf(
            "Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "app.ts" to "typescript",
            "index.jsx" to "typescript",
            "settings.json" to "json",
            "Cargo.toml" to "toml",
            "layout.xml" to "xml",
            "index.html" to "html",
            "styles.css" to "css",
            "README.md" to "markdown",
            "workflow.yml" to "yaml",
            "gradle.properties" to "properties",
            ".gitconfig" to "ini",
            ".bashrc" to "shell",
            "main.py" to "python",
            "schema.sql" to "sql",
            "App.java" to "java",
            "Jenkinsfile" to "groovy",
            "Dockerfile.dev" to "dockerfile",
            "fix.patch" to "diff",
            "theme.scss" to "scss",
            "vars.less" to "less",
        )
        expected.forEach { (path, id) ->
            val def = AardinkSyntax.builtInLanguageFor(path)
            assertNotNull("Expected built-in language for $path", def)
            assertEquals(id, def?.id)
        }
        assertNull(AardinkSyntax.builtInLanguageFor("notes.txt"))
        assertNull(AardinkSyntax.builtInLanguageFor("src/main.rs"))
        assertNull(AardinkSyntax.builtInLanguageFor("main.go"))
        assertNull(AardinkSyntax.builtInLanguageFor("lib.cpp"))
    }

    @Test
    fun builtInLanguageIdCoversFencesAndUntitledLanguages() {
        assertEquals("typescript", AardinkSyntax.builtInLanguageForId("typescriptreact")?.id)
        assertEquals("shell", AardinkSyntax.builtInLanguageForId("shellscript")?.id)
        assertEquals("yaml", AardinkSyntax.builtInLanguageForId("yml")?.id)
        assertEquals("python", AardinkSyntax.builtInLanguageForId("Python")?.id)
        assertEquals("kotlin", AardinkSyntax.builtInLanguageFor("Untitled-1", languageId = "kt")?.id)
        assertNull(AardinkSyntax.builtInLanguageForId("plaintext"))
        assertNull(AardinkSyntax.builtInLanguageForId("rust"))
    }

    @Test
    fun builtInCoversMatchesPackExtensionsAndFilenames() {
        val dockerPack = LANGUAGE_PACKS.first { it.id == "vscode.docker" }
        val yamlPack = LANGUAGE_PACKS.first { it.id == "vscode.yaml" }
        val rustPack = LANGUAGE_PACKS.first { it.id == "vscode.rust" }
        assertTrue(AardinkSyntax.builtInCovers(dockerPack))
        assertTrue(AardinkSyntax.builtInCovers(yamlPack))
        assertFalse(AardinkSyntax.builtInCovers(rustPack))
    }

    @Test
    fun resolveBuiltInThemeParsesImportedVsCodeJsonAndUsesPaletteColors() {
        val imported = SyntaxTheme(
            name = "custom.color-theme.json",
            json = """
                {
                  "name": "Custom",
                  "colors": { "editor.background": "#101820" },
                  "tokenColors": [
                    { "scope": "keyword", "settings": { "foreground": "#FF5500", "fontStyle": "italic" } },
                    { "scope": "string", "settings": { "foreground": "#00CC88" } }
                  ]
                }
            """.trimIndent(),
            semanticColors = emptyMap(),
            light = false,
            imported = true,
        )
        val (importedColors, importedStyles, importedBg) = AardinkSyntax.resolveBuiltInTheme(imported)
        assertEquals(Color(0xFF101820), importedBg)
        assertEquals(Color(0xFFFF5500), importedColors[TokenType.Keyword])
        assertEquals(Color(0xFF00CC88), importedColors[TokenType.StringLiteral])
        assertEquals(true, importedStyles[TokenType.Keyword]?.italic)

        val classicPalette = SyntaxPalette.classic(dark = true)
        val classic = SyntaxTheme(
            name = "avf-dark.tmTheme.json",
            json = "{}",
            semanticColors = emptyMap(),
            light = false,
            imported = false,
            palette = classicPalette,
        )
        val (classicColors, classicStyles, classicBg) = AardinkSyntax.resolveBuiltInTheme(classic)
        assertNull(classicBg)
        assertEquals(classicPalette.keyword, classicColors[TokenType.Keyword])
        assertEquals(classicPalette.constant, classicColors[NamedTokenType("string.escape")])
        assertEquals(true, classicStyles[TokenType.Comment]?.italic)
    }
}
