package io.github.kinteshi.nookraide.workbench

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

    @Test
    fun deepCratesAreLinkedForRustAnalyzer() {
        val root = "/home/droid/Projects"
        // The root and one level down are rust-analyzer's own discovery.
        assertEquals(null, rustLinkedProjects(root, listOf("$root/Cargo.toml", "$root/app/Cargo.toml")))
        // A deeper crate turns discovery off, so every open crate is linked.
        assertEquals(
            listOf("$root/app/Cargo.toml", "$root/b36/rs/Cargo.toml"),
            rustLinkedProjects(root, listOf("$root/b36/rs/Cargo.toml", "$root/app/Cargo.toml", "$root/b36/rs/Cargo.toml")),
        )
        assertEquals(null, rustLinkedProjects(root, emptyList()))
    }

    @Test
    fun aSecondInitializeIsRecognized() {
        assertEquals(true, alreadyInitialized("Unknown request: initialize"))
        assertEquals(true, alreadyInitialized("Server already initialized"))
        assertEquals(false, alreadyInitialized("could not start language server ty"))
    }
}
