package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ModelChoice
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.bool
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.num
import ch.nicolab.redde.model.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A flag a server may send as true/false, 0/1 or a string of either, and may leave out. */
fun JsonElement?.looseBool(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return p.content.lowercase() in setOf("1", "true", "yes")
    p.content.toBooleanStrictOrNull()?.let { return it }
    return p.content.toDoubleOrNull()?.let { it != 0.0 }
}

/** A row of the session ledger, as the Hermes API and the Dashboard list it. */
data class SessionSummary(
    val id: String,
    val source: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val model: String? = null,
    val messageCount: Int? = null,
    /** Epoch seconds. */
    val startedAt: Double? = null,
    val lastActive: Double? = null,
    val pinned: Boolean? = null,
    val toolCallCount: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
    val actualCostUsd: Double? = null,
    /** The folder the session works in, where the list says (the Dashboard's does): which project it is in. */
    val cwd: String? = null,
    /** A reply here since it was last marked read. Only the Dashboard's list says. */
    val unread: Boolean? = null,
    /** The profile it belongs to, where the list says (the Dashboard's does). */
    val profile: String? = null,
    /** Put aside, kept out of every app's list; read loosely (SQLite's 0 and 1 come through). */
    val archived: Boolean? = null,
    val hidden: Boolean? = null,
) {
    val totalTokens: Int get() = (inputTokens ?: 0) + (outputTokens ?: 0)
    val cost: Double? get() = actualCostUsd ?: estimatedCostUsd

    val displayTitle: String
        get() = title?.ifEmpty { null } ?: preview?.ifEmpty { null }?.take(80) ?: id

    /** Epoch seconds of the last activity, or the start. */
    val lastActiveSeconds: Double? get() = lastActive ?: startedAt

    /** A run of a scheduled job: Hermes makes a session of each one. */
    val isScheduledRun: Boolean get() = source == "cron"

    val sourceLabel: String
        get() = when (val s = source ?: "") {
            "api_server", "api-server" -> "Redde / API"
            "cli" -> "CLI"
            "" -> "?"
            else -> s.replace('_', ' ').split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        }

    companion object {
        fun from(json: JsonElement?): SessionSummary? {
            val id = json["id"].str ?: json["id"].int?.toString() ?: return null
            return SessionSummary(
                id = id,
                source = json["source"].str,
                title = json["title"].str,
                preview = json["preview"].str,
                model = json["model"].str,
                messageCount = json["message_count"].int,
                startedAt = json["started_at"].num,
                lastActive = json["last_active"].num,
                pinned = json["pinned"].looseBool(),
                toolCallCount = json["tool_call_count"].int,
                inputTokens = json["input_tokens"].int,
                outputTokens = json["output_tokens"].int,
                estimatedCostUsd = json["estimated_cost_usd"].num,
                actualCostUsd = json["actual_cost_usd"].num,
                cwd = json["cwd"].str,
                unread = json["unread"].looseBool(),
                profile = json["profile"].str,
                archived = json["archived"].looseBool(),
                hidden = json["hidden"].looseBool(),
            )
        }
    }
}

/**
 * One stored message. Read leniently, field by field: the gateway returns its database rows nearly
 * as they are, and one field of an unexpected type (the row `id` is a number) must not fail the
 * whole transcript.
 */
data class StoredMessage(
    val id: String? = null,
    val role: String = "",
    /** The content as text: a string, or the parts' texts joined by newlines. */
    val content: String? = null,
    val timestamp: Double? = null,
    val reasoning: String? = null,
    val reasoningContent: String? = null,
    val displayKind: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolName: String? = null,
) {
    /** A call the message made; `arguments` is JSON, usually as a string. */
    data class ToolCall(val name: String?, val arguments: JsonElement?)

    companion object {
        fun from(json: JsonElement?): StoredMessage {
            val content = when (val c = json["content"]) {
                is JsonPrimitive -> c.str
                is JsonArray -> c.mapNotNull { it["text"].str }.joinToString("\n")
                else -> null
            }
            return StoredMessage(
                id = json["id"].str ?: json["id"].int?.toString(),
                role = json["role"].str ?: "",
                content = content,
                timestamp = json["timestamp"].num ?: json["timestamp"].str?.toDoubleOrNull(),
                reasoning = json["reasoning"].str,
                reasoningContent = json["reasoning_content"].str,
                displayKind = json["display_kind"].str,
                toolCalls = json["tool_calls"].arr?.map { ToolCall(it["function"]["name"].str, it["function"]["arguments"]) } ?: emptyList(),
                toolName = json["tool_name"].str,
            )
        }
    }
}

