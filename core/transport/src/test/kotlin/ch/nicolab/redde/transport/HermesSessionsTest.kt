package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ApprovalRequest
import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.Interrupt
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.SubagentUpdate
import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.str
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from EchoTests/SSEParserTests.swift (SessionsStreamTests, AssistantCompletedTests,
 * SessionsApprovalTests) and EchoTests/HermesRunsTests.swift (the run decoding and bodies; the
 * fast-mode and file-placing tests move with Settings and ServerFiles).
 */
class HermesSessionsTest {
    private fun ev(name: String, json: String) = SseEvent(name, json)
    private fun decodeRun(json: String, id: String? = null) = HermesRuns.decode(SseEvent(null, json, id), "run_1")!!

    @Test
    fun decodesLedgerEvents() {
        val d = HermesSessionsTransport::decode
        assertEquals(listOf(TurnEvent.SessionId("abc"), TurnEvent.RunId("r1")), d(ev("run.started", """{"session_id":"abc","run_id":"r1"}""")).events)
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("Hel")), d(ev("assistant.delta", """{"message_id":"m","delta":"Hel"}""")).events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ReasoningDelta("hmm")), d(ev("tool.progress", """{"tool_name":"_thinking","delta":"hmm"}""")).events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolStarted("memory", "add")), d(ev("tool.started", """{"tool_name":"memory","preview":"add"}""")).events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolFinished("memory", failed = true)), d(ev("tool.failed", """{"tool_name":"memory"}""")).events)
        val done = d(ev("run.completed", """{"usage":{"input_tokens":50,"output_tokens":9}}"""))
        assertEquals(listOf<TurnEvent>(TurnEvent.Usage(TokenUsage(50, 9))), done.events)
        assertFalse(done.finished)
        assertTrue(d(ev("done", "{}")).finished)
        assertFailsWith<TransportError> { d(ev("error", """{"message":"boom"}""")) }
    }

    @Test
    fun completedCarriesTheResolvedImage() {
        val image = "![image](data:image/jpeg;base64,/9j/4AAQSkZJRg==)"
        val out = HermesSessionsTransport.decode(ev("assistant.completed", """{"message_id":"m","content":"Here it is $image","completed":true}"""))
        assertEquals(listOf<TurnEvent>(TurnEvent.TextFinal("Here it is $image")), out.events)
        assertFalse(out.finished, "`done` ends the stream, not this")
        assertTrue(HermesSessionsTransport.decode(ev("assistant.completed", """{"message_id":"m"}""")).events.isEmpty())
        assertTrue(HermesSessionsTransport.decode(ev("assistant.completed", """{"content":""}""")).events.isEmpty())
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("MEDIA:/tmp/snap.jpg")), HermesSessionsTransport.decode(ev("assistant.delta", """{"delta":"MEDIA:/tmp/snap.jpg"}""")).events)
    }

    @Test
    fun approvalRequestBecomesAnInterrupt() {
        val json = """{"request_id":"req1","command":"systemctl restart nginx","description":"Restart the web server","choices":["once","session","always","deny"],"run_id":"run_9","session_id":"s1"}"""
        val request = ApprovalRequest("req1", "systemctl restart nginx", "Restart the web server", listOf("once", "session", "always", "deny"))
        assertEquals(listOf<TurnEvent>(TurnEvent.Interrupted(Interrupt.Approval(request), "run_9")), HermesSessionsTransport.decode(ev("approval.request", json)).events)
        assertTrue(HermesSessionsTransport.decode(ev("approval.request", """{"command":"x"}""")).events.isEmpty())
    }

    @Test
    fun theOldLockCheckOnANamedEndpointIsNotAFailure() {
        val named = "confirmed model lock runtime mismatch: expected provider=custom:my-local model=beta; actual provider=custom model=beta"
        val keyed = "confirmed model lock runtime mismatch: expected provider=local model=beta; actual provider=custom model=beta"
        assertTrue(HermesSessionsTransport.isLockBucketMismatch(named))
        assertTrue(HermesSessionsTransport.isLockBucketMismatch(keyed))
        val result = HermesSessionsTransport.decode(ev("error", """{"message":"$named"}"""))
        assertTrue(result.events.isEmpty() && !result.finished)
        val otherModel = "confirmed model lock runtime mismatch: expected provider=custom:my-local model=beta; actual provider=custom model=alpha"
        val otherProvider = "confirmed model lock runtime mismatch: expected provider=anthropic model=claude-opus-5-5; actual provider=openrouter model=claude-opus-5-5"
        assertFalse(HermesSessionsTransport.isLockBucketMismatch(otherModel))
        assertFalse(HermesSessionsTransport.isLockBucketMismatch(otherProvider))
        assertFalse(HermesSessionsTransport.isLockBucketMismatch("No LLM provider configured."))
        assertFailsWith<TransportError> { HermesSessionsTransport.decode(ev("error", """{"message":"$otherModel"}""")) }
    }

    @Test
    fun delegatedTasksBecomeSubagentRows() {
        val started = HermesSessionsTransport.decode(ev("tool.started", """{"tool_name":"delegate_task","run_id":"r","args":{"tasks":[{"goal":"A"},{"goal":"B"}]}}""")).events
        assertEquals(3, started.size)
        assertEquals("delegate:r:1", (started[2] as TurnEvent.Subagent).update.id)
        val done = HermesSessionsTransport.decode(ev("tool.completed", """{"tool_name":"delegate_task","args":{"goal":"A"}}""")).events
        assertEquals(SubagentUpdate.Phase.Dispatched, (done[1] as TurnEvent.Subagent).update.phase)
    }

    // HermesRunsTests

    @Test
    fun wordsThinkingAndToolsAsTheyCome() {
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("Hel")), decodeRun("""{"event":"message.delta","run_id":"run_1","delta":"Hel"}""").events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ReasoningDelta("hmm")), decodeRun("""{"event":"reasoning.available","run_id":"run_1","text":"hmm"}""").events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolStarted("memory", "add")), decodeRun("""{"event":"tool.started","tool":"memory","preview":"add"}""").events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolFinished("memory", false, "saved")), decodeRun("""{"event":"tool.completed","tool":"memory","duration":0.2,"error":false,"preview":"saved"}""").events)
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolFinished("terminal", true)), decodeRun("""{"event":"tool.completed","tool":"terminal","error":true}""").events)
        assertTrue(decodeRun("""{"event":"message.interim","text":"x","already_streamed":true}""").events.isEmpty())
        assertTrue(decodeRun("""{"event":"run.steered","accepted":true}""").events.isEmpty())
    }

    @Test
    fun eventsAreNumberedOnHermes022() {
        assertEquals(7, decodeRun("""{"event":"message.delta","delta":"a","seq":7}""", id = "7").seq)
        assertEquals(12, decodeRun("""{"event":"message.delta","delta":"a"}""", id = "12").seq, "the id line alone")
        assertNull(decodeRun("""{"event":"message.delta","delta":"a"}""").seq, "0.21 numbers nothing")
        assertNull(HermesRuns.decode(SseEvent(null, "not json"), "run_1"))
    }

    @Test
    fun theRunsEndSaysHowItEnded() {
        val done = decodeRun("""{"event":"run.completed","output":"All done. MEDIA:/tmp/chart.png","usage":{"input_tokens":50,"output_tokens":9}}""")
        assertEquals(HermesRuns.Ending.Completed, done.ending)
        assertEquals(listOf(TurnEvent.TextFinal("All done. MEDIA:/tmp/chart.png"), TurnEvent.Usage(TokenUsage(50, 9))), done.events)
        assertEquals(HermesRuns.Ending.Failed("No LLM provider configured"), decodeRun("""{"event":"run.failed","error":"No LLM provider configured"}""").ending)
        assertEquals(HermesRuns.Ending.Stopped, decodeRun("""{"event":"run.cancelled"}""").ending)
        assertEquals(HermesRuns.Ending.Stopped, decodeRun("""{"event":"run.interrupted"}""").ending)
        val mismatch = decodeRun("""{"event":"run.failed","output":"[A:m]","error":"confirmed model lock runtime mismatch: expected provider=custom:lab model=m; actual provider=custom model=m"}""")
        assertTrue(mismatch.ending == HermesRuns.Ending.Completed && mismatch.events == listOf<TurnEvent>(TurnEvent.TextFinal("[A:m]")))
    }

    @Test
    fun approvalsAndSubagentsAsTheRunSaysThem() {
        val asked = decodeRun("""{"event":"approval.request","request_id":"req1","command":"rm -rf /tmp/x","choices":["once","deny"]}""")
        assertEquals(listOf<TurnEvent>(TurnEvent.Interrupted(Interrupt.Approval(ApprovalRequest("req1", "rm -rf /tmp/x", null, listOf("once", "deny"))), "run_1")), asked.events)
        val started = decodeRun("""{"event":"subagent.start","subagent_id":"sa1","goal":"Check the feed","task_index":0,"task_count":2}""")
        assertEquals(listOf<TurnEvent>(TurnEvent.Subagent(SubagentUpdate("sa1", "Check the feed", 0, 2, phase = SubagentUpdate.Phase.Started))), started.events)
        assertEquals(1, decodeRun("""{"event":"subagent.complete","subagent_id":"sa1","status":"success"}""").events.size)
    }

    @Test
    fun aRunsStatusWhenItsEventsAreGone() {
        fun status(json: String) = HermesRuns.decodeStatus(ReddeJson.parseToJsonElement(json), "run_1")
        assertEquals(HermesRuns.RunDecoded(), status("""{"status":"running"}"""))
        assertEquals(listOf<TurnEvent>(TurnEvent.TextFinal("Hi")), status("""{"status":"completed","output":"Hi"}""").events)
        assertEquals(HermesRuns.Ending.Completed, status("""{"status":"completed","output":"Hi"}""").ending)
        assertEquals(HermesRuns.Ending.Failed("boom"), status("""{"status":"failed","error":"boom"}""").ending)
        assertEquals(HermesRuns.Ending.Stopped, status("""{"status":"cancelled"}""").ending)
        val waiting = status("""{"status":"waiting_for_approval","approval":{"request_id":"r2","command":"sudo x","choices":["once","deny"]}}""")
        assertEquals(listOf<TurnEvent>(TurnEvent.Interrupted(Interrupt.Approval(ApprovalRequest("r2", "sudo x", null, listOf("once", "deny"))), "run_1")), waiting.events)
        assertNull(waiting.ending)
    }

    @Test
    fun aRunStartsWithOneMessageInItsSession() {
        val request = TurnRequest("What's this?", model = "beta", provider = "custom:lab", reasoningEffort = "high", replyLanguage = "nl", brief = true)
        val body = HermesSessionsTransport.runBody(request, "s1")
        assertEquals("s1", body["session_id"].str)
        assertEquals("beta", body["model"].str)
        assertEquals("custom:lab", body["provider"].str)
        assertEquals("high", body["model_options"]["reasoning_effort"].str)
        assertEquals(JsonPrimitive("What's this?"), body["input"])
        val instructions = body["instructions"].str!!
        assertTrue(instructions.startsWith(HermesSessionsTransport.CLIENT_HINT) && "Dutch" in instructions && "driving" in instructions)

        val picture = Attachment.of(Attachment.Kind.IMAGE, "p.jpg", "image/jpeg", byteArrayOf(1))
        val messages = HermesSessionsTransport.runBody(request.copy(attachments = listOf(picture)), "s1")["input"].arr!!
        assertEquals(1, messages.size, "one message: more would stand in for the session's history")
        assertEquals("user", messages[0]["role"].str)
        val parts = messages[0]["content"].arr!!
        assertTrue(parts.first()["type"].str == "text" && parts.first()["text"].str == "What's this?")
        assertTrue(parts.last()["type"].str == "image_url" && parts.last()["image_url"]["url"].str!!.startsWith("data:image/"))

        val plain = HermesSessionsTransport.runBody(TurnRequest("Hi"), "s1")
        assertTrue(plain["model"] == null && plain["model_options"] == null, "nothing picked: the host's default")
    }

    @Test
    fun troubleThatPassesIsWaitedOut() {
        assertTrue(HermesRuns.isTransient(java.net.SocketTimeoutException()))
        assertTrue(HermesRuns.isTransient(java.net.ConnectException()))
        assertTrue(HermesRuns.isTransient(TransportError.Http(502, "")))
        assertTrue(HermesRuns.isTransient(TransportError.StreamLost("x")))
        assertFalse(HermesRuns.isTransient(TransportError.Http(401, "")))
        assertFalse(HermesRuns.isTransient(TransportError.Malformed("x")))
    }

    @Test
    fun parsesModelOptions() {
        val json = ReddeJson.parseToJsonElement("""{"model":"m2","providers":[{"slug":"or","name":"OpenRouter","is_current":true,"models":["m1","m2",{"id":"m3","name":"Model 3"}]},{"id":"lab","models":["x"]}]}""")
        val choices = HermesSessionsApi.parseModelOptions(json)
        assertEquals(listOf("or/m1", "or/m2", "or/m3", "lab/x"), choices.map { it.id })
        assertEquals(listOf(false, true, false, false), choices.map { it.isCurrent })
        assertEquals("Model 3", choices[2].name)
        assertEquals("lab", choices[3].providerName)
    }

    @Test
    fun storedMessagesReadLeniently() {
        val m = StoredMessage.from(ReddeJson.parseToJsonElement("""{"id":42,"role":"assistant","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}],"timestamp":"1700000000.5","tool_calls":[{"function":{"name":"web","arguments":"{\"q\":1}"}}]}"""))
        assertEquals("42", m.id)
        assertEquals("a\nb", m.content)
        assertEquals(1_700_000_000.5, m.timestamp)
        assertEquals("web", m.toolCalls.single().name)
        val s = SessionSummary.from(ReddeJson.parseToJsonElement("""{"id":"s1","source":"telegram_bot","hidden":1,"archived":"false","preview":"hello"}"""))!!
        assertEquals(true, s.hidden)
        assertEquals(false, s.archived)
        assertEquals("hello", s.displayTitle)
        assertEquals("Telegram Bot", s.sourceLabel)
    }
}

