package dev.ynagai.agui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Draws one parsed segment of a run as a column of blocks.
 *
 * The parser's top-level children are the blocks, interleaved with `EOL` tokens for the blank
 * lines between them; the tokens are skipped and the spacing between blocks is the column's own,
 * which is what keeps two paragraphs separated by three blank lines from drawing three gaps.
 */
@Composable
internal fun MarkdownBlocks(
    segment: MarkdownSegment,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
) {
    val inline = remember(segment, colors, typography) { InlineBuilder(segment, typography, colors) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BlockSpacing)) {
        segment.blocks.forEach { node -> MarkdownBlock(node, segment, inline, colors, typography) }
    }
}

private val BlockSpacing = 8.dp
private val QuoteBar = 3.dp
private val CellPadding = 6.dp

@Composable
private fun MarkdownBlock(
    node: ASTNode,
    segment: MarkdownSegment,
    inline: InlineBuilder,
    colors: MarkdownColors,
    typography: MarkdownTypography,
) {
    when (node.type) {
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE, MarkdownElementTypes.LINK_DEFINITION -> Unit

        MarkdownElementTypes.PARAGRAPH -> Prose(inline.build(node), typography.text, colors)

        MarkdownElementTypes.ATX_1 -> Heading(node, inline, typography.h1, colors)
        MarkdownElementTypes.ATX_2 -> Heading(node, inline, typography.h2, colors)
        MarkdownElementTypes.ATX_3 -> Heading(node, inline, typography.h3, colors)
        MarkdownElementTypes.ATX_4 -> Heading(node, inline, typography.h4, colors)
        MarkdownElementTypes.ATX_5 -> Heading(node, inline, typography.h5, colors)
        MarkdownElementTypes.ATX_6 -> Heading(node, inline, typography.h6, colors)
        MarkdownElementTypes.SETEXT_1 -> Heading(node, inline, typography.h1, colors)
        MarkdownElementTypes.SETEXT_2 -> Heading(node, inline, typography.h2, colors)

        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST ->
            ListBlock(node, segment, inline, colors, typography)

        MarkdownElementTypes.BLOCK_QUOTE -> Quote(node, segment, inline, colors, typography, accent = null, title = null)
        GFMElementTypes.ALERT -> Alert(node, segment, inline, colors, typography)

        MarkdownElementTypes.CODE_FENCE -> CodeBlock(fenceContent(node, segment), colors, typography)
        MarkdownElementTypes.CODE_BLOCK -> CodeBlock(indentedContent(node, segment), colors, typography)

        MarkdownTokenTypes.HORIZONTAL_RULE -> Rule(colors)

        GFMElementTypes.TABLE -> Table(node, segment, inline, colors, typography)

        // Raw HTML is not interpreted; an agent that writes it gets it back as text, which is at
        // least honest about what arrived.
        MarkdownElementTypes.HTML_BLOCK -> Prose(AnnotatedString(node.getTextInNode(segment.source).toString()), typography.code, colors)

        else -> Prose(inline.build(node), typography.text, colors)
    }
}

@Composable
private fun Prose(text: AnnotatedString, style: TextStyle, colors: MarkdownColors, modifier: Modifier = Modifier) {
    BasicText(text = text, style = style.copy(color = colors.text), modifier = modifier)
}

/**
 * An ATX heading is `#` tokens, whitespace, then an `ATX_CONTENT` holding the inline content; a
 * setext one is `SETEXT_CONTENT`, an `EOL` and the underline token. Either way the content node
 * is the one to draw, and its leading space is the separator, not text.
 */
@Composable
private fun Heading(node: ASTNode, inline: InlineBuilder, style: TextStyle, colors: MarkdownColors) {
    val content = node.children.firstOrNull {
        it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
    }
    val text = if (content != null) inline.build(content) else inline.build(node)
    Prose(text, style, colors)
}

/**
 * A list item is its marker token followed by blocks. The marker is drawn as it was written for
 * an ordered list, so a list that starts at 3 starts at 3, and as a bullet for an unordered one,
 * whatever character the agent used. A GFM task box (`[ ]` or `[x]`) is a token of its own
 * after the marker, and is drawn as written too, in front of the item's first block.
 */
@Composable
private fun ListBlock(
    node: ASTNode,
    segment: MarkdownSegment,
    inline: InlineBuilder,
    colors: MarkdownColors,
    typography: MarkdownTypography,
) {
    val ordered = node.type == MarkdownElementTypes.ORDERED_LIST
    Column(verticalArrangement = Arrangement.spacedBy(BlockSpacing / 2)) {
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { item ->
            val marker = item.children.firstOrNull {
                it.type == MarkdownTokenTypes.LIST_NUMBER || it.type == MarkdownTokenTypes.LIST_BULLET
            }
            val checkBox = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            val label = buildString {
                append(if (ordered) marker?.getTextInNode(segment.source)?.trim() else "•")
                if (checkBox != null) append(' ').append(checkBox.getTextInNode(segment.source).trim())
            }
            Row {
                Prose(AnnotatedString(label), typography.text, colors, Modifier.padding(end = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(BlockSpacing / 2)) {
                    item.children.forEach { child ->
                        if (child !== marker && child !== checkBox) MarkdownBlock(child, segment, inline, colors, typography)
                    }
                }
            }
        }
    }
}

/**
 * A block quote's children are the `>` tokens and the blocks between them. The bar down the left
 * is drawn rather than laid out, so it costs nothing in measurement and follows the height of
 * whatever the quote contains.
 */
@Composable
private fun Quote(
    node: ASTNode,
    segment: MarkdownSegment,
    inline: InlineBuilder,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    accent: Color?,
    title: String?,
) {
    val bar = accent ?: colors.dividerColor
    Column(
        modifier = Modifier
            .drawBehind {
                drawLine(bar, Offset(QuoteBar.toPx() / 2, 0f), Offset(QuoteBar.toPx() / 2, size.height), QuoteBar.toPx())
            }
            .padding(start = QuoteBar + 8.dp),
        verticalArrangement = Arrangement.spacedBy(BlockSpacing / 2),
    ) {
        if (title != null && accent != null) {
            BasicText(text = title, style = typography.text.copy(color = accent, fontWeight = FontWeight.Bold))
        }
        node.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.BLOCK_QUOTE, GFMTokenTypes.ALERT_TITLE -> Unit
                MarkdownElementTypes.PARAGRAPH -> Prose(inline.build(child), if (accent == null) typography.quote else typography.text, colors)
                else -> MarkdownBlock(child, segment, inline, colors, typography)
            }
        }
    }
}

