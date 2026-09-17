package dev.ynagai.agui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Turns the inline children of a block into one [AnnotatedString].
 *
 * The parser keeps every delimiter as a token -- the asterisks of `**bold**` are `EMPH` tokens
 * inside a `STRONG` element -- so drawing is a matter of walking the children, dropping the
 * delimiters and opening a span for each element that means something. What is not dropped is
 * text: an element this walk does not recognise contributes its characters unchanged, so an
 * unhandled construct degrades to the literal Markdown rather than to nothing.
 */
internal class InlineBuilder(
    private val segment: MarkdownSegment,
    private val typography: MarkdownTypography,
    private val colors: MarkdownColors,
) {
    private val inlineCode: SpanStyle = typography.inlineCode.toSpanStyle().copy(background = colors.inlineCodeBackground)

    fun build(node: ASTNode): AnnotatedString = build(node.children)

    fun build(nodes: List<ASTNode>): AnnotatedString {
        atLineStart = true
        afterHardBreak = false
        return buildAnnotatedString { nodes.forEach { append(it) } }.trim()
    }

    /**
     * Appends [node]'s children, dropping the ones of type [delimiter].
     *
     * Only inside the element that owns them: an asterisk in running prose is also an `EMPH`
     * token, and it is a character the reader is meant to see. What makes it syntax is the
     * `STRONG` or `EMPH` element around it.
     */
    private fun AnnotatedString.Builder.appendChildren(node: ASTNode, delimiter: IElementType? = null) {
        node.children.forEach { if (it.type != delimiter) append(it) }
    }

    /**
     * Whether the last thing appended ended a line. What follows a line end inside a paragraph is
     * the next line's leading whitespace and, in a lazily continued block quote, its `>` -- both
     * of which are layout in the source and nothing on screen.
     */
    private var atLineStart: Boolean = true

    /** Whether the line was ended by a hard break, whose own `EOL` token follows it and is not a space. */
    private var afterHardBreak: Boolean = false

    private fun AnnotatedString.Builder.append(node: ASTNode) {
        if (atLineStart) {
            when (node.type) {
                MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE -> return
                MarkdownTokenTypes.EOL -> if (afterHardBreak) {
                    afterHardBreak = false
                    return
                }
                else -> Unit
            }
        }
        atLineStart = false
        afterHardBreak = false
        when (node.type) {
            MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(node, MarkdownTokenTypes.EMPH)
            }
            MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendChildren(node, MarkdownTokenTypes.EMPH)
            }
            GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendChildren(node, GFMTokenTypes.TILDE)
            }
            MarkdownElementTypes.CODE_SPAN -> withStyle(inlineCode) {
                appendChildren(node, MarkdownTokenTypes.BACKTICK)
            }

            MarkdownElementTypes.INLINE_LINK -> link(
                text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT },
                destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.text()?.trim('<', '>'),
            )

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            -> {
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: label
                val destination = label?.text()?.trim('[', ']')?.lowercase()?.let(segment.linkDefinitions::get)
                // A reference with no definition is not a link; CommonMark leaves it as the
                // bracketed characters, and so does this.
                if (destination == null) append(node.text()) else link(text = text, destination = destination)
            }

            // `<https://...>`: the angle brackets are tokens, the URL between them is the text.
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK }?.text()
                if (url != null) withLink(LinkAnnotation.Url(url, typography.textLink)) { append(url) } else appendChildren(node)
            }

            // A bare URL under GFM. One token, its text is the destination.
            GFMTokenTypes.GFM_AUTOLINK -> {
                val url = node.text()
                withLink(LinkAnnotation.Url(url, typography.textLink)) { append(url) }
            }

            // Nothing is fetched. An image is drawn as its alt text, which is what a screen reader
            // would say and what an agent meant the reader to know about it.
            MarkdownElementTypes.IMAGE -> {
                val inner = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                    ?: node.children.firstOrNull { it.type == MarkdownElementTypes.FULL_REFERENCE_LINK }
                    ?: node.children.firstOrNull { it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK }
                val alt = inner?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                if (alt != null) link(text = alt, destination = null) else appendChildren(node)
            }

            // A line break inside a paragraph is a space. Two trailing spaces or a backslash
            // before it is a real break.
            MarkdownTokenTypes.EOL -> {
                append(' ')
                atLineStart = true
            }
            MarkdownTokenTypes.HARD_LINE_BREAK -> {
                append('\n')
                atLineStart = true
                afterHardBreak = true
            }

            // Anything else: a leaf contributes its characters, an element its children's.
            else -> if (node.children.isEmpty()) append(node.text()) else appendChildren(node)
        }
    }

    /**
     * [text] is a `LINK_TEXT` (or a `LINK_LABEL` standing in for one): its first and last children
     * are the brackets, and everything between is inline content.
     */
    private fun AnnotatedString.Builder.link(text: ASTNode?, destination: String?) {
        if (text == null) return
        // A `LINK_LABEL` is a leaf: the label's characters, brackets included.
        if (text.children.isEmpty()) {
            val label = text.text().trim('[', ']')
            if (destination == null) append(label) else withLink(LinkAnnotation.Url(destination, typography.textLink)) { append(label) }
            return
        }
        val inner = text.children.let { children ->
            if (children.size >= 2 &&
                children.first().type == MarkdownTokenTypes.LBRACKET &&
                children.last().type == MarkdownTokenTypes.RBRACKET
            ) {
                children.subList(1, children.size - 1)
            } else {
                children
            }
        }
        if (destination == null) {
            inner.forEach { append(it) }
        } else {
            withLink(LinkAnnotation.Url(destination, typography.textLink)) { inner.forEach { append(it) } }
        }
    }

    private fun ASTNode.text(): String = getTextInNode(segment.source).toString()
}

/** Whitespace off both ends, spans kept: a table cell is ` a `, a heading's content ` Title`. */
internal fun AnnotatedString.trim(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return if (start == 0 && end == length) this else subSequence(start, end)
}
