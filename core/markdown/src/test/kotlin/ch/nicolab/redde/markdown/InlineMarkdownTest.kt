package ch.nicolab.redde.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineMarkdownTest {
    private fun parse(text: String) = InlineMarkdown.parse(text)

    @Test
    fun plainTextIsOneRun() {
        assertEquals(listOf(InlineSpan("just words")), parse("just words"))
        assertEquals(emptyList(), parse(""))
    }

    @Test
    fun emphasisStrongAndBoth() {
        assertEquals(listOf(InlineSpan("a "), InlineSpan("b", bold = true), InlineSpan(" c")), parse("a **b** c"))
        assertEquals(listOf(InlineSpan("a "), InlineSpan("b", italic = true)), parse("a *b*"))
        assertEquals(listOf(InlineSpan("b", bold = true)), parse("__b__"))
        assertEquals(listOf(InlineSpan("b", italic = true)), parse("_b_"))
        val both = parse("***x***").single()
        assertTrue(both.boldItalic)
        assertEquals("x", both.text)
        assertEquals(
            listOf(InlineSpan("bold ", bold = true), InlineSpan("both", bold = true, italic = true), InlineSpan(" bold", bold = true)),
            parse("**bold *both* bold**"),
        )
    }

    @Test
    fun intrawordUnderscoresAndLoneStarsStayText() {
        assertEquals(listOf(InlineSpan("snake_case_name")), parse("snake_case_name"))
        assertEquals(listOf(InlineSpan("2 * 3 * 4")), parse("2 * 3 * 4"))
        assertEquals(listOf(InlineSpan("**not closed")), parse("**not closed"))
        assertEquals(listOf(InlineSpan("a"), InlineSpan("b", italic = true), InlineSpan("c")), parse("a*b*c"))
    }

    @Test
    fun strikethrough() {
        assertEquals(listOf(InlineSpan("old", strikethrough = true), InlineSpan(" new")), parse("~~old~~ new"))
        assertEquals(listOf(InlineSpan("x", strikethrough = true)), parse("~x~"))
        assertEquals(listOf(InlineSpan("~~x~")), parse("~~x~"))
    }

    @Test
    fun codeSpansAreLiteral() {
        assertEquals(listOf(InlineSpan("run "), InlineSpan("a *b* c", code = true)), parse("run `a *b* c`"))
        assertEquals(listOf(InlineSpan("a `b` c", code = true)), parse("`` a `b` c ``"))
        assertEquals(listOf(InlineSpan("`unclosed")), parse("`unclosed"))
        assertEquals(listOf(InlineSpan("**", code = true), InlineSpan(" x")), parse("`**` x"))
    }

    @Test
    fun linksCarryTheirUrlAndStyledText() {
        assertEquals(
            listOf(InlineSpan("see "), InlineSpan("the ", url = "https://x.org/a"), InlineSpan("docs", bold = true, url = "https://x.org/a")),
            parse("see [the **docs**](https://x.org/a \"Title\")"),
        )
        assertEquals(listOf(InlineSpan("wiki", url = "https://w.org/Paris_(France)")), parse("[wiki](https://w.org/Paris_(France))"))
        assertEquals(listOf(InlineSpan("[not a link] here")), parse("[not a link] here"))
        assertEquals(listOf(InlineSpan("code", code = true, url = "u")), parse("[`code`](u)"))
        assertEquals(listOf(InlineSpan("x", url = "a b")), parse("[x](<a b>)"))
    }

    @Test
    fun autolinks() {
        assertEquals(listOf(InlineSpan("go to "), InlineSpan("https://x.org/a_b_c", url = "https://x.org/a_b_c"), InlineSpan(".")), parse("go to https://x.org/a_b_c."))
        assertEquals(listOf(InlineSpan("(", ), InlineSpan("http://x.org/p", url = "http://x.org/p"), InlineSpan(")")), parse("(http://x.org/p)"))
        assertEquals(listOf(InlineSpan("www.x.org", url = "http://www.x.org")), parse("www.x.org"))
        assertEquals(listOf(InlineSpan("https://x.org", bold = true, url = "https://x.org")), parse("**https://x.org**"))
        assertEquals(listOf(InlineSpan("https://x.org/a", url = "https://x.org/a")), parse("<https://x.org/a>"))
        assertEquals(listOf(InlineSpan("me@x.org", url = "mailto:me@x.org")), parse("<me@x.org>"))
        assertEquals(listOf(InlineSpan("a <b> c")), parse("a <b> c"))
        assertEquals(listOf(InlineSpan("xhttps://y.org")), parse("xhttps://y.org"))
    }

    @Test
    fun escapesEntitiesAndWhitespace() {
        assertEquals(listOf(InlineSpan("*not em* & <tag>")), parse("\\*not em\\* &amp; &lt;tag&gt;"))
        assertEquals(listOf(InlineSpan("é 😀 &bogus;")), parse("&#233; &#x1F600; &bogus;"))
        assertEquals(listOf(InlineSpan("line one\nline "), InlineSpan("two", italic = true)), parse("line one\nline *two*"))
        assertEquals(listOf(InlineSpan("C:\\path")), parse("C:\\path"))
    }

    @Test
    fun inlineImageShowsItsAltText() {
        assertEquals(listOf(InlineSpan("see "), InlineSpan("chart", italic = true)), parse("see ![*chart*](c.png)"))
        assertEquals(listOf(InlineSpan("logo", url = "https://x.org")), parse("[![logo](l.png)](https://x.org)"))
    }

    @Test
    fun plainText() {
        assertEquals("a b c link", InlineMarkdown.plainText("a **b** `c` [link](u)"))
    }
}
