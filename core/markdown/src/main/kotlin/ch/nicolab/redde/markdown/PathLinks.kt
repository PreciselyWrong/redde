package ch.nicolab.redde.markdown

import java.util.regex.Pattern

/**
 * File paths a reply names in passing, made links that open the file on the server: an agent
 * that says where it left a file instead of sending it (a Reddit report, 2026-10-22).
 *
 * Port of `ServeMedia.linkingPaths` / `linkedPath` from the iOS app's `ReplyMedia.swift`.
 */
object PathLinks {
    /** The scheme of links that open a file on the server. */
    const val SCHEME = "redde-file"

    // What a reply can name in passing and mean as a file for the person: Hermes's delivery list
    // and the kinds the chat shows. Not code (`.py`, `.swift`): a coding chat names files all the
    // time, and those are the agent's to work on.
    private val imageExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")
    private val docExts = setOf("pdf", "txt", "md", "markdown", "csv", "json", "log", "html", "htm", "yaml", "yml", "xml", "rtf")
    private val audioExts = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "aiff", "aif", "caf")
    /** Hermes's own list of what a `MEDIA:` tag delivers (`MEDIA_DELIVERY_EXTS`). */
    private val deliveryExts = listOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "heic", "mp4", "mov", "m4v", "avi",
        "mkv", "webm", "3gp", "mp3", "m2a", "wav", "ogg", "opus", "m4a", "aac", "flac", "pdf", "docx",
        "doc", "odt", "rtf", "txt", "md", "epub", "xlsx", "xls", "ods", "csv", "tsv", "json", "xml",
        "yaml", "yml", "kmz", "kml", "geojson", "gpx", "pptx", "ppt", "odp", "key", "zip", "tar", "gz",
        "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa", "html", "htm",
    )

    private val linkExts: String = (deliveryExts.toSet() + docExts + audioExts + imageExts)
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it }).joinToString("|")

    /**
     * A path in running text: from `/` or `~/`, from `./`, or relative with a first folder that
     * isn't a web address (`example.com/a.png`); no spaces; ending in one of the link extensions,
     * and not a piece of something longer (a web address, `a.wav.bak`).
     */
    private val plainPath: Regex = run {
        val seg = """[\w.+@%=&-]+"""
        val pattern = """(?<![\w/.:~\\@%=&+-])(?:~?/(?:SEG/)*SEG|\.{1,2}/(?:SEG/)*SEG|[\w+@%=&-]+/(?:SEG/)*SEG)\.(?i:EXTS)(?![\w/-]|\.\w)"""
            .replace("SEG", seg).replace("EXTS", linkExts)
        Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()
    }

    /** A code span that is a path and nothing else; inside backticks a name can have spaces. */
    private val codePath: Regex = run {
        val seg = """[^/\s`](?:[^/`]*[^/\s`])?"""
        val pattern = """^(?:~?/(?:SEG/)*|\.{1,2}/(?:SEG/)*|[^/\s`.:]+/(?:SEG/)*)SEG\.(?i:EXTS)$"""
            .replace("SEG", seg).replace("EXTS", linkExts)
        Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()
    }

    /**
     * A reply's inline Markdown (a paragraph, a list item, a table cell) with each file path it
     * names made a link that opens the file. Links, images, autolinks and code that is more than
     * a path are left as they are; nothing is fetched until the link is tapped.
     */
    fun linkingPaths(inline: String): String {
        if (!inline.contains('/') || !inline.contains('.')) return inline
        val out = StringBuilder()
        val plain = StringBuilder()
        fun flush() {
            out.append(linkingPlain(plain.toString()))
            plain.clear()
        }
        var i = 0
        while (i < inline.length) {
            val c = inline[i]
            val next = i + 1
            if (c == '\\') {
                plain.append(c)
                if (next < inline.length) {
                    plain.append(inline[next]); i = next + 1
                } else {
                    i = next
                }
                continue
            }
            if (c == '`') {
                var j = i
                while (j < inline.length && inline[j] == '`') j++
                val close = closingRun(j - i, inline, j)
                if (close == null) {
                    plain.append(inline, i, j)
                    i = j
                    continue
                }
                flush()
                val span = inline.substring(i, close.last + 1)
                val content = inline.substring(j, close.first).trimSpaces()
                out.append(if (codePath.containsMatchIn(content)) "[$span](${link(content)})" else span)
                i = close.last + 1
                continue
            }
            if (c == '[' || (c == '!' && next < inline.length && inline[next] == '[')) {
                val end = linkEnd(inline, if (c == '!') next else i)
                if (end != null) {
                    flush()
                    out.append(inline, i, end)
                    i = end
                    continue
                }
            }
            if (c == '<') {
                val close = (next until inline.length).firstOrNull { inline[it] == '>' || inline[it].isWhitespace() }
                if (close != null && inline[close] == '>') {
                    flush()
                    out.append(inline, i, close + 1)
                    i = close + 1
                    continue
                }
            }
            plain.append(c)
            i = next
        }
        flush()
        return out.toString()
    }

    /** The path a link from [linkingPaths] opens, or null for any other link. */
    fun linkedPath(url: String): String? {
        if (!url.startsWith("$SCHEME:")) return null
        return percentDecode(url.substring(SCHEME.length + 1))
    }

    private const val LINK_SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._~-"

    private fun link(path: String): String {
        val sb = StringBuilder("$SCHEME:")
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && LINK_SAFE.indexOf(ch) >= 0) sb.append(ch) else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Foundation's `removingPercentEncoding`: null for a malformed escape or invalid UTF-8. */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 3 > s.length) return null
                val hex = s.substring(i + 1, i + 3)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val v = hex.toInt(16)
                bytes.write(v); i += 3
            } else {
                val next = s.indexOf('%', i).let { if (it < 0) s.length else it }
                bytes.write(s.substring(i, next).toByteArray(Charsets.UTF_8)); i = next
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun linkingPlain(text: String): String {
        if (!text.contains('/')) return text
        val out = StringBuilder()
        var last = 0
        for (match in plainPath.findAll(text)) {
            val path = match.value
            // Shown as it is written: nothing in it read as emphasis or an entity.
            val shown = buildString {
                for (ch in path) {
                    if (ch in "\\`*_~&[]<>") append('\\')
                    append(ch)
                }
            }
            out.append(text, last, match.range.first).append("[$shown](${link(path)})")
            last = match.range.last + 1
        }
        return out.append(text, last, text.length).toString()
    }

    /** The run of exactly [count] backticks that closes a code span opened just before [start]. */
    private fun closingRun(count: Int, text: String, start: Int): IntRange? {
        var i = start
        while (true) {
            val tick = text.indexOf('`', i)
            if (tick < 0) return null
            var j = tick
            while (j < text.length && text[j] == '`') j++
            if (j - tick == count) return tick until j
            i = j
        }
    }

    /**
     * Where an inline link `[text](destination)` that opens at [start] ends, or null when the `[`
     * opens no link.
     */
    private fun linkEnd(text: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val open = i + 1
                        if (open >= text.length || text[open] != '(') return null
                        val close = MarkdownUrl.closingParen(text, open + 1) ?: return null
                        return close + 1
                    }
                }
            }
            if (i < text.length) i++
        }
        return null
    }
}
