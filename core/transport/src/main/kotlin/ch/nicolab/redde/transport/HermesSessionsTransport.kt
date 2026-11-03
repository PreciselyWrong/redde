package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ApprovalRequest
import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.CalendarChoice
import ch.nicolab.redde.model.CallNote
import ch.nicolab.redde.model.CarBrevity
import ch.nicolab.redde.model.CurrentLocation
import ch.nicolab.redde.model.IncognitoNote
import ch.nicolab.redde.model.Interrupt
import ch.nicolab.redde.model.LiveVoiceNote
import ch.nicolab.redde.model.MeasureUnits
import ch.nicolab.redde.model.PageTurn
import ch.nicolab.redde.model.ReplyLanguage
import ch.nicolab.redde.model.SubagentUpdate
import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.ToolActivity
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * The Hermes API's turns, in the shared session ledger every Hermes platform writes to: a
 * conversation started here shows up next to Telegram and CLI sessions, and any of theirs can be
 * resumed here. A turn is a run ([HermesRuns]) that goes on on the host whatever becomes of this
 * stream; a gateway without runs gets the chat stream (`/api/sessions/{id}/chat/stream`).
 */
class HermesSessionsTransport(
    private val baseUrl: HttpUrl,
    private val apiKey: String?,
    /** The person's own headers for this server (a reverse proxy's), sent with each request. */
    private val headers: Map<String, String> = emptyMap(),
    private val timing: HermesRuns.Timing = HermesRuns.Timing(),
) : HermesTransport {

    /**
     * One turn, as a run in the conversation's session. Ending the stream stops the run, unless the
     * conversation was left ([TurnRequest.departure]).
     */
    override fun stream(request: TurnRequest): Flow<TurnEvent> {
        val key = apiKey?.ifEmpty { null } ?: return flow { throw TransportError.MissingApiKey() }
        val sessionId = request.sessionId?.ifEmpty { null } ?: return flow { throw TransportError.Malformed("no session id") }
        val runs = HermesRuns(baseUrl, key, headers, timing = timing)
        return channelFlow {
            var runId: String? = null
            try {
                val started = runs.start(runBody(request, sessionId), request.turnKey)
                if (started == null) {
                    chatStream(request, sessionId, key).collect { send(it) }
                    return@channelFlow
                }
                runId = started
                send(TurnEvent.SessionId(sessionId))
                send(TurnEvent.RunId(started))
                runs.follow(started) { trySend(it) }
                send(TurnEvent.Done)
            } catch (e: CancellationException) {
                // Stopped, the run is stopped on the host. Left for another conversation, it goes on
                // there, and is taken up again when the conversation is opened.
                if (runId != null && request.departure?.hasLeft != true) runs.stop(runId)
                throw e
            }
        }
    }

    /** The chat stream, for a gateway without runs. Hermes stops its turn when the stream is cut. */
    fun chatStream(request: TurnRequest, sessionId: String, apiKey: String): Flow<TurnEvent> =
        StreamingHttp.run(decode = ::decode) {
            StreamingHttp.makeRequest(baseUrl.resolvePath("api/sessions/$sessionId/chat/stream"), apiKey, headers, chatBody(request))
        }

    companion object {
        const val TRANSPORT_NAME = "the Hermes API"

        /**
         * Appended by the gateway to its own system prompt every turn (`instructions` →
         * `ephemeral_system_prompt`). The stock `api_server` platform hint tells the agent to assume a
         * plain-text renderer and, on some endpoints, to print a bare file path instead of a `MEDIA:`
         * tag. This corrects both for Redde. (The iOS hint said "an iOS chat app"; the rest is its words.)
         */
        const val CLIENT_HINT = "The user is on Redde, an Android chat app that renders GitHub-flavored Markdown: headings, " +
            "bold/italic, tables, task lists, fenced code with syntax highlighting, \$math\$, and images. " +
            "Use markdown freely. To send the user a file from your machine, of any kind (a picture, " +
            "a recording, a PDF, a Markdown or HTML document you wrote, a spreadsheet), write " +
            "MEDIA:/absolute/path/to/file on a line of its own in your reply — the app fetches it and " +
            "shows it: pictures and sound in the chat, other files as one to open. This works here, on " +
            "this endpoint. Never say you cannot display images or send files, and never print a bare " +
            "file path or an [IMAGE: ...] placeholder in place of a MEDIA: tag. Put runnable commands in fenced code blocks " +
            "(```bash), which render with a Copy button — not inline backticks. To put something live in the chat (a chart, " +
            "a widget with buttons), write a single-file HTML page, then put ::preview{file=\"/absolute/path/page.html\"} " +
            "alone on its own line. The app themes it before your styles, with var(--foreground), var(--muted-foreground), " +
            "var(--accent), var(--border) and var(--card), its font, no margins and a clear background: use those vars for " +
            "colour and set no background, font or margins of your own (only a standalone page, a poster or a game, " +
            "overrides them). It sizes itself to the content: lay it out flush left, with no centring wrapper. A page talks " +
            "back: data-hermes-send=\"prompt\" on anything tappable, or window.hermes.send(\"prompt\"), sends you that prompt " +
            "as a message starting [From the page in the chat]; answer it by writing the page's file again, not in prose: " +
            "the app reads the page again once you are done."

        /** `model_options`, or null when there's nothing to ask for. */
        fun modelOptions(effort: String?, fast: Boolean): JsonObject? {
            val level = effort?.ifEmpty { null }
            if (level == null && !fast) return null
            return buildJsonObject {
                level?.let { put("reasoning_effort", it) }
                if (fast) put("fast", true)
            }
        }

        /** The turn's instructions, on top of Hermes's own: Redde's hint, then the notes for this turn. */
        fun instructions(request: TurnRequest): String =
            (listOf(CLIENT_HINT) + listOfNotNull(
                request.instructions?.ifEmpty { null },
                request.replyLanguage?.let(ReplyLanguage::instruction),
                request.units?.let(MeasureUnits::instruction),
                request.calendars?.let(CalendarChoice::instruction),
                request.location?.let(CurrentLocation::instruction),
                if (request.brief) CarBrevity.INSTRUCTION else null,
                if (request.incognito) IncognitoNote.INSTRUCTION else null,
                request.live?.let { LiveVoiceNote.instruction(it.context) },
                request.answeringCall?.let(CallNote::instruction),
            )).joinToString("\n\n")

        /** What a run is started with. A picked model goes with its provider every turn. */
        fun runBody(request: TurnRequest, sessionId: String): JsonObject = buildJsonObject {
            put("input", runInput(PageTurn.text(request), request.attachments))
            put("session_id", sessionId)
            put("instructions", instructions(request))
            request.model?.ifEmpty { null }?.let { put("model", it) }
            request.provider?.ifEmpty { null }?.let { put("provider", it) }
            modelOptions(request.reasoningEffort, request.fast)?.let { put("model_options", it) }
        }

        /**
         * A run's message: the text, or with pictures one user message of parts. One message only: a
         * run takes more than one as the conversation so far, in place of the session's own.
         */
        fun runInput(text: String, attachments: List<Attachment>): JsonElement {
            val content = MultimodalInput.make(text, attachments, textType = "text", imageType = "image_url", nestedImageUrl = true, transportName = TRANSPORT_NAME)
            if (content is JsonPrimitive) return content
            return buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", content) }) }
        }

        /** The chat stream's body. The API server takes text and images only (`input_text` / `input_image`). */
        fun chatBody(request: TurnRequest): JsonObject = buildJsonObject {
            put("input", MultimodalInput.make(PageTurn.text(request), request.attachments, "input_text", "input_image", nestedImageUrl = false, transportName = TRANSPORT_NAME))
            put("instructions", instructions(request))
            request.model?.let { put("model", it) }
            request.provider?.let { put("provider", it) }
            // With a picked model the gateway resolves the named provider every turn and pins the session to it.
            if (!request.model.isNullOrEmpty()) put("require_model_lock", true)
            modelOptions(request.reasoningEffort, request.fast)?.let { put("model_options", it) }
        }

        /** The API server never forwards subagent events, so rows are made from `delegate_task`'s arguments: one per goal. */
        fun delegatedGoals(args: JsonElement?, runId: String?): List<SubagentUpdate> {
            if (args == null) return emptyList()
            val goals = args["tasks"].arr?.mapNotNull { it["goal"].str ?: it.str } ?: listOfNotNull(args["goal"].str)
            return goals.mapIndexed { i, goal ->
                SubagentUpdate(id = "delegate:${runId ?: "run"}:$i", goal = goal, taskIndex = i, taskCount = goals.size, phase = SubagentUpdate.Phase.Started)
            }
        }

        /** The name a finished call is matched to its step by; a bare `tool_call` matches the step still running. */
        private fun finished(name: String) = if (name == "tool_call") "" else name

        /** One event of the chat stream. */
        fun decode(sse: SseEvent): Decoded {
            val event = sse.event ?: return Decoded.NOTHING
            val env = parseJsonOrNull(sse.data) as? JsonObject ?: JsonObject(emptyMap())
            return when (event) {
                "run.started" -> Decoded(listOfNotNull(env["session_id"].str?.let { TurnEvent.SessionId(it) }, env["run_id"].str?.let { TurnEvent.RunId(it) }))
                "assistant.delta" -> Decoded(listOfNotNull(env["delta"].str?.let { TurnEvent.TextDelta(it) }))
                // The finished reply, and the only form with MEDIA tags resolved into inline images.
                "assistant.completed" -> Decoded(listOfNotNull(env["content"].str?.takeIf { it.isNotEmpty() }?.let { TurnEvent.TextFinal(it) }))
                // Reasoning arrives as a pseudo-tool named "_thinking".
                "tool.progress" -> if (env["tool_name"].str == "_thinking") Decoded(listOfNotNull(env["delta"].str?.let { TurnEvent.ReasoningDelta(it) })) else Decoded.NOTHING
                "tool.started" -> {
                    val (name, args) = ToolActivity.unwrapped(env["tool_name"].str ?: "tool", env["args"])
                    val events = mutableListOf<TurnEvent>(TurnEvent.ToolStarted(name, env["preview"].str, ToolActivity.args(name, args)))
                    if (name == "delegate_task") events += delegatedGoals(args, env["run_id"].str).map { TurnEvent.Subagent(it) }
                    Decoded(events)
                }
                "tool.completed" -> {
                    val (name, args) = ToolActivity.unwrapped(env["tool_name"].str ?: "", env["args"])
                    val events = mutableListOf<TurnEvent>(TurnEvent.ToolFinished(finished(name), failed = false))
                    if (name == "delegate_task") {
                        // The API server drops subagent events; results land in a later turn.
                        events += delegatedGoals(args, env["run_id"].str).map { TurnEvent.Subagent(it.copy(phase = SubagentUpdate.Phase.Dispatched)) }
                    }
                    Decoded(events)
                }
                "tool.failed" -> Decoded.of(TurnEvent.ToolFinished(finished(ToolActivity.unwrapped(env["tool_name"].str ?: "", env["args"]).first), failed = true))
                "approval.request" -> {
                    // Answered via POST /v1/runs/{run_id}/approval, so the run id rides as the routing token.
                    val requestId = env["request_id"].str ?: return Decoded.NOTHING
                    val request = ApprovalRequest(
                        id = requestId,
                        command = env["command"].str ?: "",
                        description = env["description"].str?.ifEmpty { null },
                        choices = (env["choices"] as? JsonArray)?.mapNotNull { it.str } ?: listOf("once", "deny"),
                    )
                    Decoded.of(TurnEvent.Interrupted(Interrupt.Approval(request), env["run_id"].str ?: ""))
                }
                "run.completed" -> {
                    val u = env["usage"]
                    val input = u["input_tokens"].int ?: u["prompt_tokens"].int
                    Decoded(listOfNotNull(input?.let { TurnEvent.Usage(TokenUsage(it, u["output_tokens"].int ?: u["completion_tokens"].int ?: 0)) }))
                }
                "error" -> {
                    // Not a failure: the reply above is whole and already stored. "done" ends the turn.
                    val message = env["message"].str
                    if (message != null && isLockBucketMismatch(message)) Decoded.NOTHING
                    else throw TransportError.Malformed(message ?: "run failed")
                }
                "done" -> Decoded.FINISHED
                else -> Decoded.NOTHING
            }
        }

        private val LOCK_MISMATCH = Regex("""lock runtime mismatch: expected provider=(\S+) model=(\S+); actual provider=(\S+) model=(\S+)""")

        /**
         * Hermes before 0.21.4 ends a locked turn on a user-defined endpoint with "confirmed model lock
         * runtime mismatch: expected provider=custom:name …; actual provider=custom …": its post-run
         * check compares the endpoint's name with the bare "custom" bucket every such endpoint runs
         * under. The turn itself ran right. Any other mismatch (another model, another provider) is real.
         */
        fun isLockBucketMismatch(message: String): Boolean {
            val m = LOCK_MISMATCH.find(message) ?: return false
            val (expectedProvider, expectedModel, actualProvider, actualModel) = m.destructured
            return expectedModel == actualModel && actualProvider == "custom" && expectedProvider != "custom"
        }
    }
}
