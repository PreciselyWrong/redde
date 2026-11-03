package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ApprovalRequest
import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.CalendarChoice
import ch.nicolab.redde.model.CallNote
import ch.nicolab.redde.model.CarBrevity
import ch.nicolab.redde.model.ClarifyQuestion
import ch.nicolab.redde.model.ClarifyRequest
import ch.nicolab.redde.model.CurrentLocation
import ch.nicolab.redde.model.IncognitoNote
import ch.nicolab.redde.model.Interrupt
import ch.nicolab.redde.model.LiveVoiceNote
import ch.nicolab.redde.model.MeasureUnits
import ch.nicolab.redde.model.ReplyLanguage
import ch.nicolab.redde.model.SecretRequest
import ch.nicolab.redde.model.SubagentUpdate
import ch.nicolab.redde.model.TodoList
import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.ToolActivity
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.bool
import ch.nicolab.redde.model.displayText
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.isNull
import ch.nicolab.redde.model.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * One turn on the Hermes Dashboard: the session opened or resumed, attachments sent, the prompt
 * submitted, and the session's events heard until `message.complete` (port of
 * HermesServeTransport.swift). A dropped socket is waited out for 90 s and the turn taken up again.
 *
 * Not ported yet: [rejoin] (joining a turn already running on the host) and [watchHost].
 */
class HermesServeTransport(private val client: HermesServeClient) : HermesTransport {

    /** Where one turn has got to. */
    private class TurnState(@Volatile var runtime: String, val stored: String) {
        val streamed = StringBuilder()
        @Volatile var recovering = false
        @Volatile var underWay = false
        var recoverJob: Job? = null
    }

    override fun stream(request: TurnRequest): Flow<TurnEvent> = channelFlow {
        var listener: ((HermesServeClient.Event) -> Unit)? = null
        var turn: TurnState? = null
        try {
            client.ensureConnected()
            val (runtime, stored) = client.openSession(
                stored = request.sessionId, model = request.model, provider = request.provider,
                reasoningEffort = request.reasoningEffort, folder = request.folder, hidden = request.incognito, profile = request.profile,
            )
            val state = TurnState(runtime, stored).also { turn = it }
            send(TurnEvent.SessionId(stored))
            if (request.userText.startsWith("/") && !request.fromPage) {
                runSlash(request.userText, runtime).forEach { send(it) }
                send(TurnEvent.Done)
                return@channelFlow
            }
            val finished = CompletableDeferred<Unit>()
            listener = client.addListener { hear(it, state, this, finished) }
            val references = request.attachments.mapNotNull { attach(it, runtime) }
            val params = buildJsonObject {
                put("session_id", runtime)
                put("text", promptText(request, references))
                // A page's message is the agent's to read, not a bubble (Hermes 0.21.0 and later).
                if (request.fromPage) put("display_kind", "hidden")
                // Handed over by GPT-Live, on a Hermes that knows such turns (0.21.3 and later).
                request.live?.takeIf { it.surface }?.let {
                    put("surface", "voice-live")
                    put("voice_context", LiveVoiceNote.voiceContext(it.context))
                }
            }
            val submit = client.call("prompt.submit", params)
            if (submit["status"].str == "queued") send(TurnEvent.Status("queued behind a running turn…"))
            finished.await()
            send(TurnEvent.Done)
        } catch (e: CancellationException) {
            // Stopped, the agent is told to stop. Left for another conversation, it goes on with the turn.
            turn?.let { if (request.departure?.hasLeft != true) client.interrupt(it.runtime) }
            throw e
        } finally {
            listener?.let(client::removeListener)
            turn?.recoverJob?.cancel()
        }
    }

    private fun hear(event: HermesServeClient.Event, turn: TurnState, out: ProducerScope<TurnEvent>, finished: CompletableDeferred<Unit>) {
        val p = event.payload
        when (event.type) {
            "connection.lost" -> {
                if (turn.recovering) return
                turn.recovering = true
                out.trySend(TurnEvent.Status("connection lost, reconnecting…"))
                turn.recoverJob = out.launch { recover(turn, out, finished) }
                return
            }
            "connection.restored" -> return
        }
        if (event.sessionId != turn.runtime) return
        if (event.type in TURN_EVENTS) turn.underWay = true
        for (e in decode(event.type, p, turn.runtime, turn.streamed.isEmpty())) {
            if (e is TurnEvent.TextDelta) turn.streamed.append(e.text)
            out.trySend(e)
        }
        when (event.type) {
            "message.complete" ->
                if (p["status"].str == "error") finished.completeExceptionally(TransportError.Malformed(p["error"].str ?: p["text"].str ?: "run failed"))
                else finished.complete(Unit)
            // Hermes says where a session stands whenever something about it changes; a late word about
            // the turn before must not be taken for this one's end.
            "session.info" -> when (p["running"].bool) {
                true -> turn.underWay = true
                false -> if (turn.underWay) finished.complete(Unit)
                null -> Unit
            }
            "error" -> finished.completeExceptionally(TransportError.Malformed(p["message"].str ?: "gateway error"))
        }
    }

