package ch.nicolab.redde.markdown

/**
 * The subset of Markdown that chat replies actually use, parsed into blocks by [MarkdownParser].
 *
 * Inline styling (bold, italic, code spans, links) stays in the block's text as Markdown source;
 * [InlineMarkdown] turns it into styled runs at render time.
 */
sealed interface MarkdownBlock {
    /** An ATX (`# Title`) or setext (`Title` underlined with `===` / `---`) heading, level 1–6. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** Running text, its lines joined with spaces except after a hard break (kept as `\n`). */
    data class Paragraph(val text: String) : MarkdownBlock

    /** A run of list items, bulleted and numbered alike, each carrying its own indent. */
    data class ListBlock(val items: List<ListItem>) : MarkdownBlock

    /** A fenced code block; [language] is the fence's info string, or null when it names none. */
    data class Code(val language: String?, val text: String) : MarkdownBlock

    /** Quotes hold any blocks: paragraphs, lists, code, nested quotes. */
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** A thematic break (`---`, `***`, `___`). */
    data object Rule : MarkdownBlock

    /** A pipe table. Every row is padded or cut to the header's width. */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<ColumnAlignment>,
    ) : MarkdownBlock

    /** A paragraph that is only an image: `![alt](url)`. */
    data class Image(val alt: String, val url: String) : MarkdownBlock

    /** Display math: `$$ … $$` or `\[ … \]`, LaTeX source without the delimiters. */
    data class Math(val source: String) : MarkdownBlock

    /** How a table column's cells line up, from its divider cell (`:--`, `:-:`, `--:`). */
    enum class ColumnAlignment { LEADING, CENTER, TRAILING }

    /**
     * One list item.
     *
     * @property marker `"•"` for a bullet, or the number with a dot (`"1."`) for an ordered item.
     * @property indent Leading spaces divided by two.
     * @property checked Task list state: null for a plain item, true/false for `[x]` / `[ ]`.
     */
    data class ListItem(
        val ordered: Boolean,
        val marker: String,
        val text: String,
        val indent: Int,
        val checked: Boolean? = null,
    )

    /**
     * The block with [transform] applied to each piece of inline text in it; code, math and
     * pictures are left as they are.
     */
    fun mapInline(transform: (String) -> String): MarkdownBlock = when (this) {
        is Heading -> copy(text = transform(text))
        is Paragraph -> copy(text = transform(text))
        is ListBlock -> copy(items = items.map { it.copy(text = transform(it.text)) })
        is Quote -> copy(blocks = blocks.map { it.mapInline(transform) })
        is Table -> copy(header = header.map(transform), rows = rows.map { row -> row.map(transform) })
        is Code, Rule, is Image, is Math -> this
    }
}
