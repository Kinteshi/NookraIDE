package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.testing.FakeGuest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CopilotClientTest {
    @Test
    fun signInAndInlineCompletionFollowTheCopilotLanguageServerProtocol() {
        val initializeParams = LinkedBlockingQueue<JsonObject>()
        val completionParams = LinkedBlockingQueue<JsonObject>()
        FakeGuest { body ->
            val message = JsonParser.parseString(body).asJsonObject
            val id = message.get("id")
            when (message.get("method")?.asString) {
                "initialize" -> {
                    initializeParams.offer(message.getAsJsonObject("params"))
                    listOf("""{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{}}}""")
                }
                "signIn" -> listOf(
                    """{"jsonrpc":"2.0","id":$id,"result":{"userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","command":{"command":"github.copilot.finishDeviceFlow","title":"Sign in","arguments":[]}}}""",
                )
                "textDocument/inlineCompletion" -> {
                    completionParams.offer(message.getAsJsonObject("params"))
                    listOf(
                        """{"jsonrpc":"2.0","id":$id,"result":{"items":[{"insertText":"return a + b\r\n","range":{"start":{"line":1,"character":4},"end":{"line":1,"character":4}},"command":{"command":"github.copilot.didAcceptCompletionItem","arguments":["uuid"]}}]}}""",
                    )
                }
                else -> emptyList()
            }
        }.use { guest ->
            val copilot = CopilotClient(guest.transport, "1.2.3")
            val statuses = LinkedBlockingQueue<CopilotStatus>()
            copilot.statusListener = { statuses.offer(it) }
            copilot.start("/workspace").get(5, TimeUnit.SECONDS)
            assertEquals("Lsp", guest.startedKinds.poll(5, TimeUnit.SECONDS))
            assertEquals(COPILOT_LANGUAGE_SERVER_TOOL, guest.startedTools.poll(5, TimeUnit.SECONDS))
            val options = initializeParams.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("initializationOptions")
            assertEquals("AVF Studio Native", options.getAsJsonObject("editorInfo").get("name").asString)
            assertEquals("1.2.3", options.getAsJsonObject("editorPluginInfo").get("version").asString)

            val signIn = copilot.signIn().get(5, TimeUnit.SECONDS)!!
            assertEquals("ABCD-1234", signIn.userCode)
            assertEquals("github.copilot.finishDeviceFlow", signIn.command!!.get("command").asString)

            guest.push("""{"jsonrpc":"2.0","method":"didChangeStatus","params":{"kind":"Normal","message":"","busy":false}}""")
            assertEquals("Normal", statuses.poll(5, TimeUnit.SECONDS)?.kind)

            val suggestions = copilot.inlineCompletion(
                "/workspace/add.py", 7, LspPosition(1, 4), tabSize = 4, insertSpaces = true, invoked = false,
            ).get(5, TimeUnit.SECONDS)
            val params = completionParams.poll(5, TimeUnit.SECONDS)!!
            assertEquals(7, params.getAsJsonObject("textDocument").get("version").asInt)
            assertEquals(2, params.getAsJsonObject("context").get("triggerKind").asInt)
            assertEquals("return a + b\n", suggestions.single().insertText)
            assertEquals(LspPosition(1, 4), suggestions.single().range.start)
        }
    }

    @Test
    fun serverRequestsAreAnsweredSoTheServerNeverWaitsForever() {
        val responses = LinkedBlockingQueue<JsonObject>()
        FakeGuest { body ->
            val message = JsonParser.parseString(body).asJsonObject
            if (message.has("method") && message.get("method").asString == "initialize") {
                listOf("""{"jsonrpc":"2.0","id":${message.get("id")},"result":{"capabilities":{}}}""")
            } else {
                if (!message.has("method")) responses.offer(message)
                emptyList()
            }
        }.use { guest ->
            val client = LanguageClient(guest.transport)
            val shown = LinkedBlockingQueue<String>()
            client.showDocumentHandler = { uri -> shown.offer(uri) }
            client.start("/workspace", "ty").get(5, TimeUnit.SECONDS)
            guest.push("""{"jsonrpc":"2.0","id":"open","method":"window/showDocument","params":{"uri":"https://github.com/login/device","external":true}}""")
            guest.push("""{"jsonrpc":"2.0","id":41,"method":"custom/unknown","params":{}}""")
            guest.push("""{"jsonrpc":"2.0","id":42,"method":"workspace/workspaceFolders"}""")
            val answers = List(3) { responses.poll(5, TimeUnit.SECONDS)!! }.associateBy { it.get("id").asString }
            assertEquals("https://github.com/login/device", shown.poll(5, TimeUnit.SECONDS))
            assertTrue(answers.getValue("open").getAsJsonObject("result").get("success").asBoolean)
            assertEquals(-32601, answers.getValue("41").getAsJsonObject("error").get("code").asInt)
            assertEquals(
                "file:///workspace",
                answers.getValue("42").getAsJsonArray("result").single().asJsonObject.get("uri").asString,
            )
        }
    }

    @Test
    fun suggestionParsingSkipsMalformedItemsAndAlreadySignedInHasNoCode() {
        val parsed = parseSuggestions(
            JsonParser.parseString(
                """{"items":[{"insertText":"x"},{"insertText":"ok","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":2}}}]}""",
            ),
        )
        assertEquals(listOf("ok"), parsed.map { it.insertText })
        assertNull(parseSignIn(JsonParser.parseString("""{"status":"AlreadySignedIn","user":"octocat"}""")))
    }
}
