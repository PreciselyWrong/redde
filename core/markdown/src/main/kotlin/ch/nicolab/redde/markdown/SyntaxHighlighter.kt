package ch.nicolab.redde.markdown

import kotlin.math.roundToLong

/** What a piece of highlighted code is. */
enum class TokenKind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, ATTRIBUTE, KEY }

/** One piece of the scanned code, in order; their texts joined give back the code. */
data class HighlightToken(val text: String, val kind: TokenKind)

/** A coloured range of the code, in UTF-16 offsets (`start` inclusive, `end` exclusive). */
data class TokenRange(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Code-block colours per token kind, as opaque ARGB values (`0xFFRRGGBB`), free of any UI toolkit.
 */
data class SyntaxPalette(
    val keyword: Long,
    val string: Long,
    val comment: Long,
    val number: Long,
    val type: Long,
    val attribute: Long,
    val key: Long,
) {
    /** The colour of [kind], or null for plain text (drawn in the text colour). */
    fun color(kind: TokenKind): Long? = when (kind) {
        TokenKind.PLAIN -> null
        TokenKind.KEYWORD -> keyword
        TokenKind.STRING -> string
        TokenKind.COMMENT -> comment
        TokenKind.NUMBER -> number
        TokenKind.TYPE -> type
        TokenKind.ATTRIBUTE -> attribute
        TokenKind.KEY -> key
    }

    companion object {
        /** An opaque sRGB colour from 0–1 components, as SwiftUI's `Color(red:green:blue:)`. */
        private fun rgb(r: Double, g: Double, b: Double): Long =
            (0xFFL shl 24) or ((r * 255).roundToLong() shl 16) or ((g * 255).roundToLong() shl 8) or (b * 255).roundToLong()

        val LIGHT = SyntaxPalette(
            keyword = rgb(0.61, 0.13, 0.53),
            string = rgb(0.77, 0.10, 0.09),
            comment = rgb(0.42, 0.47, 0.51),
            number = rgb(0.11, 0.00, 0.81),
            type = rgb(0.24, 0.48, 0.55),
            attribute = rgb(0.58, 0.35, 0.09),
            key = rgb(0.24, 0.48, 0.55),
        )

        val DARK = SyntaxPalette(
            keyword = rgb(0.99, 0.47, 0.66),
            string = rgb(0.99, 0.53, 0.40),
            comment = rgb(0.49, 0.55, 0.60),
            number = rgb(0.84, 0.76, 0.47),
            type = rgb(0.60, 0.86, 0.99),
            attribute = rgb(0.75, 0.72, 0.99),
            key = rgb(0.60, 0.86, 0.99),
        )

        /**
         * The Terminal theme's palettes: the hues Claude Code paints in the Mac terminal — rose
         * keywords, lavender numbers, red-orange types, periwinkle attributes.
         */
        val TERMINAL_DARK = SyntaxPalette(
            keyword = rgb(0.92, 0.36, 0.48),
            string = rgb(0.87, 0.77, 0.45),
            comment = rgb(0.50, 0.58, 0.50),
            number = rgb(0.71, 0.66, 0.93),
            type = rgb(0.90, 0.42, 0.38),
            attribute = rgb(0.60, 0.71, 1.00),
            key = rgb(0.60, 0.71, 1.00),
        )

        /** Light counterpart of [TERMINAL_DARK]. */
        val TERMINAL_LIGHT = SyntaxPalette(
            keyword = rgb(0.72, 0.18, 0.32),
            string = rgb(0.60, 0.48, 0.10),
            comment = rgb(0.42, 0.50, 0.42),
            number = rgb(0.42, 0.35, 0.72),
            type = rgb(0.70, 0.25, 0.20),
            attribute = rgb(0.22, 0.38, 0.72),
            key = rgb(0.22, 0.38, 0.72),
        )

        /** The Hermes docs-site look: gold keywords/flags, green commands, warm white. */
        val HERMES_DARK = SyntaxPalette(
            keyword = rgb(1.00, 0.84, 0.00),
            string = rgb(0.27, 0.85, 0.42),
            comment = rgb(0.55, 0.55, 0.50),
            number = rgb(0.95, 0.72, 0.25),
            type = rgb(0.86, 0.86, 0.80),
            attribute = rgb(1.00, 0.84, 0.00),
            key = rgb(0.27, 0.85, 0.42),
        )

        /** Light counterpart of [HERMES_DARK]. */
        val HERMES_LIGHT = SyntaxPalette(
            keyword = rgb(0.55, 0.45, 0.00),
            string = rgb(0.13, 0.55, 0.25),
            comment = rgb(0.45, 0.47, 0.42),
            number = rgb(0.60, 0.45, 0.05),
            type = rgb(0.20, 0.20, 0.22),
            attribute = rgb(0.55, 0.45, 0.00),
            key = rgb(0.13, 0.55, 0.25),
        )

        /** Every palette by its iOS name (`light`, `dark`, `terminalDark`, …). */
        val byName: Map<String, SyntaxPalette> = linkedMapOf(
            "light" to LIGHT,
            "dark" to DARK,
            "terminalDark" to TERMINAL_DARK,
            "terminalLight" to TERMINAL_LIGHT,
            "hermesDark" to HERMES_DARK,
            "hermesLight" to HERMES_LIGHT,
        )

        /** The palette called [name], or null. */
        fun named(name: String): SyntaxPalette? = byName[name]
    }
}

/** How [SyntaxHighlighter] scans one language family. */
class SyntaxLanguage(
    val keywords: Set<String>,
    val lineComment: List<String>,
    val blockComment: Pair<String, String>?,
    val tripleQuotes: Boolean,
    val capitalizedTypes: Boolean,
    /** `@` for Swift/Python/TS decorators, `#` for Rust attributes, `$` for shell variables. */
    val attributePrefix: Char?,
    val yamlKeys: Boolean,
    val jsonKeys: Boolean,
    val tags: Boolean,
) {
    // Markers as code point arrays, built once here: the scanner compares them per character.
    internal val lineCommentCps: List<IntArray> = lineComment.map { it.codePoints().toArray() }
    internal val blockCommentCps: Pair<IntArray, IntArray>? = blockComment?.let { it.first.codePoints().toArray() to it.second.codePoints().toArray() }

    companion object {
        /** The language a fence's info string names; anything unknown gets strings, numbers and comments. */
        fun resolve(name: String?): SyntaxLanguage = when ((name ?: "").lowercase()) {
            "swift" -> SyntaxLanguage(SWIFT_KW, listOf("//"), "/*" to "*/", true, true, '@', false, false, false)
            "python", "py" -> SyntaxLanguage(PYTHON_KW, listOf("#"), null, true, true, '@', false, false, false)
            "js", "javascript", "jsx", "ts", "typescript", "tsx" ->
                SyntaxLanguage(JS_KW, listOf("//"), "/*" to "*/", false, true, '@', false, false, false)
            "sh", "bash", "zsh", "shell", "console", "fish" ->
                SyntaxLanguage(SHELL_KW, listOf("#"), null, false, false, '$', false, false, false)
            "json", "jsonc" -> SyntaxLanguage(setOf("true", "false", "null"), listOf("//"), null, false, false, null, false, true, false)
            "yaml", "yml", "toml", "ini" ->
                SyntaxLanguage(setOf("true", "false", "null", "yes", "no"), listOf("#"), null, false, false, null, true, false, false)
            "go", "golang" -> SyntaxLanguage(GO_KW, listOf("//"), "/*" to "*/", false, true, null, false, false, false)
            "rust", "rs" -> SyntaxLanguage(RUST_KW, listOf("//"), "/*" to "*/", false, true, '#', false, false, false)
            "sql", "psql", "mysql", "sqlite" -> SyntaxLanguage(SQL_KW, listOf("--"), "/*" to "*/", false, false, null, false, false, false)
            "html", "xml", "svg", "vue", "svelte" -> SyntaxLanguage(emptySet(), emptyList(), "<!--" to "-->", false, false, null, false, false, true)
            "c", "cpp", "c++", "h", "hpp", "objc", "objective-c", "java", "kotlin", "kt", "cs", "csharp", "php", "scala", "dart" ->
                SyntaxLanguage(C_KW, listOf("//"), "/*" to "*/", false, true, '@', false, false, false)
            "ruby", "rb" -> SyntaxLanguage(RUBY_KW, listOf("#"), null, false, true, '@', false, false, false)
            "css", "scss" -> SyntaxLanguage(emptySet(), listOf("//"), "/*" to "*/", false, false, '@', true, false, false)
            else -> SyntaxLanguage(emptySet(), listOf("//", "#"), "/*" to "*/", false, false, null, false, false, false)
        }

        private val SWIFT_KW = setOf("import", "let", "var", "func", "return", "if", "else", "guard", "for", "in", "while", "repeat", "switch", "case", "default", "break", "continue", "struct", "class", "enum", "protocol", "extension", "actor", "init", "deinit", "self", "Self", "super", "true", "false", "nil", "throws", "throw", "try", "catch", "async", "await", "some", "any", "where", "as", "is", "static", "private", "public", "internal", "fileprivate", "open", "override", "final", "mutating", "nonisolated", "lazy", "weak", "unowned", "inout", "typealias", "associatedtype", "defer", "do", "fallthrough", "subscript", "operator", "precedencegroup", "indirect", "convenience", "required", "dynamic", "willSet", "didSet", "get", "set")
        private val PYTHON_KW = setOf("def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "import", "from", "as", "try", "except", "finally", "raise", "with", "yield", "lambda", "pass", "break", "continue", "global", "nonlocal", "assert", "del", "async", "await", "True", "False", "None", "self", "print")
        private val JS_KW = setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "new", "delete", "typeof", "instanceof", "in", "of", "class", "extends", "super", "this", "import", "export", "from", "as", "async", "await", "try", "catch", "finally", "throw", "true", "false", "null", "undefined", "interface", "type", "enum", "implements", "public", "private", "protected", "readonly", "static", "yield", "void", "declare", "namespace")
        private val SHELL_KW = setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function", "return", "exit", "export", "local", "source", "echo", "cd", "ls", "cat", "grep", "sed", "awk", "curl", "sudo", "apt", "brew", "pip", "npm", "git", "docker", "python", "python3", "ssh", "chmod", "mkdir", "rm", "cp", "mv", "set", "true", "false", "xargs", "find", "tail", "head", "hermes", "systemctl")
        private val GO_KW = setOf("package", "import", "func", "return", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "select", "fallthrough", "goto", "nil", "true", "false", "make", "new", "len", "cap", "append", "error", "string", "int", "int64", "bool", "byte", "float64")
        private val RUST_KW = setOf("fn", "let", "mut", "pub", "use", "mod", "struct", "enum", "impl", "trait", "for", "in", "while", "loop", "if", "else", "match", "return", "self", "Self", "super", "crate", "as", "ref", "where", "async", "await", "move", "dyn", "const", "static", "unsafe", "type", "true", "false", "Some", "None", "Ok", "Err", "break", "continue")
        private val SQL_KW = setOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop", "alter", "add", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null", "is", "in", "like", "distinct", "union", "primary", "key", "foreign", "references", "index", "view", "with", "case", "when", "then", "else", "end", "count", "sum", "avg", "min", "max", "begin", "commit", "rollback", "returning", "exists", "between", "asc", "desc", "true", "false")
        private val C_KW = setOf("int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "bool", "struct", "union", "enum", "typedef", "const", "static", "extern", "inline", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "goto", "sizeof", "class", "public", "private", "protected", "virtual", "override", "new", "delete", "this", "namespace", "using", "template", "typename", "throw", "try", "catch", "true", "false", "nullptr", "NULL", "auto", "import", "package", "interface", "implements", "extends", "final", "abstract", "var", "val", "fun", "when", "object", "String", "let", "in", "is", "as", "null")
        private val RUBY_KW = setOf("def", "end", "class", "module", "if", "elsif", "else", "unless", "while", "until", "for", "in", "do", "return", "yield", "begin", "rescue", "ensure", "raise", "require", "include", "attr_accessor", "attr_reader", "self", "nil", "true", "false", "and", "or", "not", "then", "case", "when", "puts", "lambda", "proc")
    }
}

/**
 * Small, dependency-free highlighter for fenced code blocks. Covers the languages Hermes tends to
 * emit (Swift, Python, JS/TS, shell, JSON, YAML, Go, Rust, SQL, HTML, C-family); anything else
 * still gets strings, numbers and comments.
 *
 * The scanner walks Unicode code points (Swift walked grapheme clusters; a combining mark counts
 * as part of the identifier it follows, which gives the same result for identifiers).
 */
object SyntaxHighlighter {
    /**
     * The coloured ranges of [code] (plain text left out), in UTF-16 offsets, for the UI layer to
     * paint with [SyntaxPalette.color]. Cheap enough for typical reply-sized blocks; callers
     * should cache it per (code, language) and move big blocks off the main thread.
     */
    fun highlight(code: String, language: String?): List<TokenRange> {
        val ranges = mutableListOf<TokenRange>()
        var offset = 0
        for (token in tokenize(code, SyntaxLanguage.resolve(language))) {
            val end = offset + token.text.length
            if (token.kind != TokenKind.PLAIN) ranges += TokenRange(offset, end, token.kind)
            offset = end
        }
        return ranges
    }

    /** Scans [code] into tokens, in order; their texts joined give back [code]. */
    fun tokenize(code: String, language: SyntaxLanguage): List<HighlightToken> {
        val lang = language
        val chars = code.codePoints().toArray()
        val n = chars.size
        val tokens = mutableListOf<HighlightToken>()
        var i = 0
        val plain = StringBuilder()
        var atLineStart = true

        fun text(from: Int, to: Int) = String(chars, from, to - from)
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                tokens += HighlightToken(plain.toString(), TokenKind.PLAIN); plain.clear()
            }
        }
        fun emit(s: String, kind: TokenKind) {
            flushPlain(); tokens += HighlightToken(s, kind)
        }
        fun starts(p: IntArray, at: Int): Boolean {
            if (at + p.size > n) return false
            for (k in p.indices) if (chars[at + k] != p[k]) return false
            return true
        }
        fun readUntil(terminator: IntArray, from: Int): Int {
            var k = from
            while (k < n) {
                if (starts(terminator, k)) return k + terminator.size
                k++
            }
            return n
        }

        while (i < n) {
            val c = chars[i]

            // Block comment
            val block = lang.blockCommentCps
            if (block != null && starts(block.first, i)) {
                val end = readUntil(block.second, i + block.first.size)
                emit(text(i, end), TokenKind.COMMENT); i = end; continue
            }
            // Line comment (shell/YAML `#` must not be inside a word; `//` never is)
            val lc = lang.lineCommentCps.firstOrNull { starts(it, i) }
            if (lc != null && (!lc.contentEquals(HASH) || i == 0 || !isIdent(chars[i - 1]))) {
                var end = i
                while (end < n && chars[end] != '\n'.code) end++
                emit(text(i, end), TokenKind.COMMENT); i = end; continue
            }
            // Triple-quoted strings
            if (lang.tripleQuotes && starts(TRIPLE_QUOTE, i)) {
                val end = readUntil(TRIPLE_QUOTE, i + 3)
                emit(text(i, end), TokenKind.STRING); i = end; continue
            }
            // Quoted strings
            if (c == '"'.code || c == '\''.code || c == '`'.code) {
                var j = i + 1
                while (j < n && chars[j] != c) {
                    if (chars[j] == '\\'.code) j++
                    if (j < n && chars[j] == '\n'.code && c != '`'.code) break
                    j++
                }
                val end = minOf(j + 1, n)
                // JSON: a string followed by a colon is a key.
                var k = end
                while (k < n && chars[k] == ' '.code) k++
                val isKey = lang.jsonKeys && k < n && chars[k] == ':'.code
                emit(text(i, end), if (isKey) TokenKind.KEY else TokenKind.STRING); i = end; atLineStart = false; continue
            }
            // Tags
            if (lang.tags && c == '<'.code && i + 1 < n && (Character.isLetter(chars[i + 1]) || chars[i + 1] == '/'.code)) {
                var j = i + 1
                if (chars[j] == '/'.code) j++
                while (j < n && (isIdent(chars[j]) || chars[j] == '-'.code || chars[j] == ':'.code)) j++
                emit(text(i, j), TokenKind.KEYWORD); i = j; continue
            }
            // Attributes / decorators / shell variables
            val p = lang.attributePrefix
            if (p != null && c == p.code && i + 1 < n && (isIdent(chars[i + 1]) || chars[i + 1] == '['.code || chars[i + 1] == '{'.code)) {
                var j = i + 1
                when (chars[j]) {
                    '['.code -> j = readUntil(CLOSE_BRACKET, j)
                    '{'.code -> j = readUntil(CLOSE_BRACE, j)
                    else -> while (j < n && isIdent(chars[j])) j++
                }
                emit(text(i, j), TokenKind.ATTRIBUTE); i = j; continue
            }
            // Numbers
            if (isNumber(c) && (i == 0 || !isIdent(chars[i - 1]))) {
                var j = i
                while (j < n && (isHexDigit(chars[j]) || chars[j] == '.'.code || chars[j] == 'x'.code || chars[j] == '_'.code)) j++
                // A number that is no hex digit (`½`, `²`) is still one character: Swift's scanner
                // stood still on it and never finished.
                if (j == i) j = i + 1
                emit(text(i, j), TokenKind.NUMBER); i = j; continue
            }
            // Identifiers
            if (Character.isLetter(c) || c == '_'.code) {
                var j = i
                while (j < n && isIdent(chars[j])) j++
                val word = text(i, j)
                // YAML key: first word on the line followed by a colon.
                var k = j
                while (k < n && (chars[k] == ' '.code || chars[k] == '-'.code || isIdent(chars[k]))) k++
                if (lang.yamlKeys && atLineStart && k < n && chars[k] == ':'.code) {
                    emit(text(i, k), TokenKind.KEY); i = k; atLineStart = false; continue
                }
                if (word in lang.keywords || word.lowercase() in lang.keywords) {
                    emit(word, TokenKind.KEYWORD)
                } else if (lang.capitalizedTypes && Character.isUpperCase(c) && j - i > 1) {
                    emit(word, TokenKind.TYPE)
                } else if (lang.tags && i > 0 && (chars[i - 1] == ' '.code || chars[i - 1] == '\n'.code)) {
                    // attribute name inside a tag
                    var m = j
                    while (m < n && chars[m] == ' '.code) m++
                    if (m < n && chars[m] == '='.code) emit(word, TokenKind.ATTRIBUTE) else plain.append(word)
                } else {
                    plain.append(word)
                }
                i = j; atLineStart = false; continue
            }

            plain.appendCodePoint(c)
            if (c == '\n'.code) atLineStart = true else if (c != ' '.code && c != '\t'.code && c != '-'.code) atLineStart = false
            i++
        }
        flushPlain()
        return tokens
    }

    private val TRIPLE_QUOTE = intArrayOf('"'.code, '"'.code, '"'.code)
    private val HASH = intArrayOf('#'.code)
    private val CLOSE_BRACKET = intArrayOf(']'.code)
    private val CLOSE_BRACE = intArrayOf('}'.code)

    private fun isMark(cp: Int): Boolean = Character.getType(cp).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    private fun isNumber(cp: Int): Boolean = Character.getType(cp).let {
        it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
    }

    private fun isIdent(cp: Int): Boolean = Character.isLetter(cp) || isNumber(cp) || cp == '_'.code || isMark(cp)

    /** Swift's `Character.isHexDigit`: ASCII and fullwidth hex digits. */
    private fun isHexDigit(cp: Int): Boolean =
        cp in '0'.code..'9'.code || cp in 'a'.code..'f'.code || cp in 'A'.code..'F'.code ||
            cp in 0xFF10..0xFF19 || cp in 0xFF21..0xFF26 || cp in 0xFF41..0xFF46
}
