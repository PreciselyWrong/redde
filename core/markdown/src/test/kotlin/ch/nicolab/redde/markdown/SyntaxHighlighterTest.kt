package ch.nicolab.redde.markdown

import ch.nicolab.redde.markdown.TokenKind.ATTRIBUTE
import ch.nicolab.redde.markdown.TokenKind.COMMENT
import ch.nicolab.redde.markdown.TokenKind.KEY
import ch.nicolab.redde.markdown.TokenKind.KEYWORD
import ch.nicolab.redde.markdown.TokenKind.NUMBER
import ch.nicolab.redde.markdown.TokenKind.STRING
import ch.nicolab.redde.markdown.TokenKind.TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of the iOS `SyntaxHighlighterTests`, plus checks of the Kotlin-side API. */
class SyntaxHighlighterTest {
    private fun tokens(code: String, lang: String): List<HighlightToken> =
        SyntaxHighlighter.tokenize(code, SyntaxLanguage.resolve(lang))

    private fun List<HighlightToken>.has(text: String, kind: TokenKind) = contains(HighlightToken(text, kind))

    @Test
    fun swiftKeywordsStringsComments() {
        val t = tokens("let x = \"hi\" // note\nstruct Foo {}", "swift")
        assertTrue(t.has("let", KEYWORD))
        assertTrue(t.has("\"hi\"", STRING))
        assertTrue(t.has("// note", COMMENT))
        assertTrue(t.has("Foo", TYPE))
    }

    @Test
    fun pythonTripleQuoteSpansLines() {
        val t = tokens("s = \"\"\"a\nb\"\"\"\nprint(3)", "python")
        assertTrue(t.has("\"\"\"a\nb\"\"\"", STRING))
        assertTrue(t.has("3", NUMBER))
    }

    @Test
    fun jsonKeysVersusValues() {
        val t = tokens("{\"name\": \"x\", \"n\": 2, \"ok\": true}", "json")
        assertTrue(t.has("\"name\"", KEY))
        assertTrue(t.has("\"x\"", STRING))
        assertTrue(t.has("true", KEYWORD))
    }

    @Test
    fun shellCommentNotInsideWord() {
        val t = tokens("echo a#b # real", "bash")
        assertFalse(t.any { it.text == "#b # real" })
        assertTrue(t.has("# real", COMMENT))
        assertTrue(t.has("echo", KEYWORD))
    }

    @Test
    fun roundTripsText() {
        val code = "fn main() { let s = \"x\"; /* c */ 42 }"
        assertEquals(code, tokens(code, "rust").joinToString("") { it.text })
    }

    @Test
    fun htmlTagsAndAttributes() {
        val t = tokens("<div class=\"a\">hi</div>", "html")
        assertTrue(t.has("<div", KEYWORD))
        assertTrue(t.has("class", ATTRIBUTE))
        assertTrue(t.has("\"a\"", STRING))
    }

    // Beyond the iOS suite.

    @Test
    fun yamlKeysAttributesAndShellVariables() {
        val yaml = tokens("name: redde\n- port: 8080\nok: yes", "yaml")
        assertTrue(yaml.has("name", KEY))
        assertTrue(yaml.has("port", KEY))
        assertTrue(yaml.has("8080", NUMBER))
        assertTrue(yaml.has("yes", KEYWORD))
        assertTrue(tokens("echo \$HOME \${PATH}", "sh").let { it.has("\$HOME", ATTRIBUTE) && it.has("\${PATH}", ATTRIBUTE) })
        assertTrue(tokens("#[derive(Debug)]\nstruct A;", "rs").has("#[derive(Debug)]", ATTRIBUTE))
        assertTrue(tokens("SELECT id FROM t", "sql").let { it.has("SELECT", KEYWORD) && it.has("FROM", KEYWORD) })
    }

    @Test
    fun aNumberThatIsNoHexDigitDoesNotHang() {
        val code = "x = ½ + 2²"
        assertEquals(code, tokens(code, "python").joinToString("") { it.text })
    }

    @Test
    fun rangesAreUtf16OffsetsOfTheColouredTokens() {
        val code = "😀 let x = 1"
        val ranges = SyntaxHighlighter.highlight(code, "swift")
        assertEquals(listOf(TokenRange(3, 6, KEYWORD), TokenRange(11, 12, NUMBER)), ranges)
        assertEquals("let", code.substring(3, 6))
    }

    @Test
    fun palettesAreOpaqueArgbByName() {
        assertEquals(6, SyntaxPalette.byName.size)
        assertEquals(SyntaxPalette.TERMINAL_DARK, SyntaxPalette.named("terminalDark"))
        // Color(red: 0.61, green: 0.13, blue: 0.53) → 9C2187.
        assertEquals(0xFF9C2187, SyntaxPalette.LIGHT.keyword)
        assertEquals(0xFFFFD600, SyntaxPalette.HERMES_DARK.keyword)
        assertEquals(null, SyntaxPalette.DARK.color(TokenKind.PLAIN))
        assertEquals(SyntaxPalette.DARK.key, SyntaxPalette.DARK.color(KEY))
    }
}