data class Toolset(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val configured: Boolean? = null,
    val tools: List<String> = emptyList(),
)

data class Skill(val name: String, val description: String? = null, val category: String? = null, val enabled: Boolean? = null)

/**
 * The non-streaming half of the Hermes API's ledger: list, read, create, delete sessions; models,
 * toolsets and skills; a run's status, steer and approval.
 */
class HermesSessionsApi(
    private val baseUrl: HttpUrl,
    private val apiKey: String,
    /** The person's own headers for this server (a reverse proxy's), sent with each request. */
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = StreamingHttp.client,
) {
    /**
     * [title] with [includeHidden]: the sessions of exactly that title, hidden ones too (a bot's chat;
     * Hermes takes `include_hidden` only with a title).
     */
    suspend fun listSessions(limit: Int = 100, offset: Int = 0, title: String? = null, includeHidden: Boolean = false): List<SessionSummary> {
        val query = mutableListOf("limit" to "$limit", "offset" to "$offset")
        if (title != null) {
            query += "title" to title
            if (includeHidden) query += "include_hidden" to "true"
        }
        return requestJson("GET", "api/sessions", query)["data"].arr?.mapNotNull(SessionSummary::from) ?: emptyList()
    }

    /** One session by its id, archived ones too (the list leaves those out); null when it's gone. */
    suspend fun session(id: String): SessionSummary? =
        try {
            SessionSummary.from(requestJson("GET", "api/sessions/$id")["session"])
        } catch (e: TransportError.Http) {
            if (e.status == 404) null else throw e
        }

    /**
     * A session's transcript, oldest first: its newest 500 messages, Hermes's most for a page
     * (`latest` pages back from the newest and still answers in order).
     */
    suspend fun messages(sessionId: String): List<StoredMessage> = newestMessages(sessionId, 500)

    suspend fun newestMessages(sessionId: String, limit: Int): List<StoredMessage> =
        requestJson("GET", "api/sessions/$sessionId/messages", listOf("order" to "latest", "limit" to "$limit"))["data"].arr
            ?.map(StoredMessage::from) ?: emptyList()

    suspend fun createSession(title: String?, model: String? = null, provider: String? = null, reasoningEffort: String? = null): SessionSummary {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            model?.let { put("model", it) }
            provider?.let { put("provider", it) }
            reasoningEffort?.let { put("model_options", buildJsonObject { put("reasoning_effort", it) }) }
        }
        return SessionSummary.from(requestJson("POST", "api/sessions", body = body)["session"])
            ?: throw TransportError.Malformed("no session in the reply")
    }

    /** Inject guidance into a running turn. False (409) when the run no longer accepts steers. */
    suspend fun steer(runId: String, text: String): Boolean =
        try {
            requestJson("POST", "v1/runs/$runId/steer", body = buildJsonObject { put("text", text) })
            true
        } catch (e: TransportError.Http) {
            if (e.status == 409) false else throw e
        }

    /** Pin an open session to a model. The provider goes as the gateway lists it. */
    suspend fun lockSessionModel(id: String, model: String, provider: String?) {
        requestJson("POST", "api/sessions/$id/model", body = buildJsonObject {
            put("model", model)
            provider?.ifEmpty { null }?.let { put("provider", it) }
        })
    }

    /** Answer a pending tool approval on a run. [choice] is once | session | always | deny. */
    suspend fun respondApproval(runId: String, requestId: String?, choice: String) {
        requestJson("POST", "v1/runs/$runId/approval", body = buildJsonObject {
            put("choice", choice)
            requestId?.ifEmpty { null }?.let { put("request_id", it) }
        })
    }

    /** Rename / pin / archive / mark read or unread. Only the given fields are sent. */
    suspend fun updateSession(id: String, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null, unread: Boolean? = null, hidden: Boolean? = null) {
        requestJson("PATCH", "api/sessions/$id", body = buildJsonObject {
            title?.let { put("title", it) }
            pinned?.let { put("pinned", it) }
            archived?.let { put("archived", it) }
            unread?.let { put("unread", it) }
            hidden?.let { put("hidden", it) }
        })
    }

    /** What has become of a run: queued, running, waiting_for_approval, stopping, or how it ended. Null when the host no longer knows it. */
    suspend fun runStatus(runId: String): String? =
        try {
            requestJson("GET", "v1/runs/$runId")["status"].str
        } catch (e: TransportError.Http) {
            if (e.status == 404) null else throw e
        }

    /** Branch a session: a new one carrying a copy of the transcript. */
    suspend fun forkSession(id: String, title: String?): SessionSummary =
        SessionSummary.from(requestJson("POST", "api/sessions/$id/fork", body = buildJsonObject { title?.let { put("title", it) } })["session"])
            ?: throw TransportError.Malformed("no session in the reply")

    suspend fun deleteSession(id: String) {
        requestJson("DELETE", "api/sessions/$id")
    }

    /** Models the gateway can route to, grouped by provider. */
    suspend fun modelOptions(): List<ModelChoice> = parseModelOptions(requestJson("GET", "api/model/options"))

    suspend fun toolsets(): List<Toolset> =
        requestJson("GET", "v1/toolsets")["data"].arr?.mapNotNull { t ->
            val name = t["name"].str ?: return@mapNotNull null
            Toolset(name, t["label"].str, t["description"].str, t["enabled"].bool, t["configured"].bool, t["tools"].arr?.mapNotNull { it.str } ?: emptyList())
        } ?: emptyList()

    suspend fun skills(): List<Skill> =
        requestJson("GET", "v1/skills")["data"].arr?.mapNotNull { s ->
            val name = s["name"].str ?: return@mapNotNull null
            Skill(name, s["description"].str, s["category"].str, s["enabled"].bool)
        } ?: emptyList()

    /** Generic bearer-authenticated JSON call against the API server; [JsonNull] for an empty body. */
    suspend fun requestJson(method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: JsonObject? = null): JsonElement {
        val text = send(method, path, query, body)
        return if (text.isBlank()) JsonNull else ReddeJson.parseToJsonElement(text)
    }

    private suspend fun send(method: String, path: String, query: List<Pair<String, String>>, body: JsonObject?): String {
        val builder = Request.Builder().url(baseUrl.resolve(path, query))
        for ((name, value) in headers) builder.header(name, value)
        builder.header("Authorization", "Bearer $apiKey")
        val requestBody = body?.toString()?.toRequestBody(StreamingHttp.JSON)
        builder.method(method, requestBody ?: if (method == "POST" || method == "PATCH" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
        val timeoutSeconds = if (method == "GET") 15L else 20L
        val call = client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(builder.build())
        call.await().use { r ->
            if (!r.isSuccessful) throw TransportError.Http(r.code, r.bodyPrefix())
            return r.body.string()
        }
    }

    companion object {
        /** Shared with the Dashboard's `model.options`, which returns the same payload. */
        fun parseModelOptions(json: JsonElement?): List<ModelChoice> {
            val out = mutableListOf<ModelChoice>()
            // The payload's top-level model names the active selection.
            val currentModel = json["model"].str
            for (provider in json["providers"].arr ?: emptyList()) {
                val slug = provider["slug"].str ?: provider["id"].str ?: ""
                val providerName = provider["name"].str ?: slug
                val current = provider["is_current"].bool ?: false
                for (model in provider["models"].arr ?: emptyList()) {
                    // Live payloads carry models as bare id strings; keep the object shape too.
                    val id = model.str ?: model["id"].str ?: model["model"].str ?: ""
                    if (id.isEmpty()) continue
                    out += ModelChoice(
                        provider = slug,
                        providerName = providerName,
                        model = id,
                        name = model["name"].str ?: id,
                        isCurrent = current && (model["is_current"].bool ?: (id == currentModel)),
                    )
                }
            }
            return out
        }
    }
}

