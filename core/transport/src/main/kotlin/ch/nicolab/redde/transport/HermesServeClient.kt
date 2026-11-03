package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.bool
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.obj
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Where a Dashboard client connects and as whom. The app's settings are one. */
interface ServeEndpoint {
    val serveBaseUrl: HttpUrl?
    val serveUsername: String
    val profileName: String?
    /** Cloudflare Access, Pangolin and the person's own headers, for every request and the socket. */
    val accessHeaders: Map<String, String>
    /** The server was set up as a Dashboard with no sign-in. */
    val serveOpenAccess: Boolean get() = false
}

/**
 * JSON-RPC 2.0 over WebSocket to `hermes serve` (the Dashboard). One shared connection. Port of the
 * connection, login and RPC parts of HermesServeClient.swift.
 *
 * Auth (basic provider): `POST /auth/password-login` sets session cookies, `POST /api/auth/ws-ticket`
 * mints a 30 s single-use ticket, then `ws://…/api/ws?ticket=`. REST calls ride on the same cookies.
 * A Dashboard with no sign-in says `auth_required: false` and hands its session token to whoever
 * loads its page: REST calls carry it as `X-Hermes-Session-Token`, the socket as `?token=`. It is
 * held in memory only.
 *
 * Not ported yet: the browser sign-in (OAuth PKCE, `DashboardSignIn.swift`), and the REST helpers
 * past sessions and profiles (skills, files, toolsets, projects, plugins).
 */
