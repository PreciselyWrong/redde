package ch.nicolab.redde.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID

/** UUIDs as the iOS app stored them: upper-case text. Reading accepts either case. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** One message of a conversation, as shown and as stored (the same JSON as the iOS app's records). */
@Serializable
data class Message(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val role: Role,
    val text: String,
    @Serializable(with = InstantIso8601Serializer::class) val createdAt: Instant = Instant.now(),
    /** Set once a reply finishes streaming. */
    val metrics: TurnMetrics? = null,
    val error: String? = null,
    /** The model's reasoning, when the backend streams it. */
    val reasoning: String = "",
    /** When the reasoning started arriving and when it stopped, for "Thought for 6 s". */
    @Serializable(with = InstantIso8601Serializer::class) val reasoningStartedAt: Instant? = null,
    @Serializable(with = InstantIso8601Serializer::class) val reasoningEndedAt: Instant? = null,
    val tools: List<ToolActivity> = emptyList(),
    /** The agent's own task list as this reply left it, when the reply changed it. */
    val todos: List<TodoItem>? = null,
    /**
     * The cards this reply shows (a map, places, the weather), asked for with `redde_show`. Kept as
     * the JSON they are stored as until the card models are ported (phase 4).
     */
    val cards: List<JsonElement>? = null,
    /** Delegated child agents spawned during this reply. */
    val subagents: List<SubagentActivity> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** A nudge sent into a running turn rather than a new question. */
    val isSteer: Boolean = false,
    /** Filed as the person's, but not theirs: another bot's message, a bot's reply, a process reporting back. */
    val delivered: Delivered? = null,
) {
    @Serializable
    enum class Role {
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT,
    }

    /** One side of an aside to a tagged bot: not a turn of this chat's agent. */
    val isAside: Boolean
        get() = delivered?.kind == Delivered.Kind.ASKED || delivered?.kind == Delivered.Kind.ANSWER
}

/**
 * A message the person didn't write, though Hermes files it as theirs (a user row): another bot's,
 * a bot's reply, a background process reporting back, a schedule's result, or one side of an aside.
 */
@Serializable
data class Delivered(
    val kind: Kind,
    /** The bot it is from: its profile, and what it called itself. */
    val profile: String? = null,
    val name: String? = null,
    /** A process notice's one line (`display_text`), or the schedule's name. */
    val title: String? = null,
    /** An aside the main agent has been told of, with the person's next message to it. */
    val noted: Boolean? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("message") MESSAGE,
        @SerialName("reply") REPLY,
        @SerialName("process") PROCESS,
        @SerialName("routine") ROUTINE,
        @SerialName("asked") ASKED,
        @SerialName("answer") ANSWER,
    }
}

/** How much of the model's context window a conversation fills, in tokens. */
data class ContextUsage(val used: Int, val window: Int)

/** Wall-clock timings for one turn, measured on the phone. */
@Serializable
data class TurnMetrics(
    @Serializable(with = InstantIso8601Serializer::class) val sentAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) val firstTokenAt: Instant? = null,
    @Serializable(with = InstantIso8601Serializer::class) val completedAt: Instant? = null,
    val characters: Int = 0,
    val usage: TokenUsage? = null,
    /** Model context window in tokens, for the percentage. Detected from llama.cpp when possible. */
    val contextWindow: Int? = null,
) {
    /**
     * Context occupancy after this turn. Only when the backend states it (Dashboard) or the numbers
     * are unambiguous (OpenAI-compatible: one call's prompt tokens against the detected window).
     */
    val context: ContextUsage?
        get() {
            val used = usage?.contextUsed ?: return null
            val max = usage.contextMax ?: contextWindow ?: return null
            return if (max > 0) ContextUsage(used, max) else null
        }

    val contextPercent: Double? get() = context?.let { it.used.toDouble() / it.window * 100 }

    val tokensPerSecond: Double?
        get() = TokenUsage.decodeRate(usage?.output, firstTokenAt?.toEpochMilli(), completedAt?.toEpochMilli(), sentAt.toEpochMilli())

    val timeToFirstTokenSeconds: Double? get() = firstTokenAt?.let { (it.toEpochMilli() - sentAt.toEpochMilli()) / 1000.0 }
    val totalSeconds: Double? get() = completedAt?.let { (it.toEpochMilli() - sentAt.toEpochMilli()) / 1000.0 }

    companion object {
        /** "1.2M", "54k", "8.2k", "900": a token count in a few characters. */
        fun compact(n: Int): String = when {
            n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000.0)
            n >= 10_000 -> "%.0fk".format(java.util.Locale.ROOT, n / 1000.0)
            n >= 1000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000.0)
            else -> n.toString()
        }
    }
}
