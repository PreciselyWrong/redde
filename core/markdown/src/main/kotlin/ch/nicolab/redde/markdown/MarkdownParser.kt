package ch.nicolab.redde.markdown

import ch.nicolab.redde.markdown.MarkdownBlock.ColumnAlignment
import ch.nicolab.redde.markdown.MarkdownBlock.ListItem

/**
 * Splits a reply's Markdown into [MarkdownBlock]s. Tolerant of half-written input: a reply that is
 * still streaming parses into what has arrived (an unterminated fence or math block runs to the
 * end).
 */
object MarkdownParser {
    /** The blocks of [source]. */
    fun parse(source: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        var i = 0
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            // Lines join with a space, except after a hard break (two trailing spaces, a
            // backslash, or <br>), which keeps the newline.
            val text = StringBuilder()
            for ((n, raw) in paragraph.withIndex()) {
                var line = raw.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
                var hard = false
                if (line.endsWith("  ")) {
                    hard = true; line = line.trimSpaces()
                } else if (line.endsWith("\\")) {
                    hard = true; line = line.dropLast(1)
                }
                text.append(if (n == 0 || text.endsWith("\n")) line else " $line")
                if (hard) text.append('\n')
            }
            val joined = text.toString().trimSpacesAndNewlines()
            if (joined.isNotEmpty()) {
                val code = loneInlineCode(joined)
                val image = if (code == null) parseImage(joined) else null
                when {
                    code != null -> blocks += code
                    image != null -> blocks += image
                    else -> blocks += splitEmbeddedImages(joined)
                }
            }
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimSpaces()

            // Fenced code (an unterminated fence, mid-stream, runs to the end).
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.drop(3).trimSpaces()
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimSpaces().startsWith("```")) {
                    body += lines[i]; i++
                }
                i++
                blocks += MarkdownBlock.Code(language.ifEmpty { null }, body.joinToString("\n"))
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph(); i++; continue
            }

            // Display math. Single line `$$ x $$`, or an opening `$$` / `\[` closed on a later line.
            val math = parseMathStart(trimmed)
            if (math != null) {
                flushParagraph()
                if (math.closedBody != null) {
                    blocks += MarkdownBlock.Math(math.closedBody); i++; continue
                }
                val body = if (math.firstLine.isEmpty()) mutableListOf() else mutableListOf(math.firstLine)
                i++
                // An unterminated block mid-stream renders what has arrived.
                while (i < lines.size) {
                    val l = lines[i].trimSpaces()
                    val end = l.indexOf(math.closer)
                    if (end >= 0) {
                        val before = l.substring(0, end).trimSpaces()
                        if (before.isNotEmpty()) body += before
                        i++; break
                    }
                    body += l; i++
                }
                blocks += MarkdownBlock.Math(body.joinToString("\n"))
                continue
            }

            val heading = parseHeading(trimmed)
            if (heading != null) {
                flushParagraph(); blocks += heading; i++; continue
            }

            // Setext heading: a single paragraph line underlined with === or ---.
            if (paragraph.size == 1 && isSetextUnderline(trimmed)) {
                val text = paragraph[0].trimSpaces()
                paragraph.clear()
                blocks += MarkdownBlock.Heading(if (trimmed.startsWith("=")) 1 else 2, text)
                i++; continue
            }

            if (isRule(trimmed)) {
                flushParagraph(); blocks += MarkdownBlock.Rule; i++; continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimSpaces().startsWith(">")) {
                    var body = lines[i].trimSpaces().drop(1)
                    if (body.startsWith(" ")) body = body.drop(1)
                    quote += body
                    i++
                }
                blocks += MarkdownBlock.Quote(parse(quote.joinToString("\n")))
                continue
            }

            val item = parseListItem(line)
            if (item != null) {
                flushParagraph()
                val items = mutableListOf(item)
                i++
                while (i < lines.size) {
                    val next = parseListItem(lines[i])
                    if (next != null) {
                        items += next; i++
                    } else if (lines[i].trimSpaces().isNotEmpty() && lines[i].startsWith("  ")) {
                        // Continuation line of the previous item.
                        val last = items.removeAt(items.lastIndex)
                        items += last.copy(text = last.text + " " + lines[i].trimSpaces())
                        i++
                    } else {
                        break
                    }
                }
                blocks += MarkdownBlock.ListBlock(items)
                continue
            }

            if (trimmed.startsWith("|") && i + 1 < lines.size && isTableDivider(lines[i + 1])) {
                flushParagraph()
                val header = cells(trimmed)
                val alignments = cells(lines[i + 1].trimSpaces()).map { spec ->
                    val l = spec.startsWith(":")
                    val r = spec.endsWith(":")
                    when {
                        l && r -> ColumnAlignment.CENTER
                        r -> ColumnAlignment.TRAILING
                        else -> ColumnAlignment.LEADING
                    }
                }
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimSpaces().startsWith("|")) {
                    val row = cells(lines[i].trimSpaces()).toMutableList()
                    while (row.size < header.size) row += ""
                    rows += row.take(header.size); i++
                }
                blocks += MarkdownBlock.Table(header, rows, alignments)
                continue
            }

            paragraph += if (line.trimSpaces().isEmpty()) "" else line.dropWhile { it == ' ' }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * [parse], with each file path in the inline text made a link that opens it
     * ([PathLinks.linkingPaths]) when [linkPaths] is set: a reply's, once it has finished.
     */
    fun parse(source: String, linkPaths: Boolean): List<MarkdownBlock> {
        val blocks = parse(source)
        return if (linkPaths) blocks.map { it.mapInline(PathLinks::linkingPaths) } else blocks
    }

    private fun parseHeading(line: String): MarkdownBlock? {
        var level = 0
        var idx = 0
        while (idx < line.length && line[idx] == '#' && level < 6) {
            level++; idx++
        }
        if (level == 0 || idx >= line.length || line[idx] != ' ') return null
        return MarkdownBlock.Heading(level, line.substring(idx).trimSpaces())
    }

    private class MathStart(val closer: String, val firstLine: String, val closedBody: String?)

    private fun parseMathStart(line: String): MathStart? {
        for ((opener, closer) in listOf("$$" to "$$", "\\[" to "\\]")) {
            if (!line.startsWith(opener)) continue
            val rest = line.drop(opener.length)
            if (rest.isNotEmpty() && rest.endsWith(closer)) {
                val body = rest.dropLast(closer.length).trimSpaces()
                if (body.isEmpty()) return null // bare "$$$$"
                return MathStart(closer, "", body)
            }
            return MathStart(closer, rest.trimSpaces(), null)
        }
        return null
    }

    private fun isSetextUnderline(line: String): Boolean =
        line.length >= 3 && (line.all { it == '=' } || line.all { it == '-' })

    /**
     * A paragraph that is exactly one inline code span — how models often present a runnable
     * command (`ssh host "…"`) — promotes to a code block, so it gets the copyable code window
     * instead of styled prose. Inline code *within* a sentence stays inline.
     */
    private fun loneInlineCode(text: String): MarkdownBlock? {
        if (text.length <= 2 || !text.startsWith("`") || !text.endsWith("`") || text.startsWith("```")) return null
        val inner = text.substring(1, text.length - 1)
        if (inner.isEmpty() || inner.contains('`') || inner.contains('\n')) return null
        return MarkdownBlock.Code(null, inner)
    }

    /** `![alt](url)` on its own, optionally wrapped in a link. */
    private fun parseImage(text: String): MarkdownBlock? {
        var t = text
        if (t.startsWith("[") && t.endsWith(")")) {
            val close = t.lastIndexOf("](")
            if (close >= 0) t = t.substring(1, close) // unwrap [![alt](img)](link)
        }
        if (!t.startsWith("![") || !t.endsWith(")")) return null
        val mid = t.indexOf("](")
        if (mid < 0) return null
        val alt = t.substring(2, mid)
        var url = t.substring(mid + 2, t.length - 1)
        if (url.contains("![")) return null // a second image: not one standalone image
        url = url.substringBefore(' ') // drop a title
        if (url.isEmpty()) return null
        return MarkdownBlock.Image(alt, url)
    }

    /**
     * A paragraph with `![alt](url)` mid-text — how the API server inlines a picture the agent
     * sends ("Here it is ![image](data:…)"). Splits into paragraph and image blocks so the
     * picture renders instead of showing as raw markup.
     */
    private fun splitEmbeddedImages(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var rest = 0
        fun flushText(from: Int, to: Int) {
            val trimmed = text.substring(from, to).trimSpacesAndNewlines()
            if (trimmed.isNotEmpty()) blocks += MarkdownBlock.Paragraph(trimmed)
        }
        while (true) {
            val start = text.indexOf("![", rest)
            if (start < 0) break
            val mid = text.indexOf("](", start + 2)
            if (mid < 0) break
            val close = MarkdownUrl.closingParen(text, mid + 2) ?: break
            val afterClose = close + 1
            val url = text.substring(mid + 2, close).substringBefore(' ') // drop a title
            if (url.isEmpty()) {
                flushText(rest, afterClose)
                rest = afterClose
                continue
            }
            flushText(rest, start)
            blocks += MarkdownBlock.Image(text.substring(start + 2, mid), url)
            rest = afterClose
        }
        flushText(rest, text.length)
        return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(text)) }
    }

    private fun isRule(line: String): Boolean {
        val stripped = line.replace(" ", "")
        return stripped.length >= 3 && (stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' })
    }

    private fun parseListItem(line: String): ListItem? {
        val leading = line.takeWhile { it == ' ' }.length
        val body = line.substring(leading)
        if (body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ ")) {
            var text = body.drop(2).trimSpaces()
            var checked: Boolean? = null
            if (text.startsWith("[ ] ") || text == "[ ]") {
                checked = false; text = text.drop(3).trimSpaces()
            } else if (text.lowercase().startsWith("[x] ") || text.lowercase() == "[x]") {
                checked = true; text = text.drop(3).trimSpaces()
            }
            return ListItem(ordered = false, marker = "•", text = text, indent = leading / 2, checked = checked)
        }
        // "1. " / "12) "
        var idx = 0
        while (idx < body.length && body[idx].isNumeric()) idx++
        val digits = body.substring(0, idx)
        if (digits.isEmpty() || idx >= body.length || (body[idx] != '.' && body[idx] != ')')) return null
        val afterMarker = idx + 1
        if (afterMarker >= body.length || body[afterMarker] != ' ') return null
        return ListItem(ordered = true, marker = "$digits.", text = body.substring(afterMarker).trimSpaces(), indent = leading / 2)
    }

    private fun isTableDivider(line: String): Boolean {
        val t = line.trimSpaces()
        if (!t.startsWith("|")) return false
        val inner = t.replace("|", "").replace(" ", "")
        return inner.isNotEmpty() && inner.all { it == '-' || it == ':' }
    }

    private fun cells(line: String): List<String> {
        var t = line
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        return t.split("|").map { it.trimSpaces() }
    }
}
