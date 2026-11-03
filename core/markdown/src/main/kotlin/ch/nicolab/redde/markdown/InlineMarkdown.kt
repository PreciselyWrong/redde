package ch.nicolab.redde.markdown

/**
 * One run of styled inline text.
 *
 * @property url The link destination when the run is (part of) a link, as written in the source
 *   with backslash escapes resolved; `www.` autolinks get an `http://` prefix and `<a@b.c>`
 *   email autolinks a `mailto:` one.
 */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
) {
    /** Both bold and italic (`***x***`). */
    val boldItalic: Boolean get() = bold && italic

    internal fun sameStyle(other: InlineSpan): Boolean =
        bold == other.bold && italic == other.italic && strikethrough == other.strikethrough && code == other.code && url == other.url
}

/**
 * A small, platform-neutral inline Markdown parser: what the iOS app left to Foundation's
 * `AttributedString(markdown:, interpretedSyntax: .inlineOnlyPreservingWhitespace)`.
 *
 * Handles emphasis (`*`/`_`, CommonMark flanking rules and the "rule of three"), strong emphasis,
 * GFM strikethrough (`~x~` / `~~x~~`), code spans, inline links and images (`[t](url "title")`;
 * an image shows its alt text), `<scheme:…>` and `<a@b.c>` autolinks, bare `http(s)://` and
 * `www.` autolinks, backslash escapes and common HTML entities. Whitespace, newlines included,
 * is preserved as written. Raw HTML, reference links and footnotes are left as text.
 */
object InlineMarkdown {
    /** The styled runs of [text]; adjacent runs with the same style are merged. */
    fun parse(text: String): List<InlineSpan> {
        val nodes = Scanner(text).scan(0, text.length, allowLinks = true)
        val out = mutableListOf<InlineSpan>()
        flatten(nodes, InlineSpan(""), out)
        return out
    }

    /** The text [parse] shows, without its styling. */
    fun plainText(text: String): String = parse(text).joinToString("") { it.text }

    // MARK: - Nodes

    private sealed class Node {
        class Text(val text: String) : Node()
        class Code(val text: String) : Node()
        class Link(val children: List<Node>, val url: String?) : Node()
        class Styled(val style: Style, val children: List<Node>) : Node()

        /** A run of `*`, `_` or `~`; what [processEmphasis] leaves of it shows as text. */
        class Delim(val char: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean) : Node() {
            val original = count
        }
    }

    private enum class Style { ITALIC, BOLD, STRIKE }

    private fun flatten(nodes: List<Node>, style: InlineSpan, out: MutableList<InlineSpan>) {
        fun emit(text: String, s: InlineSpan) {
            if (text.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.sameStyle(s)) out[out.lastIndex] = last.copy(text = last.text + text)
            else out += s.copy(text = text)
        }
        for (node in nodes) when (node) {
            is Node.Text -> emit(node.text, style)
            is Node.Delim -> emit(node.char.toString().repeat(node.count), style)
            is Node.Code -> emit(node.text, style.copy(code = true))
            is Node.Link -> flatten(node.children, if (node.url != null) style.copy(url = node.url) else style, out)
            is Node.Styled -> flatten(
                node.children,
                when (node.style) {
                    Style.ITALIC -> style.copy(italic = true)
                    Style.BOLD -> style.copy(bold = true)
                    Style.STRIKE -> style.copy(strikethrough = true)
                },
                out,
            )
        }
    }

    // MARK: - Scanning