class HermesServeClient(
    private val endpoint: ServeEndpoint,
    private val password: () -> String?,
    baseClient: OkHttpClient = StreamingHttp.client,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Connected : State
        data class Reconnecting(val attempt: Int) : State
        data class Failed(val message: String) : State
    }

    /** One server event: (type, session id, payload). Synthetic `connection.lost` / `connection.restored` too. */
    data class Event(val type: String, val sessionId: String?, val payload: JsonElement)

    class RpcError(val code: Int, override val message: String) : Exception("$message ($code)")

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val cookies = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this@HermesServeClient.cookies.merge(url.host, cookies) { old, new -> old.filter { o -> new.none { it.name == o.name } } + new }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host]?.filter { it.matches(url) } ?: emptyList()
    }

    /** Never follows a redirect to another origin with the session token or a bearer on it. */
    private val http: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the socket idles between turns; the ping watches it
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val connectLock = Mutex()
    private var socket: WebSocket? = null
    private var wantsConnection = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    /** Requests from the host still waiting on an answer: their JSON-RPC id and method. */
    private val openRequests = ConcurrentHashMap<String, Pair<JsonElement, String>>()

    /** Stored session id → this connection's runtime session id. */
    private val runtimeIds = ConcurrentHashMap<String, String>()
    private val pinnedEfforts = ConcurrentHashMap<String, String>()
    private val sessionProfiles = ConcurrentHashMap<String, String>()

    @Volatile private var authenticatedAt: Long? = null
    @Volatile private var pageToken: Pair<HttpUrl, String>? = null
    private val authTtlMs = 10 * 60 * 1000L

    private val baseUrl: HttpUrl? get() = endpoint.serveBaseUrl

    private val hasStoredLogin: Boolean get() = endpoint.serveUsername.isNotEmpty() && !password().isNullOrEmpty()

    // Connection

    suspend fun ensureConnected() {
        connectLock.withLock {
            if (_state.value == State.Connected && socket != null) return
            connectOnce()
            wantsConnection = true
        }
    }

    private suspend fun connectOnce() {
        val base = baseUrl ?: throw TransportError.BadUrl()
        if (_state.value !is State.Reconnecting) _state.value = State.Connecting
        try {
            val pass = socketPass(base)
            try {
                openSocket(base, pass)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A Dashboard with no sign-in that refused the page's token: it restarted since and made
                // a new one. The page once more, and once more only.
                val spent = (pass as? SocketPass.Token)?.token ?: throw e
                val fresh = rereadPage(base, spent) ?: throw e
                openSocket(base, SocketPass.Token(fresh))
            }
            // This client answers the Dashboard's own requests (approvals, questions, passwords).
            // Hermes 0.21.5 withholds them from a client that hasn't said so.
            val opened = socket
            runCatching { call("client.capabilities", buildJsonObject { put("server_requests", true) }, timeoutMs = 15_000) }
            if (opened == null || socket !== opened) throw TransportError.Unreachable("the connection to Hermes Dashboard closed")
            _state.value = State.Connected
        } catch (e: Exception) {
            teardown()
            if (e !is kotlinx.coroutines.CancellationException) _state.value = State.Failed(e.message ?: e.toString())
            throw e
        }
    }

    fun disconnect() {
        wantsConnection = false
        reconnectJob?.cancel(); reconnectJob = null
        teardown()
        authenticatedAt = null
        _state.value = State.Disconnected
    }

    /** The server's address or login changed: nothing learned about the old one is kept. */
    fun resetForServerChange() {
        authenticatedAt = null
        pageToken = null
        cookies.clear()
        disconnect()
    }

    /** Waits up to [timeoutMs] for the link to be up again; false at once when nobody wants it. */
    suspend fun waitForConnection(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (_state.value == State.Connected && socket != null) return@withTimeoutOrNull true
                if (!wantsConnection) return@withTimeoutOrNull false
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || !wantsConnection) return
        reconnectJob = scope.launch {
            var attempt = 0
            var wait = 1_000L
            while (isActive && wantsConnection) {
                attempt++
                _state.value = State.Reconnecting(attempt)
                try {
                    connectLock.withLock { connectOnce() }
                    listeners.forEach { it(Event("connection.restored", null, JsonNull)) }
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.value = State.Reconnecting(attempt)
                    delay(wait)
                    wait = minOf(wait * 2, 30_000)
                }
            }
        }
    }

    private fun teardown() {
        pingJob?.cancel(); pingJob = null
        socket?.close(1000, null); socket = null
        runtimeIds.clear()
        pinnedEfforts.clear()
        openRequests.clear() // the host sends the ones still waiting again when a session is resumed
        // The request left the socket; whether the host acted on it is unknown: a lost stream.
        for ((_, d) in pending) d.completeExceptionally(TransportError.StreamLost("the connection to Hermes Dashboard closed"))
        pending.clear()
    }

    // Login

    sealed interface SocketPass {
        data class Ticket(val ticket: String) : SocketPass
        data class Token(val token: String) : SocketPass
    }

    private fun request(url: HttpUrl): Request.Builder {
        val b = Request.Builder().url(url)
        for ((k, v) in endpoint.accessHeaders) b.header(k, v)
        return b
    }

    private suspend fun execute(request: Request): Pair<Int, String> =
        http.newCall(request).await().use { it.code to it.body.string() }

    private suspend fun login(base: HttpUrl) {
        if (!hasStoredLogin) return openLogin(base)
        authenticatedAt?.let { if (System.currentTimeMillis() - it < authTtlMs) return }
        // The cookie may still be live from an earlier session: a cheap probe before a real login.
        val probe = runCatching { execute(request(base.resolve("api/sessions", listOf("limit" to "1"))).build()) }.getOrNull()
        if (probe?.first == 200) {
            authenticatedAt = System.currentTimeMillis()
            return
        }
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", endpoint.serveUsername)
            put("password", password() ?: "")
        }
        val (status, text) = execute(request(base.resolvePath("auth/password-login")).post(body.toString().toRequestBody(StreamingHttp.JSON)).build())
        if (status !in 200..299) {
            // A Dashboard that took its login away has nothing to log in to.
            if (status != 401 && runCatching { asksForLogin(base) }.getOrNull() == false) throw DashboardSignInChange.NoLongerAsks
            throw TransportError.Http(status, if (status == 401) "login rejected" else text.take(200))
        }
        authenticatedAt = System.currentTimeMillis()
    }

    private suspend fun openLogin(base: HttpUrl) {
        if (pageToken?.first == base) return
        readPage(base)
    }

    private suspend fun readPage(base: HttpUrl): String {
        if (asksForLogin(base)) {
            throw if (endpoint.serveOpenAccess) DashboardSignInChange.NowAsksForLogin else TransportError.Malformed("Hermes Dashboard username/password not set in Settings")
        }
        val (status, page) = execute(request(base.resolvePath("/")).header("Accept", "text/html").build())
        SessionPage.hostRefusal(status, page)?.let { throw it }
        val token = SessionPage.token(page)
            ?: throw TransportError.Malformed("Hermes Dashboard says it has no sign-in, but its page at ${base.host} has no session token.")
        // Proven by a read the token alone opens: the conversation list.
        val (proved, said) = execute(request(base.resolve("api/sessions", listOf("limit" to "1"))).header(SessionPage.HEADER, token).build())
        SessionPage.hostRefusal(proved, said)?.let { throw it }
        if (proved !in 200..299) throw TransportError.Http(proved, said.take(200))
        pageToken = base to token
        authenticatedAt = System.currentTimeMillis()
        return token
    }

    private suspend fun rereadPage(base: HttpUrl, spent: String): String? {
        pageToken?.let { (url, token) -> if (url == base && token != spent) return token }
        pageToken = null
        val fresh = readPage(base)
        return if (fresh == spent) null else fresh
    }

    /** `GET api/status`: whether the Dashboard asks for a login. */
    suspend fun asksForLogin(base: HttpUrl): Boolean {
        val (status, text) = execute(request(base.resolvePath("api/status")).build())
        SessionPage.hostRefusal(status, text)?.let { throw it }
        if (status != 200) throw TransportError.Http(status, text.take(200))
        return parseJsonOrNull(text)["auth_required"].bool == true
    }

    private val usesPageToken: Boolean get() = pageToken != null && pageToken?.first == baseUrl

    private suspend fun socketPass(base: HttpUrl): SocketPass {
        login(base)
        if (usesPageToken) pageToken?.second?.let { return SocketPass.Token(it) }
        val (status, text) = send(request(base.resolvePath("api/auth/ws-ticket")).post("{}".toRequestBody(StreamingHttp.JSON)).build(), base)
        if (status !in 200..299) throw TransportError.Http(status, "ws-ticket")
        return SocketPass.Ticket(parseJsonOrNull(text)["ticket"].str ?: throw TransportError.Malformed("no ws ticket"))
    }

    /**
     * A REST request with the proof the connection has (the page's token), once more after a 401
     * with a fresh one: the page read again, or the login made again.
     */
    private suspend fun send(request: Request, base: HttpUrl): Pair<Int, String> {
        fun authorized(): Request = pageToken?.takeIf { usesPageToken }?.second?.let { SessionPage.apply(it, request) } ?: request
        val usedToken = pageToken?.takeIf { usesPageToken }?.second
        val first = execute(authorized())
        if (first.first != 401) return first
        when {
            usedToken != null -> if (rereadPage(base, usedToken) == null) return first
            authenticatedAt != null -> {
                authenticatedAt = null
                login(base)
            }
            else -> return first
        }
        return execute(authorized())
    }

    /** A REST call on the Dashboard, with the connection's login. */
    suspend fun rest(method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: JsonObject? = null): JsonElement {
        val base = baseUrl ?: throw TransportError.BadUrl()
        login(base)
        val b = request(base.resolve(path, query))
        b.method(method, body?.toString()?.toRequestBody(StreamingHttp.JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(StreamingHttp.JSON))
        val (status, text) = send(b.build(), base)
        if (status !in 200..299) throw TransportError.Http(status, text.take(300))
        return if (text.isBlank()) JsonNull else ReddeJson.parseToJsonElement(text)
    }

    // Socket

    private suspend fun openSocket(base: HttpUrl, pass: SocketPass) {
        val query = when (pass) {
            is SocketPass.Ticket -> "ticket" to pass.ticket
            is SocketPass.Token -> "token" to pass.token
        }
        val url = base.resolve("api/ws", listOf(query))
        val first = CompletableDeferred<Unit>()
        pingJob?.cancel()
        socket?.close(1001, null)
        lateinit var opened: WebSocket
        opened = http.newWebSocket(request(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                first.complete(Unit)
                parseJsonOrNull(text)?.let(::handle)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                first.completeExceptionally(t)
                socketClosed(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socketClosed(webSocket)
        })
        socket = opened
        // Wait for gateway.ready (or the first frame) so callers know the link is live; a host that
        // completes the handshake and then goes quiet must not hang forever.
        try {
            withTimeout(10_000) { first.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw TransportError.Unreachable("Hermes Dashboard didn't answer the WebSocket handshake")
        }
        pingJob = scope.launch {
            while (isActive) {
                delay(20_000)
                try {
                    call("gateway.ping", JsonObject(emptyMap()), timeoutMs = 10_000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Half-open socket: nobody answered. Drop it so the link is made again.
                    opened.cancel()
                    return@launch
                }
            }
        }
    }

    private fun socketClosed(webSocket: WebSocket) {
        if (socket !== webSocket) return
        teardown()
        _state.value = State.Disconnected
        listeners.forEach { it(Event("connection.lost", null, JsonNull)) }
        scheduleReconnect()
    }

    private fun handle(json: JsonElement) {
        val id = json["id"]
        val method = json["method"].str
        if (id != null && id !is JsonNull && method == null) {
            val key = key(id) ?: return
            val waiting = pending.remove(key) ?: return
            val error = json["error"]
            if (error != null && error !is JsonNull) waiting.completeExceptionally(RpcError(error["code"].int ?: 0, error["message"].str ?: "rpc error"))
            else waiting.complete(json["result"] ?: JsonNull)
            return
        }
        if (method == "event") {
            val params = json["params"]
            var event = Event(params["type"].str ?: "", params["session_id"].str, params["payload"] ?: JsonNull)
            // A question the host has stopped waiting on: the card goes, as with `<kind>.expire`.
            if (event.type == "request.cancel") {
                val k = key(event.payload["id"])
                val open = k?.let { openRequests.remove(it) }
                if (open != null) event = Event("${open.second}.expire", event.sessionId, buildJsonObject { put("request_id", k) })
            }
            listeners.forEach { it(event) }
            return
        }
        if (id == null || method == null) return
        val k = key(id)
        val event = k?.let { prompt(method, it, json["params"]) }
        if (event != null) {
            openRequests[k] = id to method
            listeners.forEach { it(event) }
        } else {
            // A host request we can't serve (terminal, file dialogs): decline politely.
            sendFrame(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject { put("code", -32601); put("message", "Method not supported on Redde") })
            })
        }
    }

    /** Answers a host request (Hermes 0.21.3+) on its own id. False when it isn't open any more. */
    fun answer(requestId: String, result: JsonObject): Boolean {
        val (id, _) = openRequests.remove(requestId) ?: return false
        sendFrame(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
        return true
    }

    /** Forgets an open request settled another way (the last answer of a batch). */
    fun forgetOpenRequest(requestId: String) {
        openRequests.remove(requestId)
    }

    /** Whether the host asked [requestId] as a request of its own (0.21.3+) rather than a notification (0.21.0). */
    fun isOpenRequest(requestId: String): Boolean = openRequests.containsKey(requestId)

    /**
     * The requests a resumed session is still waiting on (`open_requests`), put to the listeners as
     * if just asked; 0.21.0's `pending_approval` too. How many there were.
     */
    fun replayOpenRequests(resumed: JsonElement, includingKnown: Boolean = false): Int {
        val session = resumed["session_id"].str
        val open = (resumed["open_requests"] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())
        for (request in open) {
            val id = request["id"] ?: continue
            val k = key(id) ?: continue
            if (!includingKnown && openRequests.containsKey(k)) continue
            val method = request["method"].str ?: continue
            val event = prompt(method, k, request["params"])?.let { if (session != null) it.copy(sessionId = session) else it } ?: continue
            openRequests[k] = id to method
            listeners.forEach { it(event) }
        }
        if (open.isEmpty()) {
            val pendingApproval = resumed["pending_approval"]
            if (pendingApproval["request_id"].str != null) {
                listeners.forEach { it(Event("approval.request", session, pendingApproval!!)) }
                return 1
            }
        }
        return open.size
    }

    // RPC

    /** The methods a profile is added to (the Dashboard serves every profile on one connection). */
    private val profileScopedMethods = setOf(
        "session.create", "session.resume", "session.delete", "session.branch",
        "projects.tree", "projects.project_sessions", "projects.create", "projects.update", "projects.delete",
        "model.options", "config.set", "slash.exec", "command.dispatch", "session.workspace.move", "plugins.manage",
    )

    fun withProfile(params: JsonElement, method: String): JsonElement {
        val fields = params.obj ?: return params
        if (method !in profileScopedMethods || "profile" in fields) return params
        val id = (fields["session_id"] ?: fields["session_key"]).str
        val sessionProfile = id?.let { sid -> sessionProfiles[sid] ?: runtimeIds.entries.firstOrNull { it.value == sid }?.let { sessionProfiles[it.key] } }
        val profile = sessionProfile ?: endpoint.profileName ?: return params
        return JsonObject(fields + ("profile" to JsonPrimitive(profile)))
    }

    fun noteProfile(profile: String, stored: String) {
        sessionProfiles[stored] = profile
    }

    fun adopt(runtime: String, stored: String) {
        runtimeIds[stored] = runtime
    }

    fun runtimeId(stored: String): String? = runtimeIds[stored]

    fun isSession(sessionId: String?, stored: String): Boolean = sessionId != null && (sessionId == stored || runtimeIds[stored] == sessionId)

    suspend fun call(method: String, params: JsonElement, timeoutMs: Long = 60_000): JsonElement {
        // No socket means the request never left the phone: safe for the caller to send again.
        if (socket == null) throw TransportError.Unreachable("not connected to Hermes Dashboard")
        val id = "echo-${nextId.getAndIncrement()}"
        val waiting = CompletableDeferred<JsonElement>()
        pending[id] = waiting
        sendFrame(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", withProfile(params, method))
        }, forCall = id)
        try {
            return withTimeoutOrNull(timeoutMs) { waiting.await() } ?: throw TransportError.Malformed("$method timed out")
        } finally {
            pending.remove(id)
        }
    }

    private fun sendFrame(frame: JsonElement, forCall: String? = null) {
        val sent = socket?.send(frame.toString()) ?: false
        if (!sent && forCall != null) pending.remove(forCall)?.completeExceptionally(TransportError.Unreachable("not connected to Hermes Dashboard"))
    }

    fun addListener(listener: (Event) -> Unit): (Event) -> Unit = listener.also { listeners += it }

    fun removeListener(listener: (Event) -> Unit) {
        listeners -= listener
    }

    // Sessions

    /**
     * A session to run a turn in: [stored] resumed (its runtime id), or a new one made. "desktop" as
     * the source, not "mobile": it picks the platform hint in the agent's system prompt, and the
     * desktop hint teaches what Redde renders (full markdown, files as MEDIA tags).
     */
    suspend fun openSession(
        stored: String?, model: String? = null, provider: String? = null, reasoningEffort: String? = null,
        folder: String? = null, hidden: Boolean = false, profile: String? = null,
    ): Pair<String, String> {
        if (stored != null) {
            val runtime = runtimeIds[stored] ?: run {
                val result = call("session.resume", buildJsonObject { put("session_id", stored); put("omit_messages", true) })
                (result["session_id"].str ?: throw TransportError.Malformed("resume: no session id")).also { runtimeIds[stored] = it }
            }
            // A resumed session runs at the level that chat last used: the first turn re-pins the phone's pick.
            if (!reasoningEffort.isNullOrEmpty() && pinnedEfforts[runtime] != reasoningEffort) {
                runCatching { setSessionReasoning(runtime, reasoningEffort) }
            }
            return runtime to stored
        }
        val params = buildJsonObject {
            put("source", "desktop")
            put("close_on_disconnect", false)
            model?.ifEmpty { null }?.let { put("model", it) }
            provider?.ifEmpty { null }?.let { put("provider", it) }
            reasoningEffort?.ifEmpty { null }?.let { put("reasoning_effort", it) }
            folder?.ifEmpty { null }?.let { put("cwd", it) }
            if (hidden) put("hidden", true)
            profile?.let { put("profile", it) }
        }
        val result = call("session.create", params)
        val runtime = result["session_id"].str ?: throw TransportError.Malformed("create: no session id")
        val storedId = result["stored_session_id"].str ?: runtime
        profile?.let { noteProfile(it, storedId) }
        runtimeIds[storedId] = runtime
        if (!reasoningEffort.isNullOrEmpty()) pinnedEfforts[runtime] = reasoningEffort
        return runtime to storedId
    }

    /** `session.resume`: the session's runtime id, whether it runs, and with [withMessages] its history. */
    suspend fun resume(stored: String, withMessages: Boolean): JsonElement {
        val result = call("session.resume", buildJsonObject { put("session_id", stored); put("omit_messages", !withMessages) },
            timeoutMs = if (withMessages) 180_000 else 60_000)
        result["session_id"].str?.let { runtimeIds[stored] = it }
        return result
    }

    suspend fun setSessionReasoning(runtimeSession: String, effort: String) {
        call("config.set", buildJsonObject { put("session_id", runtimeSession); put("key", "reasoning"); put("value", effort) })
        pinnedEfforts[runtimeSession] = effort
    }

    /** "<model> --provider <p>": the value `config.set model` takes. */
    fun modelSwitchValue(model: String, provider: String?): String =
        if (provider.isNullOrEmpty()) model else "$model --provider $provider"

    suspend fun setSessionModel(runtimeSession: String, model: String, provider: String? = null) {
        call("config.set", buildJsonObject { put("session_id", runtimeSession); put("key", "model"); put("value", modelSwitchValue(model, provider)) })
    }

    /** The session list, newest first. */
    suspend fun listSessions(limit: Int = 100): List<SessionSummary> {
        val json = rest("GET", "api/sessions", listOf("limit" to "$limit", "offset" to "0", "order" to "recent"))
        return ((json["sessions"] ?: json["data"]) as? kotlinx.serialization.json.JsonArray)?.mapNotNull(SessionSummary::from) ?: emptyList()
    }

    /** A stored session's transcript without resuming it. */
    suspend fun transcript(stored: String): List<StoredMessage> =
        (rest("GET", "api/sessions/$stored/messages", listOf("order" to "latest", "limit" to "500"))["messages"] as? kotlinx.serialization.json.JsonArray)
            ?.map(StoredMessage::from) ?: emptyList()

    suspend fun updateSession(id: String, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null, unread: Boolean? = null) {
        rest("PATCH", "api/sessions/$id", body = buildJsonObject {
            title?.let { put("title", it) }
            pinned?.let { put("pinned", it) }
            archived?.let { put("archived", it) }
            unread?.let { put("unread", it) }
        })
    }

    suspend fun modelOptions(): List<ch.nicolab.redde.model.ModelChoice> =
        HermesSessionsApi.parseModelOptions(call("model.options", buildJsonObject { put("explicit_only", true) }))

    companion object {
        val PROMPT_METHODS = setOf("approval", "clarify", "sudo", "secret")

        fun key(id: JsonElement?): String? = id.str ?: id.int?.toString()

        /** A host request as the event the older hosts sent for it (`<method>.request`, with `request_id`). */
        fun prompt(method: String, id: String, params: JsonElement?): Event? {
            if (method !in PROMPT_METHODS) return null
            val payload = (params.obj ?: JsonObject(emptyMap())) + ("request_id" to JsonPrimitive(id))
            return Event("$method.request", params["session_id"].str, JsonObject(payload))
        }

    }
}

// Answers. Each goes back the way its question came: the response frame for a request (Hermes
// 0.21.3 and later), the `<kind>.respond` call for a notification (0.21.0).

suspend fun HermesServeClient.respondApproval(runtimeSession: String, requestId: String, choice: String) {
    if (answer(requestId, buildJsonObject { put("choice", choice) })) return
    call("approval.respond", buildJsonObject { put("session_id", runtimeSession); put("request_id", requestId); put("choice", choice) })
}

suspend fun HermesServeClient.respondClarify(runtimeSession: String, requestId: String, questionId: String?, answer: String) {
    if (isOpenRequest(requestId)) {
        if (questionId.isNullOrEmpty()) {
            answer(requestId, buildJsonObject { put("answer", answer) })
            return
        }
        // A batch is answered a question at a time; the last one settles the request.
        val result = call("clarify.lock", buildJsonObject { put("request_id", requestId); put("question_id", questionId); put("answer", answer) })
        if ((result["remaining"] as? kotlinx.serialization.json.JsonArray).isNullOrEmpty()) forgetOpenRequest(requestId)
        return
    }
    call("clarify.respond", buildJsonObject {
        put("session_id", runtimeSession); put("request_id", requestId); put("answer", answer)
        if (!questionId.isNullOrEmpty()) put("question_id", questionId)
    })
}

suspend fun HermesServeClient.respondSudo(runtimeSession: String, requestId: String, password: String) {
    if (answer(requestId, buildJsonObject { put("value", password) })) return
    call("sudo.respond", buildJsonObject { put("session_id", runtimeSession); put("request_id", requestId); put("password", password) })
}

suspend fun HermesServeClient.respondSecret(runtimeSession: String, requestId: String, value: String) {
    if (answer(requestId, buildJsonObject { put("value", value) })) return
    call("secret.respond", buildJsonObject { put("session_id", runtimeSession); put("request_id", requestId); put("value", value) })
}

/** Stops the turn on the host, without waiting for the answer. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun HermesServeClient.interrupt(runtimeSession: String) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { runCatching { call("session.interrupt", buildJsonObject { put("session_id", runtimeSession) }) } }
}
