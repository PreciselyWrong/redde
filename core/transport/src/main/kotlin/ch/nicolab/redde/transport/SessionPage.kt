package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ReddeJson
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * A Dashboard with no sign-in hands its session token to whoever loads its page at `/`
 * (`window.__HERMES_SESSION_TOKEN__="…"`), as the desktop app reads it. Port of SessionPage.swift.
 */
object SessionPage {
    const val HEADER = "X-Hermes-Session-Token"

    /** The token in the page's text, or null. */
    fun token(page: String): String? {
        val text = page.take(512 * 1024)
        val name = text.indexOf("__HERMES_SESSION_TOKEN__")
        if (name < 0) return null
        var i = name + "__HERMES_SESSION_TOKEN__".length
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] != '=') return null
        i++
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length) return null
        val quote = text[i]
        if (quote != '"' && quote != '\'') return null
        val raw = StringBuilder()
        var escaped = false
        for (c in text.substring(i + 1)) {
            if (escaped) { raw.append(c); escaped = false; continue }
            if (c == '\\') { raw.append(c); escaped = true; continue }
            if (c == quote) return decoded(raw.toString())
            if (c == '\n' || c == '\r') return null
            raw.append(c)
        }
        return null
    }

    private fun decoded(raw: String): String? {
        val text = if ('\\' in raw) {
            runCatching { (ReddeJson.parseToJsonElement("\"" + raw.replace("\\'", "'") + "\"") as JsonPrimitive).content }.getOrNull() ?: return null
        } else raw
        return text.trim().ifEmpty { null }
    }

    fun apply(token: String, request: Request): Request = request.newBuilder().header(HEADER, token).build()

    /** Same scheme, host and port (default ports filled in). */
    fun sameOrigin(a: HttpUrl?, b: HttpUrl?): Boolean =
        a != null && b != null && a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    /** Hermes's answer to a host name it won't serve without a sign-in ("Invalid Host header"). */
    fun hostRefusal(status: Int, body: String): DashboardSignInChange? =
        if (status == 400 && "Invalid Host header" in body.take(400)) DashboardSignInChange.AddressRefused else null
}

/** The Dashboard's sign-in is not what the server was set up with. The app words these. */
sealed class DashboardSignInChange(message: String) : Exception(message) {
    /** It had no sign-in when set up, and asks for a login now. */
    data object NowAsksForLogin : DashboardSignInChange("The Dashboard asks for a login now") { private fun readResolve(): Any = NowAsksForLogin }

    /** It was saved with a login and has no sign-in now. */
    data object NoLongerAsks : DashboardSignInChange("The Dashboard doesn't ask for a login any more") { private fun readResolve(): Any = NoLongerAsks }

    /** No sign-in, and it only answers requests addressed to 127.0.0.1 or localhost. */
    data object AddressRefused : DashboardSignInChange("The Dashboard refused the address it was reached by") { private fun readResolve(): Any = AddressRefused }
}
