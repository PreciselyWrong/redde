package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Interrupt
import ch.nicolab.redde.model.MeasureUnits
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.str
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Dashboard's decoding, and whole turns against a stand-in Dashboard. */
class HermesServeTest {
    private val server = MockWebServer()
    private val frames = CopyOnWriteArrayList<JsonElement>()
    private val requests = CopyOnWriteArrayList<String>()

    @AfterTest
    fun stop() = server.close()

    /** A stand-in Dashboard with no sign-in, whose agent answers [reply] (or asks first, with [ask]). */
    private fun startDashboard(reply: String, ask: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.target}"
                val path = request.url.encodedPath
                return when {
                    path == "/api/status" -> MockResponse.Builder().body("""{"auth_required":false,"version":"0.22.0"}""").build()
                    path == "/" -> MockResponse.Builder().body("""<script>window.__HERMES_SESSION_TOKEN__="tok-1";</script>""").build()
                    path == "/api/sessions" && request.headers[SessionPage.HEADER] == "tok-1" -> MockResponse.Builder().body("""{"sessions":[]}""").build()
                    path == "/api/sessions" -> MockResponse.Builder().code(401).build()
                    path == "/api/ws" && request.url.queryParameter("token") == "tok-1" -> MockResponse.Builder().webSocketUpgrade(agent(reply, ask)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun agent(reply: String, ask: Boolean) = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = ReddeJson.parseToJsonElement(text)
            frames += frame
            val id = frame["id"]
            fun result(r: String) = webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":$r}""")
            fun event(type: String, payload: String) =
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt-1","payload":$payload}}""")
            when (frame["method"].str) {
                "client.capabilities" -> result("{}")
                "session.create" -> result("""{"session_id":"rt-1","stored_session_id":"st-1"}""")
                "session.resume" -> {
                    result("""{"session_id":"rt-1","running":true,"inflight":{"user":"Long job?","assistant":"Work"}}""")
                    event("message.delta", """{"text":"ing on it."}""")
                    event("message.complete", """{"text":"Working on it."}""")
                }
                "prompt.submit" -> {
                    result("""{"status":"ok"}""")
                    if (ask) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"host-7","method":"approval","params":{"session_id":"rt-1","command":"rm x","choices":["once","deny"]}}""")
                    } else {
                        event("message.delta", """{"text":"$reply"}""")
                        event("message.complete", """{"text":"$reply","usage":{"input":10,"output":2,"context_used":12,"context_max":100}}""")
                    }
                }
                null -> if (id?.str == "host-7") {
                    // The approval answered on its own id: the agent goes on.
                    event("message.delta", """{"text":"$reply"}""")
                    event("message.complete", """{"text":"$reply"}""")
                }
            }
        }
    }

    private fun endpoint(url: HttpUrl) = object : ServeEndpoint {
        override val serveBaseUrl = url
        override val serveUsername = ""
        override val profileName: String? = "work"
        override val accessHeaders = mapOf("X-Proxy" to "p")
        override val serveOpenAccess = true
    }

    @Test
    fun aTurnOnADashboardWithNoSignIn() = runBlocking {
        startDashboard("Hello!")
        val client = HermesServeClient(endpoint(server.url("/")), password = { null })
        val events = withTimeout(20_000) {
            HermesServeTransport(client).stream(TurnRequest("Hi", units = MeasureUnits.US)).toList()
        }
        assertEquals(TurnEvent.SessionId("st-1"), events.first())
        assertEquals(listOf("Hello!"), events.filterIsInstance<TurnEvent.TextDelta>().map { it.text })
        assertEquals(12, events.filterIsInstance<TurnEvent.Usage>().single().usage.contextUsed)
        assertEquals(TurnEvent.Done, events.last())

        val methods = frames.mapNotNull { it["method"].str }
        assertEquals(listOf("client.capabilities", "session.create", "prompt.submit"), methods)
        val create = frames.first { it["method"].str == "session.create" }["params"]
        assertEquals("desktop", create["source"].str)
        assertEquals("work", create["profile"].str, "the profile goes with profile-scoped methods")
        val submit = frames.first { it["method"].str == "prompt.submit" }["params"]
        assertEquals("rt-1", submit["session_id"].str)
        assertEquals("Hi\n\n(Use US units.)", submit["text"].str, "the notes ride on the message")
        assertTrue(requests.any { it.startsWith("GET /api/ws?token=tok-1") }, "$requests")
        client.disconnect()
    }

    @Test
    fun anApprovalIsAnsweredOnItsOwnId() = runBlocking {
        startDashboard("Done.", ask = true)
        val client = HermesServeClient(endpoint(server.url("/")), password = { null })
        val events = withTimeout(20_000) {
            val collected = mutableListOf<TurnEvent>()
            HermesServeTransport(client).stream(TurnRequest("Clean up")).collect { e ->
                collected += e
                if (e is TurnEvent.Interrupted) {
                    val approval = e.interrupt as Interrupt.Approval
                    assertEquals("rm x", approval.request.command)
                    assertEquals("host-7", approval.request.id)
                    client.respondApproval(e.runtimeSession, approval.request.id, "once")
                }
            }
            collected
        }
        assertEquals("Done.", events.filterIsInstance<TurnEvent.TextDelta>().single().text)
        val answer = frames.first { it["id"].str == "host-7" }
        assertEquals(JsonPrimitive("once"), answer["result"]["choice"])
        client.disconnect()
    }

    @Test
    fun aTurnRunningOnTheHostIsJoined() = runBlocking {
        startDashboard("unused")
        val client = HermesServeClient(endpoint(server.url("/")), password = { null })
        val turn = withTimeout(20_000) { HermesServeTransport(client).rejoin("st-1") }!!
        assertEquals("Long job?", turn.question)
        val events = withTimeout(20_000) { turn.events.toList() }
        assertEquals("Working on it.", events.filterIsInstance<TurnEvent.TextDelta>().joinToString("") { it.text },
            "what the reply said so far, then what came while the host answered")
        assertEquals(TurnEvent.Done, events.last())
        client.disconnect()
    }

    @Test
    fun decodesTheSessionsEvents() {
        val d = { type: String, json: String -> HermesServeTransport.decode(type, ReddeJson.parseToJsonElement(json), "rt", nothingStreamed = true) }
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolStarted("web", "redde", null)), d("tool.start", """{"name":"web","context":"redde"}"""))
        assertEquals(listOf<TurnEvent>(TurnEvent.ToolFinished("web", true, "boom")), d("tool.complete", """{"name":"web","error":"boom"}"""))
        assertEquals(listOf<TurnEvent>(TurnEvent.InterruptExpired("r1")), d("clarify.expire", """{"request_id":"r1"}"""))
        val clarify = (d("clarify.request", """{"request_id":"r2","questions":[{"qid":"a","question":"Which?","choices":["x","y"]}]}""").single() as TurnEvent.Interrupted).interrupt as Interrupt.Clarify
        assertTrue(clarify.request.isBatch)
        assertEquals("a", clarify.request.questions.single().id)
        val todos = d("todo.updated", """{"todos":[{"id":"1","content":"Plan","status":"in_progress"},{"id":"2","content":"Do","parent":"1"},{"id":"3","parent":"9"}]}""").single() as TurnEvent.Todos
        assertEquals(listOf(null, "1", null), todos.items.map { it.parent }, "a parent that isn't there is dropped")
        assertEquals("(no description)", todos.items[2].content)
        assertEquals("high", HermesServeTransport.reasoningLevel("reasoning high"))
        assertNull(HermesServeTransport.reasoningLevel("reasoning high --global"))
        assertEquals("look\n\n@file:a.txt", HermesServeTransport.prompt("look", listOf("@file:a.txt")))
    }

    @Test
    fun aHostRequestBecomesTheEventOlderHostsSent() {
        val event = HermesServeClient.prompt("sudo", "7", buildJsonObject { put("session_id", "rt") })!!
        assertEquals("sudo.request", event.type)
        assertEquals("rt", event.sessionId)
        assertEquals("7", event.payload["request_id"].str)
        assertNull(HermesServeClient.prompt("terminal.read", "8", null))
    }

    @Test
    fun readsThePagesToken() {
        assertEquals("abc", SessionPage.token("""<script>window.__HERMES_SESSION_TOKEN__ = "abc";</script>"""))
        assertEquals("a\"b", SessionPage.token("""__HERMES_SESSION_TOKEN__='a\"b'"""))
        assertNull(SessionPage.token("""__HERMES_SESSION_TOKEN__=""""))
        assertNull(SessionPage.token("no token here"))
        assertEquals(DashboardSignInChange.AddressRefused, SessionPage.hostRefusal(400, "Invalid Host header"))
    }
}
