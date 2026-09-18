package dev.ynagai.agui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.html.entities.Entities
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
     * Whether anything here is drawn: the parser's top level also holds `EOL` tokens for blank
     * lines, and a link reference definition is consulted rather than drawn.
     */
    val hasBlocks: Boolean get() = blocks.any {
        it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownElementTypes.LINK_DEFINITION
    }

    /**
     * Link reference definitions in this segment, label to destination, for `[text][label]` and
     * `[label]` links. A definition can sit inside a list item or a quote as well as at the top
     * level; labels are matched as CommonMark specifies (see [normalizeLabel]) and the first
     * definition of a label wins.
     */
    val linkDefinitions: Map<String, String> by lazy {
        buildMap {
            fun collect(node: ASTNode) {
                if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                    val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    if (label != null && destination != null) {
                        getOrPut(normalizeLabel(label.getTextInNode(source))) {
                            unescape(destination.getTextInNode(source).toString().trim('<', '>'))
                        }
                    }
                } else {
                    node.children.forEach(::collect)
                }
            }
            collect(root)
        }
    }
}

/** A reference label as CommonMark matches it: brackets off, internal whitespace collapsed, case-folded. */
internal fun normalizeLabel(label: CharSequence): String =
    label.toString().trim('[', ']').trim().split(whitespaceRun).joinToString(" ").lowercase()

private val whitespaceRun = Regex("""\s+""")

/**
 * Backslash escapes and entity references resolved, as CommonMark does for text and destinations.
 *
 * Not the parser's own `EntityConverter`: that one produces HTML, so it turns a bare `&` into
 * `&amp;` and a quote into `&quot;`, which is the opposite of what a string bound for the screen
 * needs. Its entity table is what is borrowed, keyed by the whole `&name;`. An unknown name stays
 * as written.
 */
internal fun unescape(text: CharSequence): String {
    if (text.indexOf('\\') < 0 && text.indexOf('&') < 0) return text.toString()
    return escapeOrEntity.replace(text) { match ->
        val escaped = match.groups[1]?.value
        val decimal = match.groups[2]?.value
        val hex = match.groups[3]?.value
        val named = match.groups[4]?.value
        when {
            escaped != null -> escaped
            decimal != null -> codePointOrReplacement(decimal.toIntOrNull() ?: 0)
            hex != null -> codePointOrReplacement(hex.toIntOrNull(16) ?: 0)
            named != null -> Entities.map[match.value]?.let { codePointOrReplacement(it) } ?: match.value
            else -> match.value
        }
    }
}

private val escapeOrEntity = Regex("""\\([!-/:-@\[-`{-~])|&#(\d{1,7});|&#[xX]([0-9a-fA-F]{1,6});|&([A-Za-z][A-Za-z0-9]{1,31});""")

private fun codePointOrReplacement(codePoint: Int): String = when {
    codePoint == 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF -> "\uFFFD"
    codePoint < 0x10000 -> codePoint.toChar().toString()
    else -> {
        val offset = codePoint - 0x10000
        charArrayOf(Char(0xD800 + (offset shr 10)), Char(0xDC00 + (offset and 0x3FF))).concatToString()
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
 * A blank line is not always a boundary, and the exceptions are what [settlePoint] checks:
 *
 * - inside an open code fence, a blank line is content, not a break;
 * - between two list items, a blank line makes the list loose rather than ending it, and a blank
 *   line before an indented line is a continuation of whatever block the indentation belongs to;
 * - the last line of the run is still arriving, so a bare number there is not yet known not to
 *   be an ordered-list marker.
 *
 * All are recognised by looking at the line *after* the blank: a settle point is only taken
 * where that line starts at column zero and is not itself a list item. Lists and fences are the
 * common cases in agent output. What is not covered: a fence opened on a list-marker line
 * (`- ```` ), an HTML block that spans a blank line (`<pre>`, `<!-- -->`), and a link reference
 * definition in one segment referenced from another, which resolves only against the
 * definitions of the segment the link is in. See the module's tests for what that costs.
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
        private val bareNumber = Regex("""^\d{1,9}$""")
        private val fence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

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
                val fenceMatch = fence.find(line)?.groupValues
                if (inFence != null) {
                    // A closing fence carries nothing after its marker; one with an info string
                    // is content.
                    if (fenceMatch != null && fenceMatch[1][0] == inFence[0] && fenceMatch[1].length >= inFence.length && fenceMatch[2].isBlank()) {
                        inFence = null
                    }
                    previousBlank = false
                } else if (fenceMatch != null) {
                    // A fence opening after a blank line is a block start like any other, unless
                    // it is indented into the block above; what cannot be settled is anything
                    // *inside* it, which the branch above handles.
                    if (previousBlank && !line[0].isWhitespace()) settle = lineStart
                    inFence = fenceMatch[1]
                    previousBlank = false
                } else if (line.isBlank()) {
                    previousBlank = true
                } else {
                    // A line that starts a block of its own, after a blank line, is where the
                    // text before it can be settled. Indented lines continue whatever came
                    // before; list items after a blank line continue the list; a bare number on
                    // an unfinished last line may still become one.
                    val unfinished = lineEnd == open.length
                    if (previousBlank && !line[0].isWhitespace() && !listMarker.containsMatchIn(line) &&
                        !(unfinished && bareNumber.matches(line))
                    ) {
                        settle = lineStart
                    }
                    previousBlank = false
                }
                lineStart = lineEnd + 1
            }
            return settle
        }
    }
}