/** Runs against a stand-in Hermes API. */
class HermesRunsStreamTest {
    private val server = MockWebServer()
    private val fast = HermesRuns.Timing(watchEveryMs = 60_000, settleMs = 10, pollStartMs = 10, pollMaxMs = 20)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private fun sse(vararg data: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body(data.joinToString("") { "data: $it\n\n" }).build()

    @Test
    fun aTurnIsARunFollowedToItsEnd() = runBlocking {
        server.enqueue(json("""{"run_id":"run_7","status":"started"}"""))
        server.enqueue(sse(
            """{"event":"message.delta","delta":"Hel","seq":1}""",
            """{"event":"message.delta","delta":"lo","seq":2}""",
            """{"event":"run.completed","output":"Hello","usage":{"input_tokens":5,"output_tokens":1},"seq":3}""",
        ))
        val transport = HermesSessionsTransport(server.url("/"), "k", mapOf("X-Proxy" to "p"), fast)
        val events = transport.stream(TurnRequest("Hi", sessionId = "s1", turnKey = "MSG-1")).toList()
        assertEquals(TurnEvent.SessionId("s1"), events[0])
        assertEquals(TurnEvent.RunId("run_7"), events[1])
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<TurnEvent.TextDelta>().map { it.text })
        assertEquals(TurnEvent.TextFinal("Hello"), events.first { it is TurnEvent.TextFinal })
        assertEquals(TurnEvent.Done, events.last())

        val start = server.takeRequest()
        assertEquals("/v1/runs", start.target)
        assertEquals("MSG-1", start.headers["Idempotency-Key"])
        assertEquals("Bearer k", start.headers["Authorization"])
        assertEquals("p", start.headers["X-Proxy"])
        assertEquals("s1", ReddeJson.parseToJsonElement(start.body!!.utf8())["session_id"].str)
        assertEquals("/v1/runs/run_7/events", server.takeRequest().target)
    }

