package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.AttachmentError
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.MeasureUnits
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.str
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A whole turn against a stand-in OpenAI-compatible server. */
class ChatCompletionsStreamTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun sse(vararg lines: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(lines.joinToString("") { "$it\n\n" })
        .build()

    @Test
    fun streamsAReplyAndSendsTheHistory() = runBlocking {
        server.enqueue(sse(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
            "data: [DONE]",
            """data: {"choices":[{"delta":{"content":"after done: never read"}}]}""",
        ))
        val transport = ChatCompletionsTransport(server.url("/"), apiKey = "sk-1")
        val history = listOf(Message(role = Message.Role.USER, text = "Hi"), Message(role = Message.Role.ASSISTANT, text = "Hello!"))
        val events = transport.stream(TurnRequest("And again?", history = history, model = "qwen", units = MeasureUnits.US)).toList()
        assertEquals(listOf(TurnEvent.TextDelta("Hel"), TurnEvent.TextDelta("lo")), events.filterIsInstance<TurnEvent.TextDelta>())
        assertTrue(events.any { it is TurnEvent.Usage })
        assertEquals(TurnEvent.Done, events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer sk-1", recorded.headers["Authorization"])
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertTrue(recorded.headers["User-Agent"]!!.startsWith("Redde/"))
        val body = ReddeJson.parseToJsonElement(recorded.body!!.utf8())
        assertEquals("qwen", body["model"].str)
        val messages = body["messages"].arr!!
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"].str })
        assertTrue(messages[0]["content"].str!!.startsWith("Give measurements in US customary units"))
        assertEquals("And again?", messages[3]["content"].str)
        // The stand-in is on loopback: it counts as self-hosted.
        assertEquals("true", body["return_progress"].toString())
    }

    @Test
    fun anHttpErrorCarriesItsBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"bad key"}""").build())
        val error = assertFailsWith<TransportError.Http> { ChatCompletionsTransport(server.url("/"), null).stream(TurnRequest("x")).toList() }
        assertEquals(401, error.status)
        assertTrue("bad key" in error.body)
    }

    @Test
    fun aServerThatIsntThereIsUnreachable() = runBlocking {
        val url = server.url("/")
        server.close()
        assertFailsWith<TransportError.Unreachable> { ChatCompletionsTransport(url, null).stream(TurnRequest("x")).toList() }
        Unit
    }

    @Test
    fun aPictureGoesAsAPartAndAPdfIsRefused() {
        val picture = Attachment.of(Attachment.Kind.IMAGE, "a.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val content = ChatCompletionsTransport.makeContent("look", listOf(picture))
        assertEquals("text", content[0]["type"].str)
        assertEquals("look", content[0]["text"].str)
        assertEquals("data:image/jpeg;base64,AQID", content[1]["image_url"]["url"].str)
        val pdf = Attachment.of(Attachment.Kind.PDF, "a.pdf", "application/pdf", byteArrayOf(1))
        val error = assertFailsWith<AttachmentError.Unsupported> { ChatCompletionsTransport.makeContent("x", listOf(pdf)) }
        assertEquals("PDF", error.kind)
    }

    @Test
    fun aTextFileIsInlined() {
        val notes = Attachment.of(Attachment.Kind.TEXT, "notes.txt", "text/plain", "line one".toByteArray())
        val content = ChatCompletionsTransport.makeContent("read this", listOf(notes))
        assertEquals("read this\n\n--- notes.txt ---\nline one", content[0]["text"].str)
    }
}
