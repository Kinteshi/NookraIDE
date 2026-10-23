package com.avfstudio.nativeide.ai

import com.avfstudio.nativeide.testing.FakeGuest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AcpClientTest {
    @Test
    fun promptStreamsUpdatesAndRoundTripsPermissionRequests() {
        val requests = LinkedBlockingQueue<JsonObject>()
        val permissionAnswers = LinkedBlockingQueue<JsonObject>()
        FakeGuest { body ->
            val message = JsonParser.parseString(body).asJsonObject
            val id = message.get("id")
            if (!message.has("method")) {
                permissionAnswers.offer(message)
                return@FakeGuest listOf("""{"jsonrpc":"2.0","id":7,"result":{"stopReason":"end_turn"}}""")
            }
            requests.offer(message)
            when (message.get("method").asString) {
                "initialize" -> listOf(
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"github","name":"GitHub","description":null}]}}""",
                )
                "session/new" -> listOf("""{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"s-1"}}""")
                "session/prompt" -> listOf(
                    """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}}}""",
                    """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}}}""",
                    """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Edit main.py","kind":"edit","status":"pending"}}}""",
                    """{"jsonrpc":"2.0","id":"perm-1","method":"session/request_permission","params":{"sessionId":"s-1","toolCall":{"toolCallId":"t1","title":"Edit main.py"},"options":[{"optionId":"allow","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Reject","kind":"reject_once"}]}}""",
                )
                else -> emptyList()
            }
        }.use { guest ->
            val client = AcpClient(guest.transport)
            val events = LinkedBlockingQueue<AcpEvent>()
            client.eventListener = { event ->
                if (event is AcpEvent.PermissionRequest) event.respond("allow")
                events.offer(event)
            }
            client.start("/workspace").get(5, TimeUnit.SECONDS)
            assertEquals("Acp", guest.startedKinds.poll(5, TimeUnit.SECONDS))
            assertEquals(COPILOT_AGENT_TOOL, guest.startedTools.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf("github"), client.authMethods.map { it.id })
            val initialize = requests.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("params")
            assertEquals(1, initialize.get("protocolVersion").asInt)

            client.newSession("/workspace").get(5, TimeUnit.SECONDS)
            assertEquals("/workspace", requests.poll(5, TimeUnit.SECONDS)!!.getAsJsonObject("params").get("cwd").asString)

            val stopReason = client.prompt("Say hello")
            val prompt = requests.poll(5, TimeUnit.SECONDS)!!
            assertEquals("s-1", prompt.getAsJsonObject("params").get("sessionId").asString)
            val text = StringBuilder()
            repeat(2) { text.append((events.poll(5, TimeUnit.SECONDS) as AcpEvent.MessageChunk).text) }
            assertEquals("Hello", text.toString())
            assertEquals("Edit main.py", (events.poll(5, TimeUnit.SECONDS) as AcpEvent.ToolCall).title)
            assertTrue(events.poll(5, TimeUnit.SECONDS) is AcpEvent.PermissionRequest)
            val answer = permissionAnswers.poll(5, TimeUnit.SECONDS)!!
            assertEquals("perm-1", answer.get("id").asString)
            val outcome = answer.getAsJsonObject("result").getAsJsonObject("outcome")
            assertEquals("selected", outcome.get("outcome").asString)
            assertEquals("allow", outcome.get("optionId").asString)
            // The fake replies to the permission answer with the prompt's result (request id 7 is
            // never used), so the turn is still open; cancelling must notify the agent.
            client.cancel()
            assertEquals("session/cancel", requests.poll(5, TimeUnit.SECONDS)!!.get("method").asString)
            assertTrue(!stopReason.isDone || stopReason.get() == "end_turn")
        }
    }

    @Test
    fun sessionUpdatesParseKnownKindsAndIgnoreTheRest() {
        fun parse(json: String) = parseSessionUpdate(JsonParser.parseString(json).asJsonObject)
        assertEquals(AcpEvent.ThoughtChunk("hmm"), parse("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}"""))
        assertEquals(AcpEvent.Plan(listOf("a", "b")), parse("""{"sessionUpdate":"plan","entries":[{"content":"a"},{"content":"b"}]}"""))
        assertEquals(null, parse("""{"sessionUpdate":"agent_message_chunk","content":{"type":"image","data":""}}"""))
        assertEquals(null, parse("""{"sessionUpdate":"current_mode_update","currentModeId":"x"}"""))
        assertEquals("cancelled", permissionOutcome(null).getAsJsonObject("outcome").get("outcome").asString)
    }
}
