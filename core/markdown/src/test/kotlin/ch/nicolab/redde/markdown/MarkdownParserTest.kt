package ch.nicolab.redde.markdown

import ch.nicolab.redde.markdown.MarkdownBlock.ColumnAlignment.CENTER
import ch.nicolab.redde.markdown.MarkdownBlock.ColumnAlignment.LEADING
import ch.nicolab.redde.markdown.MarkdownBlock.ColumnAlignment.TRAILING
import ch.nicolab.redde.markdown.MarkdownBlock.Code
import ch.nicolab.redde.markdown.MarkdownBlock.Heading
import ch.nicolab.redde.markdown.MarkdownBlock.Image
import ch.nicolab.redde.markdown.MarkdownBlock.ListBlock
import ch.nicolab.redde.markdown.MarkdownBlock.ListItem
import ch.nicolab.redde.markdown.MarkdownBlock.Math
import ch.nicolab.redde.markdown.MarkdownBlock.Paragraph
import ch.nicolab.redde.markdown.MarkdownBlock.Quote
import ch.nicolab.redde.markdown.MarkdownBlock.Rule
import ch.nicolab.redde.markdown.MarkdownBlock.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Port of the iOS `MarkdownParserTests`. */
class MarkdownParserTest {
    @Test
    fun parsesCommonBlocks() {
        val md = """
            # Title
            Some **bold** text
            continues here.

            - one
            - two
              more of two
            1. first
            2. second

            > quoted

            ```swift
            let x = 1
            ```

            | a | b |
            |---|---|
            | 1 | 2 |
            ---
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertEquals(Heading(1, "Title"), blocks[0])
        assertEquals(Paragraph("Some **bold** text continues here."), blocks[1])
        val items = assertIs<ListBlock>(blocks[2]).items
        assertEquals(listOf("one", "two more of two", "first", "second"), items.map { it.text })
        assertTrue(items[2].ordered && items[2].marker == "1.")
        assertEquals(Quote(listOf(Paragraph("quoted"))), blocks[3])
        assertEquals(Code("swift", "let x = 1"), blocks[4])
        assertEquals(Table(listOf("a", "b"), listOf(listOf("1", "2")), listOf(LEADING, LEADING)), blocks[5])
        assertEquals(Rule, blocks[6])
    }

    @Test
    fun unterminatedFenceStreams() {
        val blocks = MarkdownParser.parse("Here:\n```python\nprint(1)")
        assertEquals(listOf(Paragraph("Here:"), Code("python", "print(1)")), blocks)
    }

    @Test
    fun plainTextIsOneParagraph() {
        assertEquals(listOf(Paragraph("just words")), MarkdownParser.parse("just words"))
        assertTrue(MarkdownParser.parse("").isEmpty())
    }

    @Test
    fun taskListsImagesAlignmentsAndHardBreaks() {
        // Built line by line: the hard break's two trailing spaces must survive.
        val blocks = MarkdownParser.parse(
            listOf(
                "- [ ] open",
                "- [x] done",
                "",
                "![diagram](https://example.com/a.png)",
                "",
                "| Name | Qty | Price |",
                "|:-----|:---:|------:|",
                "| Tea  |  2  |  3.50 |",
                "",
                "line one  ",
                "line two",
                "",
                "> quote with",
                "> - a list",
            ).joinToString("\n"),
        )
        val items = assertIs<ListBlock>(blocks[0]).items
        assertTrue(items[0].checked == false && items[0].text == "open")
        assertTrue(items[1].checked == true && items[1].text == "done")
        assertEquals(Image("diagram", "https://example.com/a.png"), blocks[1])
        val table = assertIs<Table>(blocks[2])
        assertEquals(listOf(LEADING, CENTER, TRAILING), table.alignments)
        assertEquals(listOf(listOf("Tea", "2", "3.50")), table.rows)
        assertEquals(Paragraph("line one\nline two"), blocks[3])
        assertEquals(
            Quote(listOf(Paragraph("quote with"), ListBlock(listOf(ListItem(ordered = false, marker = "•", text = "a list", indent = 0))))),
            blocks[4],
        )
    }

    /**
     * The API server inlines a picture the agent sends mid-sentence ("Here it is ![image](…)"),
     * so an image inside a paragraph must split out and render, not show as raw markup.
     */
    @Test
    fun imageEmbeddedInAParagraph() {
        val blocks = MarkdownParser.parse("Here it is ![camera](data:image/jpeg;base64,/9j/4AAQ==) — fresh.")
        assertEquals(
            listOf(Paragraph("Here it is"), Image("camera", "data:image/jpeg;base64,/9j/4AAQ=="), Paragraph("— fresh.")),
            blocks,
        )
    }

    @Test
    fun twoImagesInOneParagraph() {
        val blocks = MarkdownParser.parse("![a](https://example.com/a.png) and ![b](https://example.com/b.png)")
        assertEquals(
            listOf(Image("a", "https://example.com/a.png"), Paragraph("and"), Image("b", "https://example.com/b.png")),
            blocks,
        )
    }

    /**
     * Wikipedia-style URLs carry balanced parentheses; the scanner must find the closing `)` of
     * the destination, not the first `)` inside it.
     */
    @Test
    fun imageUrlWithParenthesesDoesNotTruncate() {
        val url = "https://upload.example.org/wiki/Paris_(France).png"
        val blocks = MarkdownParser.parse("See ![map]($url) for the layout.")
        assertEquals(listOf(Paragraph("See"), Image("map", url), Paragraph("for the layout.")), blocks)

        val alone = MarkdownParser.parse("![map]($url)")
        assertEquals(listOf(Image("map", url)), alone)
    }

    /**
     * A command presented as a lone inline code span gets the copyable code window; inline code
     * inside a sentence stays part of the paragraph.
     */
    @Test
    fun loneInlineCodePromotesToACodeBlock() {
        val blocks = MarkdownParser.parse(
            "Run this:\n\n`ssh root@10.0.0.2 \"systemctl restart hermes\"`\n\nThen check the logs with `journalctl -u hermes`.",
        )
        assertEquals(
            listOf(
                Paragraph("Run this:"),
                Code(null, "ssh root@10.0.0.2 \"systemctl restart hermes\""),
                Paragraph("Then check the logs with `journalctl -u hermes`."),
            ),
            blocks,
        )
        assertEquals(listOf(Paragraph("`a` and `b`")), MarkdownParser.parse("`a` and `b`"))
    }

    @Test
    fun bangBracketWithoutAnImageStaysText() {
        assertEquals(listOf(Paragraph("array![0] is fine")), MarkdownParser.parse("array![0] is fine"))
        assertEquals(listOf(Paragraph("wow ![just brackets]")), MarkdownParser.parse("wow ![just brackets]"))
    }

    @Test
    fun setextHeadings() {
        val blocks = MarkdownParser.parse("Title\n=====\n\nSub\n---\n\ntext")
        assertEquals(listOf(Heading(1, "Title"), Heading(2, "Sub"), Paragraph("text")), blocks)
    }

    @Test
    fun displayMathBlocks() {
        val blocks = MarkdownParser.parse(
            "\$\$E = mc^2\$\$\n\n" +
                "\$\$\n\\int_0^1 x\\,dx\n= \\tfrac12\n\$\$\n\n" +
                "\\[ a^2 + b^2 = c^2 \\]\n\n" +
                "price is \$5 and \$6",
        )
        assertEquals(Math("E = mc^2"), blocks[0])
        assertEquals(Math("\\int_0^1 x\\,dx\n= \\tfrac12"), blocks[1])
        assertEquals(Math("a^2 + b^2 = c^2"), blocks[2])
        assertEquals(Paragraph("price is \$5 and \$6"), blocks[3])
    }

    // Beyond the iOS suite: behaviour the port must keep.

    @Test
    fun mapInlineLeavesCodeMathAndPicturesAlone() {
        val blocks = MarkdownParser.parse("# h\n\np\n\n- i\n\n> q\n\n| a |\n|---|\n| b |\n\n```\nc\n```\n\n\$\$m\$\$\n\n![x](y)")
        val mapped = blocks.map { it.mapInline(String::uppercase) }
        assertEquals(
            listOf(
                Heading(1, "H"), Paragraph("P"), ListBlock(listOf(ListItem(false, "•", "I", 0))), Quote(listOf(Paragraph("Q"))),
                Table(listOf("A"), listOf(listOf("B")), listOf(LEADING)), Code(null, "c"), Math("m"), Image("x", "y"),
            ),
            mapped,
        )
    }

    @Test
    fun crlfOrderedParenIndentAndBackslashBreak() {
        val blocks = MarkdownParser.parse("1) a\r\n    - b\r\n\r\nx\\\ny")
        assertEquals(
            listOf(
                ListBlock(listOf(ListItem(true, "1.", "a", 0), ListItem(false, "•", "b", 2))),
                Paragraph("x\ny"),
            ),
            blocks,
        )
    }

    @Test
    fun tableRowsPadAndCutToTheHeader() {
        val blocks = MarkdownParser.parse("| a | b |\n|---|--:|\n| 1 |\n| 1 | 2 | 3 |")
        assertEquals(listOf(Table(listOf("a", "b"), listOf(listOf("1", ""), listOf("1", "2")), listOf(LEADING, TRAILING))), blocks)
    }

    @Test
    fun linkedImageUnwrapsAndBrTagsBreak() {
        assertEquals(listOf(Image("a", "i.png")), MarkdownParser.parse("[![a](i.png \"t\")](https://x)"))
        assertEquals(listOf(Paragraph("a\nb")), MarkdownParser.parse("a<br>b"))
        assertEquals(listOf(Heading(6, "x")), MarkdownParser.parse("###### x"))
        assertEquals(listOf(Paragraph("####### x")), MarkdownParser.parse("####### x"))
    }
}
