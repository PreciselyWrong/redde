package ch.nicolab.redde.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/** One tool call as shown in the transcript. */
@Serializable
data class ToolActivity(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val preview: String? = null,
    val status: Status,
    /** When the call started and ended, for the card's total. Absent on older saved replies. */
    @Serializable(with = InstantIso8601Serializer::class) val startedAt: Instant? = null,
    @Serializable(with = InstantIso8601Serializer::class) val endedAt: Instant? = null,
    /**
     * What the tool was called with and what it returned, as text for the step's detail, each cut to
     * [DETAIL_CAP] characters. Absent where the backend didn't send them.
     */
    val args: String? = null,
    val output: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("running") RUNNING,
        @SerialName("completed") COMPLETED,
        @SerialName("failed") FAILED,
    }

    companion object {
        const val DETAIL_CAP = 12_000

        /** The tool an agent shows cards with (`ReplyCards`). */
        const val CARD_TOOL = "redde_show"

        /**
         * Text for a step's detail: trimmed, null when empty, cut at the cap with a note of how much
         * is missing. [more] words the note (localized by the app; English by default).
         */
        fun detail(text: String?, more: (Int) -> String = { "$it more characters" }): String? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length <= DETAIL_CAP) return trimmed
            return trimmed.take(DETAIL_CAP) + "\n… (" + more(trimmed.length - DETAIL_CAP) + ")"
        }

        /**
         * A JSON value as that text. A string stays itself; an object becomes a line per field with
         * text fields in full, so a terminal's `{"output": "…", "exit_code": 0}` reads as its output
         * and not as one escaped line; anything else is compact JSON.
         */
        fun detail(value: JsonElement?): String? {
            if (value == null || value is JsonNull) return null
            value.str?.let { return detail(it) }
            if (value is JsonObject) {
                if (value.isEmpty()) return null
                return detail(
                    value.entries.sortedBy { it.key }.joinToString("\n") { (key, field) ->
                        val text = field.str ?: return@joinToString "$key: ${field.displayText}"
                        if ("\n" in text) "$key:\n$text" else "$key: $text"
                    },
                )
            }
            return detail(value.displayText)
        }

        /**
         * The call as the person should see it. From Hermes 0.21.3 a model reaches most tools through
         * `tool_call`, which names the tool and carries its arguments. A `tool_call` that makes several
         * calls at once stays as it is.
         */
        fun unwrapped(name: String, args: JsonElement?): Pair<String, JsonElement?> {
            if (name != "tool_call") return name to args
            val calls = args["calls"].arr ?: return name to args
            if (calls.size != 1) return name to args
            val inner = calls[0]["name"].str?.takeIf { it.isNotEmpty() } ?: return name to args
            return inner to calls[0]["arguments"]
        }

        /**
         * A call's arguments as its step keeps them: for reading ([detail]), except a card call's,
         * kept as the JSON it came as, for the cards to be read from.
         */
        fun args(name: String, value: JsonElement?): String? {
            if (name != CARD_TOOL || value == null || value is JsonNull) return detail(value)
            val json = value.toString()
            return if (json.length < 256_000) json else detail(value)
        }

        /** The same for JSON that arrives as text (a stored call's `arguments`): parsed when it parses. */
        fun detailOfJson(text: String?): String? {
            if (text.isNullOrEmpty()) return null
            val value = parseJsonOrNull(text) ?: return detail(text)
            return detail(value)
        }
    }
}

/** One item of the agent's own task list: Hermes's to-do tool, `todo_list` (`todo` before 0.21.3). */
@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: Status,
    /** The item this one is a subtask of. */
    val parent: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("pending") PENDING,
        @SerialName("in_progress") IN_PROGRESS,
        @SerialName("completed") COMPLETED,
        @SerialName("cancelled") CANCELLED,
    }
}

/**
 * One delegated child agent, as seen from the parent turn. The Dashboard streams these live; the
 * ledger SSE stream only reveals the goals, so rows there stay at "dispatched".
 */
@Serializable
data class SubagentActivity(
    /** `subagent_id`, or a synthesized delegate-tool id. */
    val id: String,
    val goal: String,
    val taskIndex: Int = 0,
    val taskCount: Int = 1,
    val depth: Int = 0,
    val status: Status = Status.RUNNING,
    val toolCount: Int = 0,
    val lastTool: String? = null,
    val summary: String? = null,
    val durationSeconds: Double? = null,
    @SerialName("childSessionID") val childSessionId: String? = null,
    val model: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("running") RUNNING,
        @SerialName("dispatched") DISPATCHED,
        @SerialName("completed") COMPLETED,
        @SerialName("failed") FAILED,
    }
}

/** A live change to one subagent, carried by [TurnEvent.Subagent]. */
data class SubagentUpdate(
    val id: String,
    val goal: String,
    val taskIndex: Int = 0,
    val taskCount: Int = 1,
    val depth: Int = 0,
    val toolCount: Int? = null,
    val childSessionId: String? = null,
    val model: String? = null,
    val phase: Phase,
) {
    sealed interface Phase {
        data object Started : Phase
        data class Tool(val name: String) : Phase
        data class Progress(val text: String) : Phase
        data class Completed(val succeeded: Boolean, val summary: String?, val duration: Double?) : Phase
        data object Dispatched : Phase
    }

    companion object {
        /**
         * A child agent's event as Hermes words it, on the Dashboard's socket and in a Hermes API run's
         * events alike (`subagent.start`, `.tool`, `.progress`, `.complete`).
         */
        fun fromEvent(type: String, p: JsonElement?): SubagentUpdate? {
            val id = p["subagent_id"].str ?: p["child_session_id"].str ?: return null
            val phase = when (type) {
                "subagent.start" -> Phase.Started
                "subagent.tool" -> Phase.Tool(p["tool_name"].str ?: "tool")
                "subagent.progress" -> Phase.Progress(p["text"].str ?: "")
                "subagent.complete" -> {
                    val status = p["status"].str ?: "success"
                    Phase.Completed(
                        succeeded = status != "error" && status != "failed" && status != "timeout",
                        summary = p["summary"].str ?: p["text"].str,
                        duration = p["duration_seconds"].num,
                    )
                }
                else -> return null
            }
            return SubagentUpdate(
                id = id,
                goal = p["goal"].str ?: "",
                taskIndex = p["task_index"].int ?: 0,
                taskCount = p["task_count"].int ?: 1,
                depth = p["depth"].int ?: 0,
                toolCount = p["tool_count"].int,
                childSessionId = p["child_session_id"].str,
                model = p["model"].str,
                phase = phase,
            )
        }
    }
}
