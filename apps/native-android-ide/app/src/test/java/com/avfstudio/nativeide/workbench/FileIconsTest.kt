package com.avfstudio.nativeide.workbench

import androidx.compose.ui.graphics.Color
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileIconsTest {
    @Test
    fun woffBecomesTheFontItWraps() {
        val woff = javaClass.classLoader!!.getResourceAsStream("seti.woff")!!.readBytes()
        val expected = File("src/main/res/font/seti.ttf").readBytes()
        assertArrayEquals(expected, woffToSfnt(woff))
    }

    @Test
    fun picturesFoldersAndLightOverrides() {
        val root = Files.createTempDirectory("icons").toFile()
        File(root, "icons").mkdirs()
        File(root, "dist").mkdirs()
        // A picture that exists, outside the pack.
        val outside = File(root.parentFile, "outside.svg").apply { writeText("<svg/>") }
        listOf("file.svg", "python.svg", "folder.svg", "folder-open.svg", "folder-src.svg", "python-light.svg").forEach { File(root, "icons/$it").writeText("<svg/>") }
        val theme = FileIconTheme(
            JsonParser.parseString(
                """
                {
                  "iconDefinitions": {
                    "file": {"iconPath": "./../icons/file.svg"},
                    "python": {"iconPath": "./../icons/python.svg"},
                    "python_light": {"iconPath": "./../icons/python-light.svg"},
                    "folder": {"iconPath": "./../icons/folder.svg"},
                    "folder_open": {"iconPath": "./../icons/folder-open.svg"},
                    "folder_src": {"iconPath": "./../icons/folder-src.svg"},
                    "escape": {"iconPath": "../../outside.svg"}
                  },
                  "file": "file",
                  "folder": "folder",
                  "folderExpanded": "folder_open",
                  "fileExtensions": {"py": "python", "secret": "escape"},
                  "folderNames": {"src": "folder_src"},
                  "hidesExplorerArrows": true,
                  "light": {"fileExtensions": {"py": "python_light"}}
                }
                """.trimIndent(),
            ).asJsonObject,
            File(root, "dist"),
            root,
        )
        fun picture(icon: FileIcon?) = (icon as FileIcon.Picture).file.name
        assertEquals("python.svg", picture(theme.iconFor("main.py", dark = true)))
        assertEquals("python-light.svg", picture(theme.iconFor("main.py", dark = false)))
        // A picture outside the theme's folder isn't used: the default file icon shows.
        assertEquals("file.svg", picture(theme.iconFor("a.secret", dark = true)))
        assertEquals("folder-src.svg", picture(theme.folderIconFor("SRC", expanded = false, dark = true)))
        assertEquals("folder-src.svg", picture(theme.folderIconFor("src", expanded = true, dark = true)))
        assertEquals("folder-open.svg", picture(theme.folderIconFor("docs", expanded = true, dark = true)))
        assertTrue(theme.hasFolderIcons && theme.hidesExplorerArrows)
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun setiHasNoFolderIcons() {
        val theme = FileIconTheme(
            JsonParser.parseString(
                """{"iconDefinitions": {"_default": {"fontCharacter": "\\E023", "fontColor": "#d4d7d6"}}, "file": "_default"}""",
            ).asJsonObject,
        )
        assertNull(theme.folderIconFor("src", expanded = false, dark = true))
        val glyph = theme.iconFor("x.unknown", dark = true) as FileIcon.Glyph
        assertEquals("", glyph.glyph)
        assertEquals(Color(0xFFD4D7D6), glyph.color)
    }

    @Test
    fun hexColors() {
        assertEquals(Color(0xFFFF0000), parseHexColor("#f00"))
        assertEquals(Color(0x80112233), parseHexColor("#11223380"))
        assertNull(parseHexColor("red"))
    }

    @Test
    fun hexOffsets() {
        assertEquals(1234L, parseOffset("1234"))
        assertEquals(0x4D2L, parseOffset("0x4d2"))
        assertEquals(0x4D2L, parseOffset("4D2h"))
        assertEquals(0xFFL, parseOffset("ff"))
        assertNull(parseOffset("zz"))
    }
}
