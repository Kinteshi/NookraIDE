package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageServersTest {
    @Test
    fun picksTheServerAndLanguageIdForAFile() {
        assertEquals(OtherLanguageServer.Go to "go", OtherLanguageServer.forPath("/w/main.go"))
        assertEquals(OtherLanguageServer.C to "cpp", OtherLanguageServer.forPath("/w/src/a.hpp"))
        assertEquals(OtherLanguageServer.Docker to "dockerfile", OtherLanguageServer.forPath("/w/Dockerfile"))
        assertEquals(OtherLanguageServer.Toml to "toml", OtherLanguageServer.forPath("/w/Cargo.toml"))
        assertEquals(OtherLanguageServer.Json to "jsonc", OtherLanguageServer.forPath("/w/.vscode/workspace.code-snippets"))
        assertNull(OtherLanguageServer.forPath("/w/notes.txt"))
        assertNull(OtherLanguageServer.forPath("/w/Makefile"))
    }

    @Test
    fun recognisesAMissingServerInTheDaemonsError() {
        assertEquals(OtherLanguageServer.Go, OtherLanguageServer.missingIn("tool 'gopls' is not installed in the guest registry"))
        assertEquals(OtherLanguageServer.Bash, OtherLanguageServer.missingIn("avf-node-agent: bash-language-server is not set up; run: …"))
        assertNull(OtherLanguageServer.missingIn("tool 'typescript' is not installed in the guest registry"))
    }

    @Test
    fun sqlStyleRulesSetEveryRuleAndKeepUpperCaseKeywords() {
        val rules = sqlLintRules(SqlStyleRules.Warning)
        assertEquals(8, rules.size)
        assertEquals("warning", rules["linebreak-after-clause-keyword"])
        assertEquals(listOf("warning", "upper"), rules["reserved-word-case"])
        assertEquals(setOf("off"), sqlLintRules(SqlStyleRules.Off).values.map { (it as? List<*>)?.first() ?: it }.toSet())
    }
}
