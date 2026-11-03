package ch.nicolab.redde.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The three ways Redde talks to a backend. Declaration order is the order Settings and setup list them. */
@Serializable
enum class Transport {
    /** `hermes serve`, the Hermes Dashboard, over WebSocket: live reasoning, approvals, slash commands. */
    @SerialName("hermesServe") HERMES_SERVE,

    /** The Hermes gateway's API server: the shared session ledger, with a bearer key. */
    @SerialName("hermesSessions") HERMES_SESSIONS,

    /** OpenAI-compatible `/v1/chat/completions`, straight to a model with no agent. */
    @SerialName("chatCompletions") CHAT_COMPLETIONS,
}

/** Server addresses as Setup stores them. */
object ServerAddress {
    /**
     * An address with its trailing slashes and a trailing `/v1` taken off: `https://h:8642/v1/`
     * becomes `https://h:8642`. Null when nothing is left.
     */
    fun normalizedBase(raw: String): String? {
        var text = raw.trim()
        text = text.trimEnd('/')
        if (text.lowercase().endsWith("/v1")) text = text.dropLast(3)
        text = text.trimEnd('/')
        return text.ifEmpty { null }
    }

    /** The parts of an http(s) address that matter for checking it, or null when it isn't one. */
    data class Parts(val scheme: String, val host: String, val port: Int?, val userInfo: String?)

    /**
     * Splits `scheme://[user@]host[:port][/…]` by hand. `java.net.URI` refuses host names it doesn't
     * like (an underscore) and escapes it doesn't know; an address is checked here only for what Redde
     * needs of it.
     */
    fun parts(address: String): Parts? {
        val schemeEnd = address.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = address.substring(0, schemeEnd)
        if (!scheme.all { it.isLetterOrDigit() || it in "+-." } || !scheme[0].isLetter()) return null
        val rest = address.substring(schemeEnd + 3)
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val host: String
        val port: Int?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val after = hostPort.substring(close + 1)
            port = if (after.startsWith(":")) after.drop(1).toIntOrNull() ?: return null else if (after.isEmpty()) null else return null
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) {
                host = hostPort.substring(0, colon)
                val portText = hostPort.substring(colon + 1)
                port = if (portText.isEmpty()) null else portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            } else {
                host = hostPort
                port = null
            }
        }
        if (host.any { it.isWhitespace() }) return null
        return Parts(scheme.lowercase(), host, port, userInfo)
    }
}
