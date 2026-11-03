package ch.nicolab.redde.model

/** Events emitted while a turn streams back from the model. */
sealed interface TurnEvent {
    /** A fragment of assistant text, in order. */
    data class TextDelta(val text: String) : TurnEvent

    /**
     * The backend's authoritative text for the reply, replacing whatever streamed. The API server
     * rewrites `MEDIA:<path>` tags into inline images only in this final form, so a reply that
     * sends a picture is wrong until it arrives.
     */
    data class TextFinal(val text: String) : TurnEvent

    /** The backend is doing something other than emitting text (tool call, thinking). Informational. */
    data class Status(val text: String) : TurnEvent

    /**
     * Prompt-processing progress before the first token (llama.cpp `return_progress`, OpenAI-compatible
     * connection only). [cached] tokens were already in the KV cache.
     */
    data class Prefill(val processed: Int, val total: Int, val cached: Int) : TurnEvent

    /** Token accounting for the turn, sent at the end of the stream. */
    data class Usage(val usage: TokenUsage) : TurnEvent

    /** A fragment of the model's reasoning ("thinking"), streamed live where the backend allows. */
    data class ReasoningDelta(val text: String) : TurnEvent

    /** The agent started a tool call. [args] is what it was called with, as text, where the backend sends it. */
    data class ToolStarted(val name: String, val preview: String?, val args: String? = null) : TurnEvent

    /**
     * A tool call ended. [name] may be empty when the backend doesn't repeat it. [output] is what it
     * returned, as text: the Dashboard sends it, the Hermes API's stream does not.
     */
    data class ToolFinished(val name: String, val failed: Boolean, val output: String? = null) : TurnEvent

    /** The agent's task list, whole, as the host has it now. */
    data class Todos(val items: List<TodoItem>) : TurnEvent

    /** A delegated child agent started, called a tool, or finished. */
    data class Subagent(val update: SubagentUpdate) : TurnEvent

    /** Server-side session id (Hermes ledger) this turn belongs to. */
    data class SessionId(val id: String) : TurnEvent

    /** API-server run id for this turn; needed to steer or stop it. */
    data class RunId(val id: String) : TurnEvent

    /** The agent is waiting on the person: approval, clarifying question, sudo, secret. */
    data class Interrupted(val interrupt: Interrupt, val runtimeSession: String) : TurnEvent

    /** A pending interrupt timed out on the gateway. */
    data class InterruptExpired(val id: String) : TurnEvent

    /** The stream finished normally. */
    data object Done : TurnEvent
}

/** Token counts for a turn. */
@kotlinx.serialization.Serializable
data class TokenUsage(
    /**
     * Prompt tokens. On the OpenAI-compatible connection this is the context the model saw for the
     * one call. On the Hermes backends it is the session's running total across every API call, so
     * it must not be read as context size; [contextUsed] carries that when the gateway reports it.
     */
    val input: Int,
    val output: Int,
    /** Prompt tokens served from the prefix cache (llama.cpp reports this; the gateway doesn't). */
    val cached: Int? = null,
    /** Current context occupancy and window, when the backend states them. */
    val contextUsed: Int? = null,
    val contextMax: Int? = null,
) {
    val total: Int get() = input + output

    companion object {
        /**
         * Average decode speed: reply tokens (reasoning included) over the time from the first token
         * of any kind to the last. Falls back to the whole request window when the stream arrived in
         * one burst, so a batched delivery can't fake a huge rate. Times are epoch milliseconds.
         */
        fun decodeRate(output: Int?, firstTokenAt: Long?, doneAt: Long?, sentAt: Long?): Double? {
            if (output == null || output <= 1 || doneAt == null) return null
            var window = firstTokenAt?.let { (doneAt - it) / 1000.0 } ?: 0.0
            if (window < 0.5) window = sentAt?.let { (doneAt - it) / 1000.0 } ?: 0.0
            if (window <= 0.2) return null
            return output / window
        }
    }
}

/**
 * A turn GPT-Live handed to the agent: the recent spoken exchange it refers to, for the agent's eyes
 * only, and whether the Dashboard takes it as a live voice turn itself (Hermes 0.21.3 and later).
 */
data class LiveTurn(val context: String, val surface: Boolean)

/**
 * What the client sends for one turn. On iOS several fields were computed from global settings at
 * the moment the turn was made (units, calendars, location); here the caller fills them in, so the
 * transports stay free of app state.
 */
data class TurnRequest(
    val userText: String,
    /**
     * The message's own id: the Hermes API takes it as the run's idempotency key, so sending the same
     * message again (after a connection that dropped while it went) finds the run it started.
     */
    val turnKey: String? = null,
    /** Prior turns, oldest first, for stateless backends. Ignored when threading by id. */
    val history: List<Message> = emptyList(),
    /** Hermes ledger session id. Created on the first turn when null. */
    val sessionId: String? = null,
    val model: String? = null,
    val provider: String? = null,
    /** "" or null = gateway default; low … max. */
    val reasoningEffort: String? = null,
    val instructions: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** Reply language (an ISO code); null lets the model choose. */
    val replyLanguage: String? = null,
    /** The units the agent is to give measurements in; null leaves it to the agent. */
    val units: MeasureUnits? = null,
    /** On a question about the calendar, the calendars the person chose, for the agent to look at only. */
    val calendars: List<String>? = null,
    /** On a question about places or the weather, with Current Location on, roughly where the person is. */
    val location: CurrentLocation.Here? = null,
    /** On the Dashboard, the profile a new session is made on when it isn't the selected one. */
    val profile: String? = null,
    /** Asked by voice with "Short answers" on: the agent is asked to keep it short. */
    val brief: Boolean = false,
    /** The folder a new conversation starts in on the Dashboard (a project's). */
    val folder: String? = null,
    /** The provider's fast tier for this turn (the Hermes API). */
    val fast: Boolean = false,
    /** In a bot's chat, the note saying which bot each @tag means (Dashboard only). */
    val mentionNote: String? = null,
    /** An incognito chat: the agent is told so; on the Dashboard its session is hidden. */
    val incognito: Boolean = false,
    /** Sent by a page in a reply, not typed: no message of the person's shows for it. */
    val fromPage: Boolean = false,
    /** Handed over by GPT-Live. */
    val live: LiveTurn? = null,
    /** The first words after the person answered an agent's call: what the agent opened with. */
    val answeringCall: String? = null,
)

/** The turn a page in a reply started: which reply, and the page to read again after. */
object PageTurn {
    /**
     * On the Hermes API, which keeps no hidden messages, a page's message says where it is from, and
     * the conversation opened again leaves it out.
     */
    const val MARKER = "[From the page in the chat] "

    fun text(request: TurnRequest): String = if (request.fromPage) MARKER + request.userText else request.userText

    fun isMarked(text: String): Boolean = text.startsWith(MARKER)
}

/** One selectable model as the gateway lists it. */
@kotlinx.serialization.Serializable
data class ModelChoice(
    /** The provider's slug. */
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    val isCurrent: Boolean,
) {
    val id: String get() = "$provider/$model"
}
