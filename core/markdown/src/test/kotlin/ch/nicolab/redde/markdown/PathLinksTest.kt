package ch.nicolab.redde.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of the iOS `ReplyPathLinkTests`: a file a reply names only by its path opens from it (a
 * Reddit report, 2026-10-22: an agent said where it had left the sound files instead of sending
 * them). Where iOS read the result with `AttributedString(markdown:)`, these use [InlineMarkdown].
 */
class PathLinksTest {
    /** The paths made links, in order, as their links open them. */
    private fun linked(text: String): List<String> =
        Regex("""\]\((redde-file:[^)]*)\)""").findAll(PathLinks.linkingPaths(text))
            .mapNotNull { PathLinks.linkedPath(it.groupValues[1]) }.toList()

    @Test
    fun pathsAReplyNamesAreLinks() {
        assertEquals(listOf("/home/hermes/voices/af_heart.wav"), linked("I left it at /home/hermes/voices/af_heart.wav."))
        assertEquals(listOf("~/briefings/today.md", "./out/today.pdf"), linked("Saved to ~/briefings/today.md, and a copy in ./out/today.pdf"))
        assertEquals(listOf("samples/bm_lewis.wav"), linked("In the chat's folder: samples/bm_lewis.wav"))
        assertEquals(listOf("/tmp/report.pdf"), linked("**/tmp/report.pdf**"))
        assertEquals(listOf("/tmp/a.tar.gz"), linked("(/tmp/a.tar.gz)"))
        assertEquals(
            listOf("/home/hermes/My Voices/af heart.wav"),
            linked("Here: `/home/hermes/My Voices/af heart.wav`"),
            "in backticks a name can have spaces",
        )
    }

    @Test
    fun notWhatOnlyLooksLikeOne() {
        for (
            text in listOf(
                "https://example.com/a/b.png", "example.com/b.png", "www.example.com/b.pdf",
                "[the chart](/tmp/chart.png)", "![x](/tmp/x.png)", "<https://x.org/a.pdf>",
                "/home/hermes/run.py", "/etc/hosts", "report.md", "`cat /tmp/a.wav`", "`/tmp/a.wav --loud`",
                "/tmp/a.wav.bak", "/tmp/a.wav-old", "and/or", "a\\/tmp/b.wav",
            )
        ) {
            assertEquals(text, PathLinks.linkingPaths(text), text)
        }
    }

    @Test
    fun shownAsWrittenAndOpensWhatItNames() {
        val path = "/home/hermes/_drafts_/a&b.md"
        val spans = InlineMarkdown.parse(PathLinks.linkingPaths("Left at $path for you"))
        assertEquals("Left at $path for you", spans.joinToString("") { it.text }, "nothing in it read as emphasis or an entity")
        val url = spans.firstNotNullOfOrNull { it.url } ?: fail("no link")
        assertEquals(path, PathLinks.linkedPath(url))
        assertNull(PathLinks.linkedPath("https://example.com/a.png"))

        val code = InlineMarkdown.parse(PathLinks.linkingPaths("`~/My Voices/af heart.wav`"))
        assertEquals("~/My Voices/af heart.wav", code.joinToString("") { it.text })
        assertEquals("~/My Voices/af heart.wav", code.firstNotNullOfOrNull { it.url }?.let(PathLinks::linkedPath))
        assertTrue(code.single().code)
    }

    @Test
    fun inWhatIsReadNotInCode() {
        val blocks = MarkdownParser.parse("Saved /tmp/a.wav\n\n```\nplay /tmp/a.wav\n```\n\n- /tmp/b.pdf\n\n| file |\n|---|\n| /tmp/c.csv |", linkPaths = true)
        assertEquals(MarkdownBlock.Paragraph("Saved [/tmp/a.wav](redde-file:/tmp/a.wav)"), blocks.first())
        assertEquals("play /tmp/a.wav", assertIs<MarkdownBlock.Code>(blocks[1]).text)
        assertTrue(assertIs<MarkdownBlock.ListBlock>(blocks[2]).items.first().text.contains("redde-file:/tmp/b.pdf"))
        assertTrue(assertIs<MarkdownBlock.Table>(blocks.last()).rows.first().first().contains("redde-file:/tmp/c.csv"))
        assertEquals(listOf(MarkdownBlock.Paragraph("Saved /tmp/a.wav")), MarkdownParser.parse("Saved /tmp/a.wav", linkPaths = false), "not while a reply is still coming")
    }

    @Test
    fun nonAsciiPathsRoundTrip() {
        assertEquals(listOf("/tmp/café 😀/ü.md"), linked("`/tmp/café 😀/ü.md`"))
        assertNull(PathLinks.linkedPath("redde-file:/tmp/%E9.md"), "invalid UTF-8")
        assertNull(PathLinks.linkedPath("redde-file:/tmp/%zz.md"), "malformed escape")
    }
}
