package ch.nicolab.redde.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A tool approval the agent is waiting on. */
@Serializable
data class ApprovalRequest(
    /** The host's `request_id`. */
    val id: String,
    val command: String,
    val description: String? = null,
    /** once | session | always | deny */
    val choices: List<String>,
) {
    /** The kanban card the request names (`t_…`): its change can be looked at before answering. */
    val kanbanCard: String?
        get() = KANBAN_CARD.find("$command ${description ?: ""}")?.groupValues?.get(1)

    /**
     * The two answers a pair of buttons can give: run it this once, or don't. Null when the request
     * doesn't offer both; then only the full card in the app can answer it.
     */
    val yesNo: Pair<String, String>?
        get() {
            if ("deny" !in choices) return null
            val approve = if ("once" in choices) "once" else choices.firstOrNull { it != "deny" } ?: return null
            return approve to "deny"
        }

    companion object {
        private val KANBAN_CARD = Regex("""\b(t_[0-9a-f]{6,})\b""")

        /**
         * An `approval.request` as Hermes sends it, on the Dashboard's socket and in a Hermes API
         * run's events. The choices are listed when the host lists them; a request that sends none
         * says with two flags whether "this session" and "always" are on offer.
         */
        fun fromPayload(p: JsonElement?): ApprovalRequest {
            var choices = p["choices"].arr?.mapNotNull { it.str } ?: emptyList()
            if (choices.isEmpty()) {
                choices = buildList {
                    add("once")
                    if (p["allow_session"].bool ?: true) add("session")
                    if (p["allow_permanent"].bool ?: true) add("always")
                    add("deny")
                }
            }
            return ApprovalRequest(
                id = p["request_id"].str ?: "",
                command = p["command"].str ?: "",
                description = p["description"].str,
                choices = choices,
            )
        }
    }
}

/** One clarifying question from the agent's `clarify` tool. */
@Serializable
data class ClarifyQuestion(
    /** The `qid` ("" for the single-question form). */
    val id: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean,
)

@Serializable
data class ClarifyRequest(
    /** The host's `request_id`. */
    val id: String,
    val questions: List<ClarifyQuestion>,
    /** Batched requests answer per question id; the single form answers the request as a whole. */
    val isBatch: Boolean,
)

@Serializable
data class SecretRequest(val id: String, val prompt: String, val envVar: String)

/** Everything the gateway can pause a turn on. */
sealed interface Interrupt {
    val id: String

    data class Approval(val request: ApprovalRequest) : Interrupt {
        override val id get() = request.id
    }

    data class Clarify(val request: ClarifyRequest) : Interrupt {
        override val id get() = request.id
    }

    data class Sudo(override val id: String) : Interrupt

    data class Secret(val request: SecretRequest) : Interrupt {
        override val id get() = request.id
    }
}
