package ch.nicolab.redde.model

/**
 * Pangolin Link access tokens (`<token-id>.<token>`), sent as `P-Access-Token-Id` and
 * `P-Access-Token` to a resource behind [Pangolin](https://pangolin.net).
 */
object Pangolin {
    const val ID_HEADER = "P-Access-Token-Id"
    const val TOKEN_HEADER = "P-Access-Token"

    /**
     * A token as stored, `id.token`, from what was pasted: the pair itself, or a link carrying it as
     * `p_token=`. Null for anything else.
     */
    fun token(pasted: String): String? {
        var text = pasted.trim()
        val marker = text.indexOf("p_token=")
        if (marker >= 0) {
            text = text.substring(marker + "p_token=".length).takeWhile { it != '&' && it != '#' }
            text = PercentEncoding.decode(text) ?: text
        }
        val (id, token) = parts(text) ?: return null
        return "$id.$token"
    }

    /** The two halves of a stored token, or null when it isn't one. */
    fun parts(text: String): Pair<String, String>? {
        val pair = text.trim()
        val dot = pair.indexOf('.')
        if (dot < 0) return null
        val id = pair.substring(0, dot)
        val token = pair.substring(dot + 1)
        if (id.isEmpty() || token.isEmpty()) return null
        if (!(id + token).all { it.isLetterOrDigit() || it in "-_." }) return null
        return id to token
    }

    /** The request headers for a stored token; none for one that isn't. */
    fun headers(stored: String?): Map<String, String> {
        val (id, token) = stored?.let(::parts) ?: return emptyMap()
        return mapOf(ID_HEADER to id, TOKEN_HEADER to token)
    }
}

/** Percent-encoding as setup links use it: a `+` is a plus sign, never a space. */
object PercentEncoding {
    /** Decodes `%XX` escapes as UTF-8; null for an escape that isn't one or bytes that aren't UTF-8. */
    fun decode(text: String): String? {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 > text.lastIndex) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val next = text.indexOf('%', i).let { if (it < 0) text.length else it }
                bytes.write(text.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /** Characters left as they are in a query value; everything else, `&`, `=`, `+` and `#` included, is escaped. */
    private const val KEPT = "-._~!$'()*,;:@/?"

    fun encodeQueryValue(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code < 128 && (c.isLetterOrDigit() || c in KEPT)) append(c)
            else append('%').append("%02X".format(b.toInt() and 0xFF))
        }
    }
}
