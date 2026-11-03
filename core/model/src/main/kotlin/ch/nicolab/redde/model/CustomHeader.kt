package ch.nicolab.redde.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A header of the person's own that every request to a Hermes server carries: what a reverse proxy
 * in front of the server asks for before it lets a request through (a token, a tenant, its own basic
 * auth). Kept with the server's other secrets, the name too.
 */
@Serializable
class CustomHeader private constructor(val name: String, val value: String) {
    /** Header names don't tell upper case from lower: one "X-Token" at most. */
    val id: String get() = name.lowercase()

    val isUsable: Boolean get() = problem(name) == null && value.isNotEmpty() && value.none { it == '\n' || it == '\r' }

    override fun equals(other: Any?) = other is CustomHeader && name == other.name && value == other.value
    override fun hashCode() = 31 * name.hashCode() + value.hashCode()
    override fun toString() = "CustomHeader($name)"

    /** Why a header name can't be used, or nil when it can. */
    sealed interface Problem {
        data object NoName : Problem
        /** Not an HTTP token (RFC 9110): no spaces, no colon. */
        data object NotAToken : Problem
        /** Set by the connection itself and can't be replaced. */
        data class Reserved(val name: String) : Problem
    }

    companion object {
        /** Builds a header with both parts trimmed, as the iOS initializer did. */
        operator fun invoke(name: String, value: String) = CustomHeader(name.trim(), value.trim())

        /** Headers the connection itself depends on. Setting one would break the request or the WebSocket. */
        private val RESERVED = setOf("host", "content-length", "connection", "upgrade", "transfer-encoding", "te", "trailer")
        private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

        fun problem(name: String): Problem? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return Problem.NoName
            if (!trimmed.all { it.code < 128 && (it.isLetterOrDigit() || it in TOKEN_SYMBOLS) }) return Problem.NotAToken
            val lower = trimmed.lowercase()
            if (lower in RESERVED || lower.startsWith("sec-websocket-")) return Problem.Reserved(trimmed)
            return null
        }

        private val listSerializer = ListSerializer(serializer())

        /** The list as the secret store holds it (JSON), and back. An empty list is no entry at all. */
        fun encode(headers: List<CustomHeader>): String =
            if (headers.isEmpty()) "" else ReddeJson.encodeToString(listSerializer, headers)

        fun decode(stored: String?): List<CustomHeader> =
            if (stored.isNullOrEmpty()) emptyList() else runCatching { ReddeJson.decodeFromString(listSerializer, stored) }.getOrDefault(emptyList())

        /** The headers as request fields; one that couldn't be sent is left out, and of two by one name the first counts. */
        fun fields(headers: List<CustomHeader>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val seen = HashSet<String>()
            for (h in headers) if (h.isUsable && seen.add(h.id)) out[h.name] = h.value
            return out
        }

        /** [headers] with [new] added, in place of one by the same name. */
        fun adding(new: CustomHeader, to: List<CustomHeader>): List<CustomHeader> = to.filter { it.id != new.id } + new
    }
}
