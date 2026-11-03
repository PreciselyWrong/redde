package ch.nicolab.redde.markdown

// Character classes matching Foundation's, so trimming behaves as it did on iOS.

/** Foundation's `.whitespaces`: tab and the Unicode space separators (Zs), no newlines. */
internal fun Char.isInlineSpace(): Boolean = this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/** Foundation's `.whitespacesAndNewlines`: [isInlineSpace] plus line and paragraph breaks. */
internal fun Char.isSpaceOrNewline(): Boolean =
    isInlineSpace() || this in '\n'..'\r' || this == '\u0085' ||
        Character.getType(this).let { it == Character.LINE_SEPARATOR.toInt() || it == Character.PARAGRAPH_SEPARATOR.toInt() }

/** `trimmingCharacters(in: .whitespaces)`. */
internal fun String.trimSpaces(): String = trim { it.isInlineSpace() }

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimSpacesAndNewlines(): String = trim { it.isSpaceOrNewline() }

/** Inline Markdown destination scanning shared by the image and link scanners. */
internal object MarkdownUrl {
    /**
     * The index of the `)` closing a destination that starts at [start] (right after `](`),
     * honouring balanced parentheses inside the URL — a Wikipedia-style `/Foo_(bar).png` link
     * must not truncate at the inner `)`. Null when it never closes before [end].
     */
    fun closingParen(s: CharSequence, start: Int, end: Int = s.length): Int? {
        var depth = 1
        for (i in start until end) {
            when (s[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return null
    }
}

/**
 * Swift's `Character.isNumber` for one UTF-16 unit: any Unicode number (decimal digits, letter
 * numbers such as Roman numerals, other numbers such as `½` or `²`).
 */
internal fun Char.isNumeric(): Boolean = Character.getType(this).let {
    it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
}
