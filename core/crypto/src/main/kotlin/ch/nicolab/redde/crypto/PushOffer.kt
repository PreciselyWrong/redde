package ch.nicolab.redde.crypto

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * A pairing link, as `hermes redde-push pair` shows it (and draws it as a QR code):
 *
 *     https://redde.goosehouse.org/connect#push=<the plugin's public key>
 *     redde://connect?push=<the plugin's public key>
 *
 * It rides on the setup link's address so a camera opens it in the app; the key comes after the
 * "#", which a browser never sends. Nothing in it is secret: it is one half of a key agreement,
 * and what makes it trustworthy is where it was read, the person's own terminal.
 */
class PushOffer private constructor(
    /** The plugin's X25519 public key for this pairing. */
    val publicKey: ByteArray,
    /** The relay the plugin was told to use, when it isn't the usual one. */
    val relay: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is PushOffer && publicKey.contentEquals(other.publicKey) && relay == other.relay

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + (relay?.hashCode() ?: 0)

    override fun toString(): String = "PushOffer(publicKey=${PushSeal.base64URL(publicKey)}, relay=$relay)"

    companion object {
        const val PARAMETER = "push"

        private const val SCHEME = "redde"
        private const val HOST = "connect"
        private const val WEB_HOST = "redde.goosehouse.org"
        private const val WEB_PATH = "/connect"
        private const val MAXIMUM_LINK_LENGTH = 600

        /** The offer in [url]; null when it isn't a pairing link. */
        fun fromUrl(url: String): PushOffer? {
            if (url.encodeToByteArray().size > MAXIMUM_LINK_LENGTH) return null
            val parameters = linkParameters(url) ?: return null
            val fields = mutableMapOf<String, String>()
            for (pair in parameters.split('&')) {
                if (pair.isEmpty()) continue
                val cut = pair.indexOf('=')
                if (cut < 0) continue
                val value = percentDecoded(pair.substring(cut + 1)) ?: continue
                fields.putIfAbsent(pair.substring(0, cut).lowercase(), value)
            }
            val key = fields[PARAMETER]?.let { PushSeal.data(it) } ?: return null
            if (key.size != 32) return null
            return PushOffer(key, fields["relay"]?.let(::normalizedBase))
        }

        /** The offer in the first setup or pairing link in [text]; null when there is none. */
        fun fromText(text: String): PushOffer? = linkIn(text)?.let(::fromUrl)

        /**
         * A setup link's parameters, still percent-encoded: the query of an app link, the
         * fragment of a web link. Null when [url] isn't such a link; a bare link to the page is
         * just the page.
         */
        internal fun linkParameters(url: String): String? {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme == SCHEME && host == HOST) return uri.rawQuery ?: ""
            if (scheme != "https" || host != WEB_HOST || uri.port != -1) return null
            val path = uri.rawPath ?: ""
            if (path != WEB_PATH && path != "$WEB_PATH/") return null
            val fragment = uri.rawFragment ?: ""
            // Parameters after a "?" would have gone to the server: they are not read, and the
            // link is answered with "no address" instead of silence.
            if (fragment.isEmpty() && uri.rawQuery.isNullOrEmpty()) return null
            return fragment
        }

        /** The first setup or pairing link in [text], up to whitespace, `<`, `>` or `"`. */
        internal fun linkIn(text: String): String? {
            val start = listOf("$SCHEME://$HOST", "https://$WEB_HOST$WEB_PATH")
                .map { text.indexOf(it, ignoreCase = true) }
                .filter { it >= 0 }
                .minOrNull() ?: return null
            val rest = text.substring(start)
            val end = rest.indexOfFirst { it.isWhitespace() || it == '<' || it == '>' || it == '"' }
            val link = if (end < 0) rest else rest.substring(0, end)
            return link.takeIf { linkParameters(it) != null }
        }

        /**
         * A base address as providers document it: a trailing slash or a trailing `/v1` is
         * dropped, because the transports add `v1/…` themselves. Null when nothing is left or it
         * isn't an address.
         */
        internal fun normalizedBase(raw: String): String? {
            var text = raw.trim()
            text = text.trimEnd('/')
            if (text.lowercase().endsWith("/v1")) text = text.dropLast(3)
            text = text.trimEnd('/')
            if (text.isEmpty()) return null
            return try {
                URI(text).toString()
            } catch (_: URISyntaxException) {
                null
            }
        }

        /** Percent-decoding of UTF-8, leaving `+` as it is; null when it is malformed. */
        internal fun percentDecoded(text: String): String? {
            if ('%' !in text) return text
            val out = ByteArrayOutputStream(text.length)
            var index = 0
            while (index < text.length) {
                if (text[index] == '%') {
                    if (index + 3 > text.length) return null
                    val high = Character.digit(text[index + 1], 16)
                    val low = Character.digit(text[index + 2], 16)
                    if (high < 0 || low < 0) return null
                    out.write(high * 16 + low)
                    index += 3
                } else {
                    val next = text.indexOf('%', index).let { if (it < 0) text.length else it }
                    out.write(text.substring(index, next).encodeToByteArray())
                    index = next
                }
            }
            return try {
                out.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
