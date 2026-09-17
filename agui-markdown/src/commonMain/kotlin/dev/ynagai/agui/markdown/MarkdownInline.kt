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
        inLink = false
        return buildAnnotatedString { appendAll(nodes) }.trim()
    }

    /**
     * Appends [node]'s children, dropping the ones of type [delimiter].
     *
     * Only inside the element that owns them: an asterisk in running prose is also an `EMPH`
     * token, and it is a character the reader is meant to see. What makes it syntax is the
     * `STRONG` or `EMPH` element around it.
     */
    private fun AnnotatedString.Builder.appendChildren(node: ASTNode, delimiter: IElementType? = null) {
        appendAll(if (delimiter == null) node.children else node.children.filter { it.type != delimiter })
    }

    /**
     * Appends [nodes] in order. The one construct that spans siblings is an email autolink,
     * `<name@host>`, which the parser leaves as three tokens rather than wrapping in an element.
     */
    private fun AnnotatedString.Builder.appendAll(nodes: List<ASTNode>) {
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            if (node.type == MarkdownTokenTypes.LT &&
                nodes.getOrNull(index + 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK &&
                nodes.getOrNull(index + 2)?.type == MarkdownTokenTypes.GT
            ) {
                val email = nodes[index + 1].text()
                atLineStart = false
                afterHardBreak = false
                link("mailto:$email") { append(email) }
                index += 3
            } else {
                append(node)
                index++
            }
        }
    }

    /**
     * Whether the last thing appended ended a line. What follows a line end inside a paragraph is
     * the next line's leading whitespace and, in a lazily continued block quote, its `>` -- both
     * of which are layout in the source and nothing on screen.
     */
    private var atLineStart: Boolean = true

    /** Whether the line was ended by a hard break, whose own `EOL` token follows it and is not a space. */
    private var afterHardBreak: Boolean = false

    /**
     * Whether a link is open. A URL inside a link's text (or an image's alt text) is that text,
     * not a second link over the same characters.
     */
    private var inLink: Boolean = false

    private inline fun AnnotatedString.Builder.link(url: String, block: AnnotatedString.Builder.() -> Unit) {
        if (inLink) {
            block()
        } else {
            inLink = true
            withLink(LinkAnnotation.Url(url, typography.textLink)) { block() }
            inLink = false
        }
    }

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
            // The opening and closing backtick runs go; everything between them is code as
            // written, including further backticks and the whitespace prose would fold -- except
            // the one space on each side that lets a span begin or end with a backtick.
            MarkdownElementTypes.CODE_SPAN -> withStyle(inlineCode) {
                val children = node.children
                val code = buildString {
                    children.forEachIndexed { index, child ->
                        if (index == 0 || index == children.lastIndex) return@forEachIndexed
                        if (child.type == MarkdownTokenTypes.EOL) append(' ') else append(child.text())
                    }
                }
                val padded = code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.any { it != ' ' }
                append(if (padded) code.substring(1, code.length - 1) else code)
            }

            MarkdownElementTypes.INLINE_LINK -> link(
                text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT },
                destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.text()?.trim('<', '>')?.let(::unescape),
            )

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            -> {
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: label
                val destination = label?.text()?.let(::normalizeLabel)?.let(segment.linkDefinitions::get)
                // A reference with no definition is not a link; CommonMark leaves it as the
                // bracketed characters, with whatever inline syntax is inside them, and so does this.
                if (destination == null) appendChildren(node) else link(text = text, destination = destination)
            }

            // `<https://...>`: the angle brackets are tokens, the URL between them is the text.
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK }?.text()
                if (url != null) link(url) { append(url) } else appendChildren(node)
            }

            // A bare URL under GFM. One token, its text is the destination -- with the scheme GFM
            // implies for the `www.` form, which a `UriHandler` cannot open without.
            GFMTokenTypes.GFM_AUTOLINK -> {
                val text = node.text()
                val url = if (text.startsWith("www.", ignoreCase = true)) "http://$text" else text
                link(url) { append(text) }
            }

            // Nothing is fetched. An image is drawn as its alt text, which is what a screen reader
            // would say and what an agent meant the reader to know about it. A reference image
            // with no definition is not an image, and stays as written like an undefined link.
            MarkdownElementTypes.IMAGE -> {
                val inner = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                    ?: node.children.firstOrNull { it.type == MarkdownElementTypes.FULL_REFERENCE_LINK }
                    ?: node.children.firstOrNull { it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK }
                val label = inner?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val defined = label == null || normalizeLabel(label.text()) in segment.linkDefinitions
                val alt = inner?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: label
                if (alt != null && defined) link(text = alt, destination = null) else appendChildren(node)
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

            // Prose, with its backslash escapes and entity references resolved.
            MarkdownTokenTypes.TEXT -> append(unescape(node.text()))

            // Anything else: a leaf contributes its characters, an element its children's.
            else -> if (node.children.isEmpty()) append(node.text()) else appendChildren(node)
        }
    }

    /**
     * [text] is a `LINK_TEXT` (or a `LINK_LABEL` standing in for one): its first and last children
     * are the brackets, and everything between is inline content. A null [destination] draws the
     * text without a link -- and without any link inside it either, which is the image case.
     */
    private fun AnnotatedString.Builder.link(text: ASTNode?, destination: String?) {
        if (text == null) return
        // A `LINK_LABEL` is a leaf: the label's characters, brackets included.
        if (text.children.isEmpty()) {
            val label = text.text().trim('[', ']')
            if (destination == null) append(label) else link(destination) { append(label) }
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
            val wasInLink = inLink
            inLink = true
            appendAll(inner)
            inLink = wasInLink
        } else {
            link(destination) { appendAll(inner) }
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
