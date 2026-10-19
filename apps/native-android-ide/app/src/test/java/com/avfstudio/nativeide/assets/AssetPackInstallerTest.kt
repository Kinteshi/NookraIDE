package com.avfstudio.nativeide.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AssetPackInstallerTest {
    @Test
    fun installsDeclarativeContributionsFromVsix() {
        val directory = Files.createTempDirectory("asset-pack-test").toFile()
        try {
            val vsix = archive(
                "extension/package.json" to """
                    {
                      "publisher":"example",
                      "name":"sample-pack",
                      "displayName":"Sample Pack",
                      "version":"1.2.3",
                      "main":"./out/extension.js",
                      "contributes":{
                        "themes":[{"label":"Sample Dark","path":"./themes/dark.json"}],
                        "grammars":[{"language":"example","scopeName":"source.example","path":"./syntaxes/example.json"}],
                        "languages":[{"id":"example","extensions":[".ex"],"configuration":"./language.json"}]
                      }
                    }
                """.trimIndent(),
                "extension/themes/dark.json" to """{"name":"Sample Dark","tokenColors":[]}""",
                "extension/syntaxes/example.json" to """{"scopeName":"source.example"}""",
                "extension/language.json" to """{"comments":{"lineComment":"#"}}""",
            )

            val pack = AssetPackInstaller(File(directory, "packs")).install(ByteArrayInputStream(vsix))

            assertEquals("example.sample-pack", pack.id)
            assertEquals("Sample Pack", pack.displayName)
            assertEquals("1.2.3", pack.version)
            assertEquals("Sample Dark", pack.themes.single().label)
            assertTrue(File(pack.directory, "extension/themes/dark.json").isFile)
            assertEquals("example", pack.grammars.single().label)
            assertEquals("example", pack.languages.single().languageId)
            assertEquals(".ex", pack.languages.single().extensions.single())
            assertEquals("./language.json", pack.languages.single().configurationPath)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveTraversal() {
        val directory = Files.createTempDirectory("asset-pack-test").toFile()
        try {
            val malicious = archive(
                "extension/package.json" to """{"publisher":"example","name":"unsafe"}""",
                "extension/../../escape.txt" to "outside",
            )

            try {
                AssetPackInstaller(File(directory, "packs")).install(ByteArrayInputStream(malicious))
                throw AssertionError("Expected invalid archive path to be rejected")
            } catch (_: IOException) {
                assertFalse(File(directory, "escape.txt").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedEntriesOutsideExtensionDirectory() {
        val directory = Files.createTempDirectory("asset-pack-test").toFile()
        try {
            val oversized = archive(
                "metadata/large-entry.bin" to "x".repeat(8 * 1024 * 1024 + 1),
            )

            try {
                AssetPackInstaller(File(directory, "packs")).install(ByteArrayInputStream(oversized))
                throw AssertionError("Expected oversized archive entry to be rejected")
            } catch (_: IOException) {
                assertFalse(File(directory, "packs").listFiles()?.any { it.name.startsWith(".install-") } == true)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