/** `> [!NOTE]` and its four siblings: a quote whose first line names the accent to draw it in. */
@Composable
private fun Alert(
    node: ASTNode,
    segment: MarkdownSegment,
    inline: InlineBuilder,
    colors: MarkdownColors,
    typography: MarkdownTypography,
) {
    val kind = node.children.firstOrNull { it.type == GFMTokenTypes.ALERT_TITLE }
        ?.getTextInNode(segment.source)?.toString()?.trim()?.removePrefix("[!")?.removeSuffix("]")?.uppercase()
    val accent = when (kind) {
        "NOTE" -> colors.alert.note
        "TIP" -> colors.alert.tip
        "IMPORTANT" -> colors.alert.important
        "WARNING" -> colors.alert.warning
        "CAUTION" -> colors.alert.caution
        else -> null
    }
    val title = kind?.lowercase()?.replaceFirstChar { it.uppercase() }
    Quote(node, segment, inline, colors, typography, accent = accent, title = title)
}

/**
 * A fence's content is the `CODE_FENCE_CONTENT` tokens between its start and end, one per line,
 * with `EOL` tokens between them. The first `EOL` ends the opening line (with its language, if
 * any), and everything after the last content token is the closing line.
 */
private fun fenceContent(node: ASTNode, segment: MarkdownSegment): String {
    val lines = mutableListOf<String>()
    var line = StringBuilder()
    var openingLine = true
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_END -> break
            MarkdownTokenTypes.EOL -> if (openingLine) openingLine = false else {
                lines += line.toString()
                line = StringBuilder()
            }
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> line.append(child.getTextInNode(segment.source))
            else -> Unit
        }
    }
    if (line.isNotEmpty()) lines += line.toString()
    return lines.joinToString("\n")
}

/**
 * An indented block is `CODE_LINE` tokens with `EOL`s between them, indentation included, so four
 * columns (or the one tab that stands for them) come off each. A blank line inside the block is
 * an `EOL` with no `CODE_LINE` before it, which is why the lines are counted off the `EOL`s.
 */
private fun indentedContent(node: ASTNode, segment: MarkdownSegment): String {
    val lines = mutableListOf<String>()
    var line: String? = null
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.EOL -> {
                lines += line.orEmpty()
                line = null
            }
            MarkdownTokenTypes.CODE_LINE -> {
                val text = child.getTextInNode(segment.source)
                var strip = 0
                if (text.startsWith("\t")) strip = 1 else while (strip < text.length && strip < 4 && text[strip] == ' ') strip++
                line = text.subSequence(strip, text.length).toString()
            }
            else -> Unit
        }
    }
    if (line != null) lines += line
    return lines.joinToString("\n")
}

@Composable
private fun CodeBlock(content: String, colors: MarkdownColors, typography: MarkdownTypography) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.codeBackground)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        BasicText(text = content, style = typography.code.copy(color = colors.text))
    }
}

@Composable
private fun Rule(colors: MarkdownColors) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.dividerColor))
}

/**
 * A GFM table is a `HEADER` row, a `TABLE_SEPARATOR` line, and `ROW`s; each row is `CELL`s with
 * `TABLE_SEPARATOR` pipes between them. Alignment is read from the separator line, which is the
 * only place the syntax states it. The header sets the column count: a short row is padded with
 * empty cells so its cells keep their columns (the parser already drops a long row's excess).
 */
@Composable
private fun Table(
    node: ASTNode,
    segment: MarkdownSegment,
    inline: InlineBuilder,
    colors: MarkdownColors,
    typography: MarkdownTypography,
) {
    val alignments = node.children
        .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        ?.getTextInNode(segment.source)
        ?.split('|')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { spec ->
            when {
                spec.startsWith(':') && spec.endsWith(':') -> TextAlign.Center
                spec.endsWith(':') -> TextAlign.End
                else -> TextAlign.Start
            }
        }
        .orEmpty()
    val rows = node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    val columns = rows.firstOrNull()?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(colors.dividerColor, style = Stroke(1.dp.toPx())) },
    ) {
        rows.forEachIndexed { index, row ->
            val header = row.type == GFMElementTypes.HEADER
            val style = if (header) typography.table.copy(fontWeight = FontWeight.Bold) else typography.table
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (header) Modifier.background(colors.tableBackground) else Modifier)
                    .then(
                        if (index < rows.lastIndex) {
                            Modifier.drawBehind {
                                drawLine(colors.dividerColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val cells = row.children.filter { it.type == GFMTokenTypes.CELL }
                repeat(maxOf(columns, cells.size)) { column ->
                    BasicText(
                        text = cells.getOrNull(column)?.let { inline.build(it.children) } ?: AnnotatedString(""),
                        style = style.copy(color = colors.text, textAlign = alignments.getOrElse(column) { TextAlign.Start }),
                        modifier = Modifier.weight(1f).padding(CellPadding),
                    )
                }
            }
        }
    }
}
