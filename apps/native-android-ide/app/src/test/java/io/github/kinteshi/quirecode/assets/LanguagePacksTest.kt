package io.github.kinteshi.quirecode.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePacksTest {
    @Test
    fun findsThePackByNameThenExtension() {
        assertEquals("vscode.sql", languagePackFor("schema.sql")?.id)
        assertEquals("vscode.docker", languagePackFor("Dockerfile")?.id)
        assertEquals("vscode.make", languagePackFor("Makefile")?.id)
        assertEquals("vscode.rust", languagePackFor("main.RS")?.id)
        assertEquals("vscode.yaml", languagePackFor("ci.yml")?.id)
        assertNull(languagePackFor("script.py"))
        assertNull(languagePackFor("notes"))
    }

    @Test
    fun everyPackIsPinnedToAnOpenVsxFile() {
        assertTrue(LANGUAGE_PACKS.size >= 40)
        LANGUAGE_PACKS.forEach { pack ->
            assertTrue(pack.id, pack.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(pack.id, pack.downloadUrl.startsWith("https://open-vsx.org/api/vscode/"))
        }
        assertEquals("https://open-vsx.org/api/vscode/sql/1.95.3/file/vscode.sql-1.95.3.vsix", languagePackFor("a.sql")!!.downloadUrl)
        assertEquals("SQL", languagePackFor("a.sql")!!.languageName)
    }
}
