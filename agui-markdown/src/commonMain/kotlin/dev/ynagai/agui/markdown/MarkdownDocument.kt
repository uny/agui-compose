package dev.ynagai.agui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * One parsed stretch of a run: the source it was parsed from and the top-level blocks in it.
 *
 * The parser's nodes are offsets into the string they were built from, so a segment carries that
 * string with them. A finished message is a single segment; a streaming one is a list, and the
 * list is what lets the settled part of a run keep its tree while only the tail is re-parsed.
 */
internal class MarkdownSegment(val source: String, val root: ASTNode) {
    val blocks: List<ASTNode> get() = root.children

    /**
     * Link reference definitions in this segment, label to destination, for `[text][label]` and
     * `[label]` links. Labels are matched case-insensitively, as CommonMark specifies.
     */
    val linkDefinitions: Map<String, String> by lazy {
        buildMap {
            root.children.filter { it.type == MarkdownElementTypes.LINK_DEFINITION }.forEach { node ->
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                if (label != null && destination != null) {
                    put(
                        label.getTextInNode(source).toString().trim('[', ']').lowercase(),
                        destination.getTextInNode(source).toString().trim('<', '>'),
                    )
                }
            }
        }
    }
}

internal fun MarkdownFlavourDescriptor.parse(text: String): MarkdownSegment {
    val parser = MarkdownParser(this, assertionsEnabled = false, cancellationToken = CancellationToken.NonCancellable)
    return MarkdownSegment(text, parser.buildMarkdownTreeFromString(text as CharSequence))
}

/**
 * A run that is still arriving, parsed in settled pieces.
 *
 * intellij-markdown is not incremental: it parses a string from the top every time. What bounds the
 * cost here is that an agent's prose settles as it goes -- a paragraph that has been followed by a
 * blank line and a fresh block is not going to change -- so everything before the last such point
 * is parsed once and kept, and only the open tail is re-parsed per token.
 *
 * A blank line is not always a boundary, and the two exceptions are what [settlePoint] checks:
 *
 * - inside an open code fence, a blank line is content, not a break;
 * - between two list items, a blank line makes the list loose rather than ending it, and a blank
 *   line before an indented line is a continuation of whatever block the indentation belongs to.
 *
 * Both are recognised by looking at the line *after* the blank: a settle point is only taken
 * where that line starts at column zero and is not itself a list item. Lists and fences are the
 * common cases in agent output; what is not covered is a link reference definition that a later
 * segment needs, which resolves against the definitions of the segment the link is in and so
 * fails to resolve if the definition arrives after a settle point. See the module's tests for
 * what that costs.
 *
 * Correctness does not depend on any of this: a run that is not an extension of what has been
 * parsed is parsed again from the top, and a finished run is handed to [parse] whole.
 */
internal class StreamingMarkdownDocument(private val flavour: MarkdownFlavourDescriptor) {
    private var settled: MutableList<MarkdownSegment> = mutableListOf()
    private var settledLength: Int = 0
    private var tail: MarkdownSegment = flavour.parse("")
    private var fed: String = ""

    /** How many times the parser has run on this document. Measured by the tests, not read by the renderer. */
    var parses: Int = 0
        private set

    val segments: List<MarkdownSegment> get() = settled + tail

    /**
     * Brings the document up to [text], which is the whole run so far rather than a delta.
     *
     * Returns the segments to draw. The settled prefix is reused when [text] extends [fed];
     * otherwise everything starts over, which is the regenerated-message case.
     */
    fun update(text: String): List<MarkdownSegment> {
        if (text == fed) return segments
        if (!text.startsWith(fed)) {
            settled = mutableListOf()
            settledLength = 0
        }
        fed = text

        val open = text.substring(settledLength)
        val settle = settlePoint(open)
        if (settle > 0) {
            settled += parseCounted(open.substring(0, settle))
            settledLength += settle
        }
        tail = parseCounted(text.substring(settledLength))
        return segments
    }

    private fun parseCounted(source: String): MarkdownSegment {
        parses++
        return flavour.parse(source)
    }

    private companion object {
        private val listMarker = Regex("""^(?:[-*+]|\d{1,9}[.)])(?:\s|$)""")
        private val fence = Regex("""^ {0,3}(`{3,}|~{3,})""")

        /**
         * The offset in [open] up to which the text can be settled, or 0 if none of it can.
         *
         * Walks the lines once, tracking fence state, and remembers the last blank line that is
         * followed by a line a new block can start on. The returned offset is the start of that
         * following line, so the blank line itself stays with the settled segment.
         */
        fun settlePoint(open: String): Int {
            var settle = 0
            var lineStart = 0
            var inFence: String? = null
            var previousBlank = false
            while (lineStart < open.length) {
                val lineEnd = open.indexOf('\n', lineStart).let { if (it < 0) open.length else it }
                val line = open.substring(lineStart, lineEnd)
                val fenceMatch = fence.find(line)?.groupValues?.get(1)
                if (inFence != null) {
                    if (fenceMatch != null && fenceMatch[0] == inFence[0] && fenceMatch.length >= inFence.length) {
                        inFence = null
                    }
                    previousBlank = false
                } else if (fenceMatch != null) {
                    // A fence opening after a blank line is a block start like any other; what
                    // cannot be settled is anything *inside* it, which the branch above handles.
                    if (previousBlank) settle = lineStart
                    inFence = fenceMatch
                    previousBlank = false
                } else if (line.isBlank()) {
                    previousBlank = true
                } else {
                    // A line that starts a block of its own, after a blank line, is where the
                    // text before it can be settled. Indented lines continue whatever came
                    // before; list items after a blank line continue the list.
                    if (previousBlank && !line[0].isWhitespace() && !listMarker.containsMatchIn(line)) {
                        settle = lineStart
                    }
                    previousBlank = false
                }
                lineStart = lineEnd + 1
            }
            // A settle point inside a fence that never closed is not one: the fence started after
            // it, so it is fine -- what would not be fine is settling *into* an open fence, and
            // the loop above never records a point while `inFence` is set.
            return settle
        }
    }
}
