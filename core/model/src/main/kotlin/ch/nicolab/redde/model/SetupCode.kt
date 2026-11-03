package ch.nicolab.redde.model

/**
 * A connection handed over as a link, or as a QR code of that link, so nobody has to type an
 * address and a key on a phone keyboard. The link comes in two forms with the same parameters:
 *
 *     https://redde.goosehouse.org/connect#name=Home&dashboard=http://hermes.home.example:9119&user=redde&password=…
 *     redde://connect?name=Home&dashboard=http://hermes.home.example:9119&user=redde&password=…
 *
 * The first is a web address: Android opens it in the app as a verified App Link, it is tappable
 * wherever a web address is, and where the app isn't installed the page there says what to do. The
 * connection rides after the "#", the part of an address a browser never sends, so the site doesn't
 * see it. The second is the app's own scheme.
 *
 * Every parameter is optional, but a code has to carry at least one address:
 *
 * - `name`: what the server is called in Settings › Servers.
 * - `dashboard`, `user`, `password`: the Hermes Dashboard (`hermes serve`).
 * - `sign-in`: `none` for a Dashboard with no sign-in, used that way on purpose. Ignored with a
 *   `user` or `password`.
 * - `api`, `key`: the Hermes API server. `profile` names a Hermes profile, `profile-key` is that
 *   profile's own API key when it has one.
 * - `access-id`, `access-secret`: a Cloudflare Access service token.
 * - `pangolin`, `pangolin-api`: Pangolin Link access tokens for the Dashboard and the Hermes API.
 * - `header`: a header a reverse proxy asks for, as `Name: value`. The one parameter that may come
 *   more than once, up to [MAXIMUM_HEADERS] of them.
 * - `model-url`, `model-key`, `model`: an OpenAI-compatible endpoint.
 * - `use`: `dashboard`, `api` or `model`, the connection to talk to. Left out, it is the first of
 *   those the code carries.
 * - `v`: the format's version, 1 today. A higher one is refused instead of half understood.
 *
 * A link is untrusted input. It is shown for confirmation and saved only once the person agrees;
 * nothing here is applied on arrival.
 */
