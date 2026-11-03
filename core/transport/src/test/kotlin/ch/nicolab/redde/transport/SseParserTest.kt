package ch.nicolab.redde.transport

import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.TurnEvent
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from EchoTests/SSEParserTests.swift and LineBufferTests.swift. */
class SseParserTest {
    @Test
    fun splitsEventsAcrossChunks() {
        val parser = SseParser()
        val events = parser.feed("event: response.output_text.delta\ndata: {\"del").toMutableList()
        assertTrue(events.isEmpty())
        events += parser.feed("ta\":\"Hi\"}\n\ndata: [DONE]\n\n")
        assertEquals(listOf(SseEvent("response.output_text.delta", "{\"delta\":\"Hi\"}"), SseEvent(null, "[DONE]")), events)
    }

    @Test
    fun joinsMultiLineDataAndIgnoresComments() {
        assertEquals(listOf(SseEvent(null, "a\nb")), SseParser().feed(": keepalive\ndata: a\ndata:b\n\n"))
    }

    @Test
    fun handlesCrlf() {
        assertEquals(listOf(SseEvent("x", "1", "7")), SseParser().feed("event: x\r\nid: 7\r\ndata: 1\r\n\r\n"))
    }

    @Test
    fun flushesUnterminatedEventOnFinish() {
        val parser = SseParser()
        assertTrue(parser.feed("data: tail").isEmpty())
        assertEquals(SseEvent(null, "tail"), parser.finish())
    }

    @Test
    fun lineBufferSplitsAcrossChunksAndKeepsBlankLines() {
        val b = LineBuffer()
        assertEquals(listOf("event: a\n", "data: 1\n", "\n"), b.append("event: a\ndata: 1\n\nev".toByteArray()))
        assertEquals(listOf("event: b\n"), b.append("ent: b\n".toByteArray()))
        assertTrue(b.append("data: 2".toByteArray()).isEmpty())
        assertEquals("data: 2", b.flush())
        assertNull(b.flush())
    }

    @Test
    fun lineBufferKeepsACharacterCutBetweenChunksWhole() {
        val bytes = "data: café\n".toByteArray()
        val b = LineBuffer()
        val cut = bytes.indexOfFirst { it == 0xC3.toByte() } + 1
        assertTrue(b.append(bytes.copyOfRange(0, cut)).isEmpty())
        assertEquals(listOf("data: café\n"), b.append(bytes.copyOfRange(cut, bytes.size)))
    }

    @Test
    fun decodesChatCompletionChunks() {
        val chunk = SseEvent(null, """{"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}""")
        val out = ChatCompletionsTransport.decode(chunk)
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("Hi")), out.events)
        assertFalse(out.finished)
        assertTrue(ChatCompletionsTransport.decode(SseEvent(null, "[DONE]")).finished)
        val usage = SseEvent(null, """{"choices":[],"usage":{"prompt_tokens":19,"completion_tokens":6,"prompt_tokens_details":{"cached_tokens":7}}}""")
        assertEquals(listOf<TurnEvent>(TurnEvent.Usage(TokenUsage(19, 6, 7, contextUsed = 25))), ChatCompletionsTransport.decode(usage).events)
    }

    /** llama.cpp's `return_progress` chunks (prefill percentage) surface as prefill events. */
    @Test
    fun decodesPromptProgressChunks() {
        val progress = SseEvent(null, """{"choices":[{"delta":{"content":null},"finish_reason":null}],"prompt_progress":{"total":9100,"cache":8412,"processed":4200,"time_ms":1250}}""")
        assertEquals(listOf<TurnEvent>(TurnEvent.Prefill(4200, 9100, 8412)), ChatCompletionsTransport.decode(progress).events)
    }

    @Test
    fun decodesReasoningAndHermesToolProgress() {
        assertEquals(
            listOf<TurnEvent>(TurnEvent.ReasoningDelta("hmm")),
            ChatCompletionsTransport.decode(SseEvent(null, """{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""")).events,
        )
        assertEquals(
            listOf<TurnEvent>(TurnEvent.ToolStarted("web", "Searching")),
            ChatCompletionsTransport.decode(SseEvent("hermes.tool.progress", """{"tool":"web","label":"Searching","status":"running"}""")).events,
        )
        assertEquals(
            listOf<TurnEvent>(TurnEvent.ToolFinished("web", failed = true)),
            ChatCompletionsTransport.decode(SseEvent("hermes.tool.progress", """{"tool":"web","status":"error"}""")).events,
        )
    }

    @Test
    fun anErrorChunkFailsTheTurn() {
        val error = runCatching { ChatCompletionsTransport.decode(SseEvent(null, """{"error":{"message":"model not found"}}""")) }.exceptionOrNull()
        assertEquals("model not found", (error as TransportError.Malformed).detail)
    }

    /** The progress flag only goes to self-hosted endpoints; OpenAI rejects unknown arguments. */
    @Test
    fun progressFlagOnlyForSelfHostedEndpoints() {
        for (host in listOf("http://localhost:8080", "http://192.168.1.5:8080", "http://100.101.102.103:11500",
            "https://llm.tailnet-name.ts.net", "http://10.0.0.2", "http://172.20.0.1", "http://mbp:8080")) {
            assertTrue(ChatCompletionsTransport.isSelfHosted(host.toHttpUrl()), "$host should count as self-hosted")
        }
        for (host in listOf("https://api.openai.com", "https://openrouter.ai", "https://172.15.0.1", "https://100.128.0.1")) {
            assertFalse(ChatCompletionsTransport.isSelfHosted(host.toHttpUrl()), "$host should not")
        }
    }

    @Test
    fun everyLevelReachesALocalModel() {
        val local = "http://100.101.102.103:11500".toHttpUrl()
        val cloud = "https://api.openai.com".toHttpUrl()
        for (level in listOf("low", "medium", "high", "xhigh", "max")) {
            val kwargs = ChatCompletionsTransport.thinkingKwargs(level, local)!!
            assertEquals(JsonPrimitive(true), kwargs["enable_thinking"])
            assertEquals(JsonPrimitive(level), kwargs["reasoning_effort"])
        }
        assertEquals(JsonPrimitive("high"), ChatCompletionsTransport.thinkingKwargs("High", local)!!["reasoning_effort"])
        assertEquals(JsonPrimitive(false), ChatCompletionsTransport.thinkingKwargs("none", local)!!["enable_thinking"])
        assertNull(ChatCompletionsTransport.thinkingKwargs(null, local))
        assertNull(ChatCompletionsTransport.thinkingKwargs("", local))
        assertNull(ChatCompletionsTransport.thinkingKwargs("high", cloud))
    }

    @Test
    fun pathsGoUnderTheBase() {
        assertEquals("http://h:8080/v1/chat/completions", "http://h:8080".toHttpUrl().resolvePath("v1/chat/completions").toString())
        assertEquals("http://h:8080/v1/chat/completions", "http://h:8080/".toHttpUrl().resolvePath("v1/chat/completions").toString())
        assertEquals("http://h/llm/v1/models", "http://h/llm".toHttpUrl().resolvePath("v1/models").toString())
        assertEquals("http://h/llm/v1/models", "http://h/llm/".toHttpUrl().resolvePath("v1/models").toString())
    }
}