    /** The socket dropped mid-turn: wait for it, then take the turn up again, or fill in its end. */
    private suspend fun recover(turn: TurnState, out: ProducerScope<TurnEvent>, finished: CompletableDeferred<Unit>) {
        if (!client.waitForConnection(90_000)) {
            finished.completeExceptionally(TransportError.Malformed("lost the connection to Hermes Dashboard and couldn't get it back"))
            return
        }
        try {
            val info = client.resume(turn.stored, withMessages = true)
            turn.runtime = info["session_id"].str ?: throw TransportError.Malformed("resume gave no session id")
            turn.recovering = false
            out.trySend(TurnEvent.Status("reconnected"))
            // A question the host asked while the socket was down, or still waits on.
            client.replayOpenRequests(info)
            if (info["running"].bool == true) {
                turn.underWay = true
                return // still generating; events now arrive under the new runtime id
            }
            // The turn finished while we were away: fill in whatever we missed.
            val last = info["messages"].arr?.lastOrNull { it["role"].str == "assistant" }
            val text = last["text"].str ?: last["content"].str
            val soFar = turn.streamed.toString()
            if (text != null && text.length > soFar.length && text.startsWith(soFar)) {
                out.trySend(TurnEvent.TextDelta(text.substring(soFar.length)))
                turn.streamed.append(text.substring(soFar.length))
            }
            finished.complete(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finished.completeExceptionally(e)
        }
    }

    private suspend fun attach(att: Attachment, runtime: String): String? {
        val (method, params) = attachFrame(att, runtime)
        val result = client.call(method, params)
        return if (method == "file.attach") result["ref_text"].str?.ifEmpty { null } else null
    }

    /**
     * Slash commands: `/reasoning <level>` goes to `config.set` (through `slash.exec` the level only
     * changes the host's slash worker); the rest to `slash.exec`, or `command.dispatch` where the host
     * says it doesn't know it there (4018).
     */
    private suspend fun runSlash(text: String, runtime: String): List<TurnEvent> {
        val body = text.drop(1)
        reasoningLevel(body)?.let { level ->
            client.setSessionReasoning(runtime, level)
            return listOf(TurnEvent.TextDelta("Reasoning effort for this conversation: $level"))
        }
        return try {
            val output = client.call("slash.exec", buildJsonObject { put("session_id", runtime); put("command", body) })["output"]?.displayText ?: ""
            listOf(TurnEvent.TextDelta(output.ifEmpty { "(no output)" }))
        } catch (e: HermesServeClient.RpcError) {
            if (e.code != 4018) throw e
            val name = body.substringBefore(' ')
            val arg = if (' ' in body) body.substringAfter(' ') else ""
            val result = client.call("command.dispatch", buildJsonObject { put("session_id", runtime); put("name", name); put("arg", arg) })
            val message = result["message"].str
            if (result["type"].str == "send" && message != null) {
                client.call("prompt.submit", buildJsonObject { put("session_id", runtime); put("text", message) })
                listOf(TurnEvent.Status("running $name…"))
            } else {
                listOf(TurnEvent.TextDelta(result["display"].str ?: result["output"]?.displayText ?: message ?: "(done)"))
            }
        }
    }

    companion object {
        /** Events that say a turn is under way in a session. */
        val TURN_EVENTS = setOf(
            "message.start", "message.delta", "reasoning.delta", "reasoning.available", "tool.start", "tool.complete",
            "approval.request", "clarify.request", "sudo.request", "secret.request",
        )

        /**
         * `prompt.submit` takes no instructions: the notes ride on the message, after the attached
         * files' references and a bot chat's @mention note.
         */
        fun promptText(request: TurnRequest, references: List<String> = emptyList()): String =
            prompt(request.userText, references) + (request.mentionNote ?: "") +
                (request.replyLanguage?.let(ReplyLanguage::note) ?: "") + (request.units?.let(MeasureUnits::note) ?: "") +
                (request.calendars?.let(CalendarChoice::note) ?: "") + (request.location?.let(CurrentLocation::note) ?: "") +
                (if (request.brief) CarBrevity.NOTE else "") +
                (if (request.incognito) IncognitoNote.NOTE else "") +
                (request.live?.takeIf { !it.surface }?.let { LiveVoiceNote.note(it.context) } ?: "") +
                (request.answeringCall?.let(CallNote::note) ?: "")

        fun prompt(text: String, references: List<String>): String {
            if (references.isEmpty()) return text
            val named = references.joinToString(" ")
            return if (text.isBlank()) named else "$text\n\n$named"
        }

        fun attachFrame(att: Attachment, runtime: String): Pair<String, JsonObject> = when (att.kind) {
            Attachment.Kind.IMAGE -> "image.attach_bytes" to buildJsonObject {
                put("session_id", runtime); put("content_base64", Base64.getEncoder().encodeToString(att.data)); put("filename", att.filename)
            }
            Attachment.Kind.PDF -> "pdf.attach" to buildJsonObject {
                put("session_id", runtime); put("content_base64", Base64.getEncoder().encodeToString(att.data)); put("filename", att.filename)
            }
            Attachment.Kind.TEXT, Attachment.Kind.OTHER -> "file.attach" to buildJsonObject {
                put("session_id", runtime); put("data_url", att.dataUrl); put("name", att.filename)
            }
        }

        /** `/reasoning <level>` (not `--global`): the level, else null. */
        fun reasoningLevel(body: String): String? {
            val words = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
            if (words.firstOrNull() != "reasoning" || words.size < 2 || "--global" in words) return null
            return words[1].takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra") }
        }

        fun parseClarify(p: JsonElement?): ClarifyRequest {
            val id = p["request_id"].str ?: ""
            val batch = p["questions"].arr
            if (!batch.isNullOrEmpty()) {
                return ClarifyRequest(id, batch.map {
                    ClarifyQuestion(it["qid"].str ?: "", it["question"].str ?: "", it["choices"].arr?.mapNotNull { c -> c.str } ?: emptyList(), it["multi_select"].bool ?: false)
                }, isBatch = true)
            }
            return ClarifyRequest(id, listOf(ClarifyQuestion("", p["question"].str ?: "", p["choices"].arr?.mapNotNull { it.str } ?: emptyList(), p["multi_select"].bool ?: false)), isBatch = false)
        }

        /** One session event as the turn's events. [nothingStreamed]: `message.complete`'s text is the reply. */
        fun decode(type: String, p: JsonElement, runtime: String, nothingStreamed: Boolean): List<TurnEvent> = when (type) {
            "message.delta" -> listOfNotNull(p["text"].str?.takeIf { it.isNotEmpty() }?.let { TurnEvent.TextDelta(it) })
            "reasoning.delta", "reasoning.available" -> listOfNotNull(p["text"].str?.let { TurnEvent.ReasoningDelta(it) })
            "tool.start" -> {
                val name = p["name"].str ?: "tool"
                listOf(TurnEvent.ToolStarted(name, p["context"].str, ToolActivity.args(name, p["args"])))
            }
            "tool.complete" -> {
                val failed = !p["error"].isNull
                // The whole result rides on the event (`result_text` is the host's rendering of it).
                val output = ToolActivity.detail(p["result_text"].str) ?: ToolActivity.detail(p["result"]) ?: ToolActivity.detail(p["error"])
                listOf(TurnEvent.ToolFinished(p["name"].str ?: "", failed, output))
            }
            "todo.updated" -> listOfNotNull(TodoList.list(p)?.let { TurnEvent.Todos(it) })
            "subagent.start", "subagent.tool", "subagent.progress", "subagent.complete" -> listOfNotNull(SubagentUpdate.fromEvent(type, p)?.let { TurnEvent.Subagent(it) })
            "approval.request" -> listOf(TurnEvent.Interrupted(Interrupt.Approval(ApprovalRequest.fromPayload(p)), runtime))
            "clarify.request" -> listOf(TurnEvent.Interrupted(Interrupt.Clarify(parseClarify(p)), runtime))
            "sudo.request" -> listOf(TurnEvent.Interrupted(Interrupt.Sudo(p["request_id"].str ?: ""), runtime))
            "secret.request" -> listOf(TurnEvent.Interrupted(Interrupt.Secret(SecretRequest(p["request_id"].str ?: "", p["prompt"].str ?: "", p["env_var"].str ?: "")), runtime))
            "approval.expire", "clarify.expire", "sudo.expire", "secret.expire" -> listOf(TurnEvent.InterruptExpired(p["request_id"].str ?: ""))
            "status.update" -> listOfNotNull(p["text"].str?.let { TurnEvent.Status(it) })
            "message.complete" -> buildList {
                // The turn's terminal event: final text (if nothing streamed), the authoritative usage.
                if (nothingStreamed) p["text"].str?.takeIf { it.isNotEmpty() }?.let { add(TurnEvent.TextDelta(it)) }
                val u = p["usage"]
                val input = (u["input"] ?: u["prompt"]).int
                if (input != null) add(TurnEvent.Usage(TokenUsage(input, (u["output"] ?: u["completion"]).int ?: 0, null, u["context_used"].int, u["context_max"].int)))
            }
            "session.usage" -> {
                // A periodic tick while the turn runs (context occupancy). Says nothing about the end.
                val u = p["usage"]
                val used = u["context_used"].int
                val max = u["context_max"].int
                if (used != null && max != null && max > 0) listOf(TurnEvent.Usage(TokenUsage((u["input"] ?: u["prompt"]).int ?: 0, (u["output"] ?: u["completion"]).int ?: 0, null, used, max)))
                else emptyList()
            }
            else -> emptyList()
        }
    }
}