    private class Scanner(val s: String) {
        fun scan(from: Int, to: Int, allowLinks: Boolean): MutableList<Node> {
            val nodes = mutableListOf<Node>()
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    nodes += Node.Text(text.toString()); text.clear()
                }
            }
            fun add(node: Node) {
                flushText(); nodes += node
            }

            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < to && isAsciiPunctuation(s[i + 1]) -> {
                        text.append(s[i + 1]); i += 2
                    }
                    c == '`' -> {
                        var j = i
                        while (j < to && s[j] == '`') j++
                        val close = closingBackticks(j - i, j, to)
                        if (close == null) {
                            text.append(s, i, j); i = j
                        } else {
                            add(Node.Code(codeContent(s.substring(j, close))))
                            i = close + (j - i)
                        }
                    }
                    c == '*' || c == '_' || c == '~' -> {
                        var j = i
                        while (j < to && s[j] == c) j++
                        add(delimiter(c, i, j))
                        i = j
                    }
                    c == '&' -> {
                        val entity = entityAt(i, to)
                        if (entity != null) {
                            text.append(entity.first); i = entity.second
                        } else {
                            text.append(c); i++
                        }
                    }
                    c == '!' && i + 1 < to && s[i + 1] == '[' -> {
                        val link = linkAt(i + 1, to)
                        if (link != null) {
                            // An image inline shows its alt text.
                            add(Node.Link(scanned(link.textFrom, link.textTo), null)); i = link.end
                        } else {
                            text.append(c); i++
                        }
                    }
                    c == '[' && allowLinks -> {
                        val link = linkAt(i, to)
                        if (link != null) {
                            add(Node.Link(scanned(link.textFrom, link.textTo), link.url)); i = link.end
                        } else {
                            text.append(c); i++
                        }
                    }
                    c == '<' && allowLinks -> {
                        val auto = angleAutolinkAt(i, to)
                        if (auto != null) {
                            add(Node.Link(listOf(Node.Text(auto.first)), auto.second)); i = auto.third
                        } else {
                            text.append(c); i++
                        }
                    }
                    allowLinks && (c == 'h' || c == 'H' || c == 'w' || c == 'W') && bareUrlAllowedAt(i) -> {
                        val end = bareUrlEnd(i, to)
                        if (end != null) {
                            val raw = s.substring(i, end)
                            val url = if (raw.startsWith("www.", ignoreCase = true)) "http://$raw" else raw
                            add(Node.Link(listOf(Node.Text(raw)), url)); i = end
                        } else {
                            text.append(c); i++
                        }
                    }
                    else -> {
                        text.append(c); i++
                    }
                }
            }
            flushText()
            processEmphasis(nodes)
            return nodes
        }

        /** Link text scanned on its own: emphasis and code, but no nested links. */
        private fun scanned(from: Int, to: Int): List<Node> = scan(from, to, allowLinks = false)

        private fun delimiter(c: Char, start: Int, end: Int): Node.Delim {
            val before = if (start > 0) s[start - 1] else ' '
            val after = if (end < s.length) s[end] else ' '
            val left = !after.isWhitespace() && (!isPunctuation(after) || before.isWhitespace() || isPunctuation(before))
            val right = !before.isWhitespace() && (!isPunctuation(before) || after.isWhitespace() || isPunctuation(after))
            val count = end - start
            return when (c) {
                '_' -> Node.Delim(c, count, left && (!right || isPunctuation(before)), right && (!left || isPunctuation(after)))
                '~' -> if (count > 2) Node.Delim(c, count, false, false) else Node.Delim(c, count, left, right)
                else -> Node.Delim(c, count, left, right)
            }
        }

        /** Index of the run of exactly [count] backticks closing a code span, from [from]. */
        private fun closingBackticks(count: Int, from: Int, to: Int): Int? {
            var i = from
            while (i < to) {
                if (s[i] != '`') {
                    i++; continue
                }
                var j = i
                while (j < to && s[j] == '`') j++
                if (j - i == count) return i
                i = j
            }
            return null
        }

        /** CommonMark: line endings become spaces; one space each side strips when both are there. */
        private fun codeContent(raw: String): String {
            val t = raw.replace('\n', ' ')
            return if (t.length >= 2 && t.startsWith(" ") && t.endsWith(" ") && t.isNotBlank()) t.substring(1, t.length - 1) else t
        }

        class LinkMatch(val textFrom: Int, val textTo: Int, val url: String, val end: Int)

        /** An inline link `[text](destination "title")` opening at [open], or null. */
        private fun linkAt(open: Int, to: Int): LinkMatch? {
            var depth = 0
            var i = open
            var closeBracket = -1
            while (i < to) {
                when (s[i]) {
                    '\\' -> i++
                    '`' -> {
                        // Code spans bind tighter than link brackets.
                        var j = i
                        while (j < to && s[j] == '`') j++
                        val close = closingBackticks(j - i, j, to)
                        i = if (close != null) close + (j - i) - 1 else j - 1
                    }
                    '[' -> depth++
                    ']' -> if (--depth == 0) {
                        closeBracket = i; break
                    }
                }
                i++
            }
            if (closeBracket < 0 || closeBracket + 1 >= to || s[closeBracket + 1] != '(') return null
            val closeParen = MarkdownUrl.closingParen(s, closeBracket + 2, to) ?: return null
            val dest = s.substring(closeBracket + 2, closeParen).trim()
            val url = when {
                dest.startsWith("<") && dest.indexOf('>') > 0 -> dest.substring(1, dest.indexOf('>'))
                else -> dest.takeWhile { !it.isWhitespace() }
            }
            if (url.isEmpty() && dest.isNotEmpty()) return null
            return LinkMatch(open + 1, closeBracket, unescape(url), closeParen + 1)
        }

        /** `<scheme:…>` or `<a@b.c>`: (shown text, url, end). */
        private fun angleAutolinkAt(open: Int, to: Int): Triple<String, String, Int>? {
            var j = open + 1
            while (j < to && s[j] != '>' && s[j] != '<' && !s[j].isWhitespace()) j++
            if (j >= to || s[j] != '>') return null
            val inner = s.substring(open + 1, j)
            return when {
                URI_AUTOLINK.matches(inner) -> Triple(inner, inner, j + 1)
                EMAIL_AUTOLINK.matches(inner) -> Triple(inner, "mailto:$inner", j + 1)
                else -> null
            }
        }

        /** A bare URL starts a line, or follows a space or one of `*_~(`. */
        private fun bareUrlAllowedAt(i: Int): Boolean = i == 0 || s[i - 1].isWhitespace() || s[i - 1] in "*_~("

        /** Where a GFM extended autolink starting at [start] ends, or null when none starts there. */
        private fun bareUrlEnd(start: Int, to: Int): Int? {
            val prefix = listOf("https://", "http://", "www.").firstOrNull { s.startsWith(it, start, ignoreCase = true) } ?: return null
            var end = start
            while (end < to && !s[end].isWhitespace() && s[end] != '<') end++
            // Trailing punctuation belongs to the sentence, an unbalanced `)` to the text around.
            while (end > start + prefix.length) {
                val last = s[end - 1]
                when {
                    last in "?!.,:*_~'\"" -> end--
                    last == ')' && s.substring(start, end).count { it == ')' } > s.substring(start, end).count { it == '(' } -> end--
                    last == ';' -> {
                        var k = end - 2
                        while (k > start && s[k].isLetterOrDigit()) k--
                        if (k > start && s[k] == '&' && k < end - 2) end = k else break
                    }
                    else -> break
                }
            }
            val host = s.substring(start + prefix.length, end).takeWhile { it != '/' && it != '?' && it != '#' }
            return if (host.isNotEmpty() && host.first().isLetterOrDigit()) end else null
        }

        /** An HTML entity at [i]: (decoded text, end). */
        private fun entityAt(i: Int, to: Int): Pair<String, Int>? {
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi >= to || semi - i > 33) return null
            val body = s.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).takeIf { it.length in 1..6 && it.all(::isHexDigit) }?.toInt(16)?.let(::codePointText)
                body.startsWith("#") ->
                    body.drop(1).takeIf { it.length in 1..7 && it.all { c -> c in '0'..'9' } }?.toInt()?.let(::codePointText)
                else -> ENTITIES[body]
            } ?: return null
            return decoded to semi + 1
        }

        private fun codePointText(cp: Int): String =
            if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) "�" else String(Character.toChars(cp))

        private fun unescape(url: String): String {
            if ('\\' !in url) return url
            val sb = StringBuilder()
            var i = 0
            while (i < url.length) {
                if (url[i] == '\\' && i + 1 < url.length && isAsciiPunctuation(url[i + 1])) {
                    sb.append(url[i + 1]); i += 2
                } else {
                    sb.append(url[i]); i++
                }
            }
            return sb.toString()
        }
    }

    // MARK: - Emphasis

    /**
     * CommonMark's "process emphasis": each closer, left to right, pairs with the nearest
     * compatible opener before it; what lies between moves into a styled node, and delimiters
     * left inside it stay literal.
     */
    private fun processEmphasis(nodes: MutableList<Node>) {
        var ci = 0
        while (ci < nodes.size) {
            val closer = nodes[ci] as? Node.Delim
            if (closer == null || !closer.canClose || closer.count == 0) {
                ci++; continue
            }
            var oi = ci - 1
            var opener: Node.Delim? = null
            while (oi >= 0) {
                val o = nodes[oi] as? Node.Delim
                if (o != null && o.char == closer.char && o.canOpen && o.count > 0 && compatible(o, closer)) {
                    opener = o; break
                }
                oi--
            }
            if (opener == null) {
                ci++; continue
            }
            val used = when {
                closer.char == '~' -> closer.count
                opener.count >= 2 && closer.count >= 2 -> 2
                else -> 1
            }
            val style = when {
                closer.char == '~' -> Style.STRIKE
                used == 2 -> Style.BOLD
                else -> Style.ITALIC
            }
            opener.count -= used
            closer.count -= used
            val inner = nodes.subList(oi + 1, ci)
            val children = inner.toList()
            inner.clear()
            nodes.add(oi + 1, Node.Styled(style, children))
            ci = oi + 2
            if (opener.count == 0) {
                nodes.removeAt(oi); ci--
            }
            if (closer.count == 0) nodes.removeAt(ci)
        }
    }

    private fun compatible(opener: Node.Delim, closer: Node.Delim): Boolean {
        if (closer.char == '~') return opener.count == closer.count
        // The rule of three: a run that can both open and close pairs only when the lengths'
        // sum isn't a multiple of 3, unless both are.
        if ((opener.canClose || closer.canOpen) && (opener.original + closer.original) % 3 == 0 &&
            !(opener.original % 3 == 0 && closer.original % 3 == 0)
        ) return false
        return true
    }

    // MARK: - Character classes

    private fun isAsciiPunctuation(c: Char): Boolean = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    private fun isPunctuation(c: Char): Boolean = isAsciiPunctuation(c) || when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private val URI_AUTOLINK = Regex("""[A-Za-z][A-Za-z0-9+.\-]{1,31}:[^\s<>]*""")
    private val EMAIL_AUTOLINK = Regex("""[A-Za-z0-9.!#$%&'*+/=?^_`{|}~\-]+@[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?)*""")

    /** The named entities a chat reply plausibly carries. */
    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶", "plusmn" to "±",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "ne" to "≠",
        "le" to "≤", "ge" to "≥", "infin" to "∞", "check" to "✓", "shy" to "­", "zwj" to "‍",
        "zwnj" to "‌", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    )
}
