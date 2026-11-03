package ch.nicolab.redde.store

import ch.nicolab.redde.model.InstantIso8601Serializer
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.Transport
import ch.nicolab.redde.model.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** The bot a conversation is the chat of. */
@Serializable
data class BotRef(
    /** The bot's profile. */
    val profile: String,
    /** What it was called when the chat was opened. */
    val name: String,
    /** The chat goes by the Hermes API at /p/<profile>/, not by the Dashboard. */
    val viaAPI: Boolean? = null,
)

/** Another profile than the selected one a conversation lives in, and how to reach it. */
@Serializable
data class ProfileRoute(
    val profile: String,
    /** Reached by the Hermes API at /p/<profile>/ with that profile's own key, not the Dashboard. */
    val viaAPI: Boolean,
)

/**
 * A message that hasn't gone to the server yet: queued behind a reply that is still streaming, or
 * held because the server couldn't be reached. Sent strictly in order, oldest first.
 */
@Serializable
data class OutboxItem(
    val message: Message,
    val state: State,
    @Serializable(with = InstantIso8601Serializer::class) val queuedAt: Instant,
) {
    @Serializable
    enum class State {
        /** Goes out as soon as the reply in front of it finishes. */
        @SerialName("queued") QUEUED,
        /** The server couldn't be reached; retried when the connection comes back. */
        @SerialName("waitingForConnection") WAITING_FOR_CONNECTION,
        /** Held until the reader taps Send: after Stop, a failed turn, or when it has gone stale. */
        @SerialName("paused") PAUSED,
    }

    val id: UUID get() = message.id

    /** Older than [STALE_AFTER_SECONDS], a waiting message is held for confirmation instead of sent on its own. */
    fun isStale(now: Instant = Instant.now()): Boolean = now.epochSecond - queuedAt.epochSecond > STALE_AFTER_SECONDS

    companion object {
        const val STALE_AFTER_SECONDS = 60 * 60L
    }
}

/** One saved conversation, in the JSON the iOS app wrote (port of ConversationRecord). */
@Serializable
data class ConversationRecord(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val title: String,
    @Serializable(with = InstantIso8601Serializer::class) val createdAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) val updatedAt: Instant,
    val transport: Transport,
    /** Hermes ledger session id, when this conversation lives on the server. */
    @SerialName("serverSessionID") val serverSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    /** Messages not yet sent. Absent in older files. */
    val outbox: List<OutboxItem>? = null,
    /** The Hermes server this conversation belongs to; absent for OpenAI-compatible chats. */
    @SerialName("serverID") @Serializable(with = UuidSerializer::class) val serverId: UUID? = null,
    /** The name the conversation was given; [title] is that, or else the first question. */
    val name: String? = null,
    val bot: BotRef? = null,
    val elsewhere: ProfileRoute? = null,
) {
    val turnCount: Int get() = messages.count { it.role == Message.Role.USER && !it.isSteer }
}

/** What the list shows: a record without its messages. */
@Serializable
data class ConversationSummary(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val title: String,
    @Serializable(with = InstantIso8601Serializer::class) val createdAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) val updatedAt: Instant,
    val transport: Transport,
    @SerialName("serverSessionID") val serverSessionId: String? = null,
    val turnCount: Int = 0,
    @SerialName("serverID") @Serializable(with = UuidSerializer::class) val serverId: UUID? = null,
    val bot: BotRef? = null,
    val elsewhere: ProfileRoute? = null,
) {
    companion object {
        fun of(r: ConversationRecord) = ConversationSummary(
            r.id, r.title, r.createdAt, r.updatedAt, r.transport, r.serverSessionId, r.turnCount, r.serverId, r.bot, r.elsewhere,
        )
    }
}

/**
 * A saved Hermes server: how to reach it and which profile and model to use there. Its secrets live
 * in the [SecretStore] under its id. Every field but the id may be missing, so a record written by
 * another version still loads.
 */
@Serializable
data class HermesServer(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val name: String = "",
    val transport: Transport = Transport.HERMES_SERVE,
    @SerialName("gatewayURL") val gatewayUrl: String = "",
    @SerialName("serveURL") val serveUrl: String = "",
    val serveUsername: String = "",
    @SerialName("cfAccessClientID") val cfAccessClientId: String = "",
    val gatewayModel: String = "",
    val gatewayProvider: String = "",
    val hermesProfile: String = "",
    val hermesProfileHome: String = "",
    /** The Dashboard has no sign-in, and the person chose to use it that way. */
    val serveOpenAccess: Boolean = false,
) {
    /** Its name, else the host of whichever address it uses, else null (the app says "New server"). */
    val title: String?
        get() {
            name.trim().ifEmpty { null }?.let { return it }
            val primary = if (transport == Transport.HERMES_SESSIONS) listOf(gatewayUrl, serveUrl) else listOf(serveUrl, gatewayUrl)
            for (raw in primary) ch.nicolab.redde.model.ServerAddress.parts(raw.trim())?.host?.ifEmpty { null }?.let { return it }
            return null
        }
}

/**
 * Where a server's secrets are kept: encrypted with an Android Keystore key in the app (core/data).
 * Accounts are named as on iOS: `<item>@<serverUUID>`, and a profile's key `gateway-api-key.<profile>@<server>`.
 */
interface SecretStore {
    fun read(account: String): String?
    fun write(account: String, value: String)
    fun delete(account: String)
    fun allAccounts(): List<String>

    enum class Item(val raw: String) {
        GATEWAY_API_KEY("gateway-api-key"),
        SERVE_DASHBOARD_PASSWORD("serve-dashboard-password"),
        SERVE_SIGN_IN("serve-sign-in"),
        CF_ACCESS_CLIENT_SECRET("cf-access-client-secret"),
        CUSTOM_HEADERS("custom-headers"),
        PANGOLIN_DASHBOARD("pangolin-dashboard"),
        PANGOLIN_API("pangolin-api"),
        // The app's own, under the bare name (no server).
        FAST_LANE_API_KEY("fast-lane-api-key"),
        OPENAI_LIVE("openai-live-key"),
        GEMINI_LIVE("gemini-live-key"),
        GROK_VOICE("grok-voice-key"),
        ELEVENLABS_LIVE("elevenlabs-live-key"),
        PUSH_REGISTER_SECRET("push-register-secret"),
        SSH_KEY("ssh-key"),
    }

    companion object {
        fun account(item: Item, server: UUID) = "${item.raw}@${server.toString().uppercase()}"
        fun profileAccount(profile: String, server: UUID) = "${Item.GATEWAY_API_KEY.raw}.$profile@${server.toString().uppercase()}"

        /** Every secret of [server] goes: the server was removed. */
        fun forgetServer(store: SecretStore, server: UUID) {
            val suffix = "@" + server.toString().uppercase()
            store.allAccounts().filter { it.endsWith(suffix) }.forEach(store::delete)
        }
    }
}