data class SetupCode(
    val name: String = "",
    /** The connection asked for with `use`; [transport] is the one that will be used. */
    val use: Transport? = null,
    val dashboardUrl: String = "",
    val dashboardUser: String = "",
    val dashboardPassword: String = "",
    /** The Dashboard has no sign-in (`sign-in=none`). No secret goes along. */
    val dashboardOpen: Boolean = false,
    val apiUrl: String = "",
    val apiKey: String = "",
    val profile: String = "",
    val profileKey: String = "",
    val accessId: String = "",
    val accessSecret: String = "",
    /** Pangolin Link access tokens (`<token-id>.<token>`) for the Dashboard's resource and the API's. Secrets. */
    val pangolin: String = "",
    val pangolinApi: String = "",
    /** Custom headers for the server. Their values are secrets like a key. */
    val headers: List<CustomHeader> = emptyList(),
    val modelUrl: String = "",
    val modelKey: String = "",
    val model: String = "",
) {
    /** Why a setup link can't be used. */
    sealed class ParseError(message: String) : Exception(message) {
        /** Made by a newer version of the app. */
        data object NewerVersion : ParseError("newer version") { private fun readResolve(): Any = NewerVersion }

        /** No Dashboard, Hermes API or model address in it. */
        data object NoAddress : ParseError("no address") { private fun readResolve(): Any = NoAddress }

        /** An address that isn't an http or https URL ([text] is what was given). */
        data class BadAddress(val text: String) : ParseError("bad address: ${text.take(80)}")

        data object TooLong : ParseError("too long") { private fun readResolve(): Any = TooLong }
    }

    /**
     * The connection the app will talk to: the one asked for when the code carries it, else the first
     * it does carry. Null for a code with no address.
     */
    val transport: Transport?
        get() {
            val carried = buildList {
                if (dashboardUrl.isNotEmpty()) add(Transport.HERMES_SERVE)
                if (apiUrl.isNotEmpty()) add(Transport.HERMES_SESSIONS)
                if (modelUrl.isNotEmpty()) add(Transport.CHAT_COMPLETIONS)
            }
            return use?.takeIf { it in carried } ?: carried.firstOrNull()
        }

    /** Carries a Hermes server (the model endpoint isn't one: it is the app's, not a server's). */
    val hasServer: Boolean get() = dashboardUrl.isNotEmpty() || apiUrl.isNotEmpty()

    val hasSecrets: Boolean
        get() = dashboardPassword.isNotEmpty() || apiKey.isNotEmpty() || profileKey.isNotEmpty() ||
            accessSecret.isNotEmpty() || pangolin.isNotEmpty() || pangolinApi.isNotEmpty() ||
            modelKey.isNotEmpty() || headers.isNotEmpty()

    /** The same code with addresses and names only, for showing where a password shouldn't be. */
    val withoutSecrets: SetupCode
        get() = copy(
            dashboardPassword = "", apiKey = "", profileKey = "", accessSecret = "",
            pangolin = "", pangolinApi = "", headers = emptyList(), modelKey = "",
        )

    /** The parameters, percent-encoded, as both forms of the link carry them. */
    val parameters: String
        get() {
            val items = mutableListOf<Pair<String, String>>()
            fun add(name: String, value: String) {
                if (value.isNotEmpty()) items += name to value
            }
            add("name", name)
            add("dashboard", dashboardUrl)
            add("user", dashboardUser)
            add("password", dashboardPassword)
            if (dashboardOpen && dashboardUrl.isNotEmpty() && dashboardUser.isEmpty() && dashboardPassword.isEmpty()) add("sign-in", "none")
            add("api", apiUrl)
            add("key", apiKey)
            add("profile", profile)
            add("profile-key", profileKey)
            add("access-id", accessId)
            add("access-secret", accessSecret)
            add("pangolin", pangolin)
            add("pangolin-api", pangolinApi)
            for (header in headers) add("header", "${header.name}: ${header.value}")
            add("model-url", modelUrl)
            add("model-key", modelKey)
            add("model", model)
            use?.let {
                add("use", when (it) {
                    Transport.HERMES_SERVE -> "dashboard"
                    Transport.HERMES_SESSIONS -> "api"
                    Transport.CHAT_COMPLETIONS -> "model"
                })
            }
            return items.joinToString("&") { (k, v) -> "$k=" + PercentEncoding.encodeQueryValue(v) }
        }

    /** The link in the app's own scheme. */
    val appLink: String get() = "$SCHEME://$HOST?$parameters"

    /** The link to hand out: the web form (see the class comment for why). */
    val webLink: String get() = "https://$WEB_HOST$WEB_PATH#$parameters"

    companion object {
        const val SCHEME = "redde"
        const val HOST = "connect"

        /** Where the web form of the link lives. Links made here name it; links read here may name any of [WEB_HOSTS]. */
        const val WEB_HOST = "redde.goosehouse.org"
        val WEB_HOSTS = setOf(WEB_HOST)
        const val WEB_PATH = "/connect"
        const val VERSION = 1

        /** Longer than any real code, short enough to fit a QR code a phone can read off a screen. */
        const val MAXIMUM_LENGTH = 2_500
        const val MAXIMUM_HEADERS = 8

        /** The parameter a notification pairing link carries; it sets up no connection. */
        const val PUSH_OFFER_PARAMETER = "push"

        /** Whether [link] is a setup link at all; the rest of the app's links aren't. */
        fun isSetupLink(link: String): Boolean = parameters(link) != null

        /**
         * A setup link's parameters, still percent-encoded: the query of an app link, the fragment of a
         * web link. Null when [link] isn't a setup link; a bare link to the page is just the page.
         */
        fun parameters(link: String): String? {
            val url = SplitLink.of(link) ?: return null
            if (url.scheme == SCHEME && url.host == HOST) return url.query ?: ""
            if (url.scheme != "https" || url.host !in WEB_HOSTS || url.port != null) return null
            if (url.path != WEB_PATH && url.path != "$WEB_PATH/") return null
            val fragment = url.fragment ?: ""
            // Parameters after a "?" would have gone to the server: they are not read, and the link
            // is answered with "no address" instead of silence.
            if (fragment.isEmpty() && url.query.isNullOrEmpty()) return null
            return fragment
        }

        /** Reads a setup link. Null when [link] isn't one; throws [ParseError] when it is one that can't be used. */
        fun read(link: String): SetupCode? {
            val parameters = parameters(link) ?: return null
            if (link.toByteArray(Charsets.UTF_8).size > MAXIMUM_LENGTH) throw ParseError.TooLong
            val fields = HashMap<String, String>()
            val headers = mutableListOf<CustomHeader>()
            // Split by hand: a "+" is a plus sign here, and a badly escaped value is dropped, not fatal.
            for (pair in parameters.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                val name = PercentEncoding.decode(pair.substring(0, eq))?.lowercase() ?: continue
                val value = PercentEncoding.decode(pair.substring(eq + 1))?.trim() ?: continue
                if (name == "header") {
                    // Each one its own header; of two by the same name the first counts, like the rest.
                    val header = header(value)
                    if (header != null && headers.size < MAXIMUM_HEADERS && headers.none { it.id == header.id }) headers += header
                    continue
                }
                // The first of a repeated parameter counts, as it does for the person reading the link.
                if (value.isNotEmpty() && name !in fields) fields[name] = value
            }
            if (PUSH_OFFER_PARAMETER in fields) return null
            fields["v"]?.let { v -> if ((v.toIntOrNull() ?: Int.MAX_VALUE) > VERSION) throw ParseError.NewerVersion }

            val dashboardUrl = address(fields["dashboard"])
            val user = fields["user"] ?: ""
            val password = fields["password"] ?: ""
            val apiUrl = address(fields["api"])
            val modelUrl = address(fields["model-url"])
            val hasServer = dashboardUrl.isNotEmpty() || apiUrl.isNotEmpty()
            val code = SetupCode(
                name = (fields["name"] ?: "").take(60),
                dashboardUrl = dashboardUrl,
                dashboardUser = user,
                dashboardPassword = password,
                dashboardOpen = dashboardUrl.isNotEmpty() && user.isEmpty() && password.isEmpty() &&
                    fields["sign-in"]?.lowercase() == "none",
                apiUrl = apiUrl,
                apiKey = fields["key"] ?: "",
                profile = fields["profile"] ?: "",
                profileKey = fields["profile-key"] ?: "",
                accessId = fields["access-id"] ?: "",
                accessSecret = fields["access-secret"] ?: "",
                pangolin = fields["pangolin"]?.let(Pangolin::token) ?: "",
                pangolinApi = fields["pangolin-api"]?.let(Pangolin::token) ?: "",
                modelUrl = modelUrl,
                // Headers go to a Hermes server; a code for a model endpoint alone has no use for them.
                headers = if (hasServer) headers else emptyList(),
                modelKey = fields["model-key"] ?: "",
                model = fields["model"] ?: "",
                use = when (fields["use"]?.lowercase()) {
                    "dashboard" -> Transport.HERMES_SERVE
                    "api" -> Transport.HERMES_SESSIONS
                    "model" -> Transport.CHAT_COMPLETIONS
                    else -> null
                },
            )
            if (code.transport == null) throw ParseError.NoAddress
            return code
        }

        /**
         * A header as a code writes it, `Name: value`. Null for one the app couldn't send: no name or no
         * value, a name that isn't a header's, or one the connection sets itself.
         */
        fun header(text: String): CustomHeader? {
            val colon = text.indexOf(':')
            if (colon < 0) return null
            val header = CustomHeader(text.substring(0, colon), text.substring(colon + 1))
            return if (header.isUsable) header else null
        }

        /**
         * An address as Setup would accept it typed: http or https, with a host. One with a user name in
         * it is refused: "http://my-server@elsewhere.example" reads like the wrong host.
         */
        private fun address(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val base = ServerAddress.normalizedBase(raw) ?: throw ParseError.BadAddress(raw)
            val parts = ServerAddress.parts(base) ?: throw ParseError.BadAddress(raw)
            if (parts.scheme != "http" && parts.scheme != "https") throw ParseError.BadAddress(raw)
            if (parts.host.isEmpty() || parts.userInfo != null) throw ParseError.BadAddress(raw)
            return base
        }

        /** The first setup link in a piece of text, in either form: what a QR code holds, or what was pasted. */
        fun link(text: String): String? {
            val lower = text.lowercase()
            val start = (listOf("$SCHEME://$HOST") + WEB_HOSTS.map { "https://$it$WEB_PATH" })
                .map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
            val rest = text.substring(start)
            val end = rest.indexOfFirst { it.isWhitespace() || it == '<' || it == '>' || it == '"' }.let { if (it < 0) rest.length else it }
            val link = rest.substring(0, end)
            return if (isSetupLink(link)) link else null
        }
    }
}