    @Test
    fun aGatewayWithoutRunsGetsTheChatStream() = runBlocking {
        server.enqueue(json("{}", code = 404))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
            "event: assistant.delta\ndata: {\"delta\":\"Hi\"}\n\nevent: done\ndata: {}\n\n",
        ).build())
        val events = HermesSessionsTransport(server.url("/"), "k", timing = fast).stream(TurnRequest("Hi", sessionId = "s1", model = "m")).toList()
        assertEquals(listOf(TurnEvent.TextDelta("Hi"), TurnEvent.Done), events)
        server.takeRequest()
        val chat = server.takeRequest()
        assertEquals("/api/sessions/s1/chat/stream", chat.target)
        assertEquals("true", ReddeJson.parseToJsonElement(chat.body!!.utf8())["require_model_lock"].toString())
    }

    @Test
    fun eventsForTheFirstListenerOnlyAreFollowedByTheStatus() = runBlocking {
        server.enqueue(json("""{"run_id":"r"}"""))
        server.enqueue(json("{}", code = 404)) // 0.21.x: no events for this listener
        server.enqueue(json("""{"status":"running"}"""))
        server.enqueue(json("""{"status":"completed","output":"Whole reply"}"""))
        val events = HermesSessionsTransport(server.url("/"), "k", timing = fast).stream(TurnRequest("Hi", sessionId = "s1")).toList()
        assertEquals(TurnEvent.TextFinal("Whole reply"), events.first { it is TurnEvent.TextFinal })
        assertEquals(TurnEvent.Done, events.last())
    }

    @Test
    fun aFailedRunFailsTheTurn() = runBlocking {
        server.enqueue(json("""{"run_id":"r"}"""))
        server.enqueue(sse("""{"event":"run.failed","error":"No LLM provider configured"}"""))
        val error = assertFailsWith<TransportError.Malformed> {
            HermesSessionsTransport(server.url("/"), "k", timing = fast).stream(TurnRequest("Hi", sessionId = "s1")).toList()
        }
        assertEquals("No LLM provider configured", error.detail)
    }

    @Test
    fun noKeyNoTurn() = runBlocking {
        assertFailsWith<TransportError.MissingApiKey> { HermesSessionsTransport(server.url("/"), null).stream(TurnRequest("x", sessionId = "s")).toList() }
        Unit
    }

    @Test
    fun theLedgerIsListedAndChanged() = runBlocking {
        server.enqueue(json("""{"data":[{"id":"s1","title":"Plans","last_active":1700000000}],"has_more":false}"""))
        server.enqueue(json("{}"))
        server.enqueue(json("{}", code = 404))
        val api = HermesSessionsApi(server.url("/p/work/"), "k")
        assertEquals(listOf("Plans"), api.listSessions().map { it.displayTitle })
        api.updateSession("s1", pinned = true)
        assertNull(api.session("gone"))
        assertEquals("/p/work/api/sessions?limit=100&offset=0", server.takeRequest().target)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"pinned":true}""", patch.body!!.utf8())
    }
}