/** A setup link that arrived (opened, scanned or pasted): the code in it, or why it can't be used. */
data class SetupCodeOffer(val result: Result<SetupCode>) {
    companion object {
        /** Null when [link] isn't a setup link. */
        fun fromLink(link: String): SetupCodeOffer? =
            try {
                SetupCode.read(link)?.let { SetupCodeOffer(Result.success(it)) }
            } catch (e: SetupCode.ParseError) {
                SetupCodeOffer(Result.failure(e))
            }

        /** Null when there is no setup link in [text]. */
        fun fromText(text: String): SetupCodeOffer? = SetupCode.link(text)?.let(::fromLink)
    }
}

/**
 * A link split into its parts without decoding anything: `scheme://host[:port]path[?query][#fragment]`.
 * Scheme and host come back lower-cased. Null for text that isn't shaped like one.
 */
internal data class SplitLink(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    companion object {
        fun of(link: String): SplitLink? {
            val schemeEnd = link.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = link.substring(0, schemeEnd).lowercase()
            var rest = link.substring(schemeEnd + 3)
            val hash = rest.indexOf('#')
            val fragment = if (hash >= 0) rest.substring(hash + 1) else null
            if (hash >= 0) rest = rest.substring(0, hash)
            val question = rest.indexOf('?')
            val query = if (question >= 0) rest.substring(question + 1) else null
            if (question >= 0) rest = rest.substring(0, question)
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else ""
            if ('@' in authority) return null
            val colon = authority.lastIndexOf(':')
            val host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase()
            val port = if (colon >= 0) authority.substring(colon + 1).toIntOrNull() ?: return null else null
            return SplitLink(scheme, host, port, path, query, fragment)
        }
    }
}
