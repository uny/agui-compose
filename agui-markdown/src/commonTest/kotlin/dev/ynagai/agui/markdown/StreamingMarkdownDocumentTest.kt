package dev.ynagai.agui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settled-prefix scheme, asserted on where it settles and what that costs.
 *
 * Correctness of what is drawn does not depend on any of this -- a finished run is parsed whole --
 * so what these pin is the two ways the scheme could be *wrong about a boundary*: settling into
 * an open code fence, which would split the fence in two, and settling between two items of one
 * list, which would draw it as two lists until the run finished.
 */
class StreamingMarkdownDocumentTest {
    private val flavour = GFMFlavourDescriptor()

    private fun blockTypes(document: StreamingMarkdownDocument): List<String> =
        document.segments.flatMap { segment -> segment.blocks.map { it.type.name } }
            .filter { it != "EOL" && it != "WHITE_SPACE" }

    @Test
    fun aParagraphFollowedByANewBlockIsSettled() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("First paragraph.\n\nSecond, still")

        assertEquals(2, document.segments.size)
        assertEquals("First paragraph.\n\n", document.segments[0].source)
        assertEquals("Second, still", document.segments[1].source)
    }

    @Test
    fun aBlankLineInsideAnOpenFenceIsNotABoundary() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("Intro.\n\n```kotlin\nval a = 1\n\nval b = 2")

        assertEquals(listOf("Intro.\n\n", "```kotlin\nval a = 1\n\nval b = 2"), document.segments.map { it.source })
        assertEquals(listOf("PARAGRAPH", "CODE_FENCE"), blockTypes(document))
    }

    @Test
    fun aBlankLineBetweenListItemsIsNotABoundary() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("- one\n\n- two\n\n- three")

        assertEquals(1, document.segments.size)
        assertEquals(listOf("UNORDERED_LIST"), blockTypes(document))
        assertEquals(
            3,
            document.segments.single().blocks.single().children.count { it.type == MarkdownElementTypes.LIST_ITEM },
        )
    }

    @Test
    fun anIndentedLineAfterABlankContinuesItsBlock() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("- item\n\n  continued\n\nNext.")

        assertEquals(listOf("UNORDERED_LIST", "PARAGRAPH"), blockTypes(document))
        assertEquals(2, document.segments.size)
    }

    @Test
    fun aRunThatIsNotAnExtensionStartsOver() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("First.\n\nSecond.\n\nThird")
        assertEquals(2, document.segments.size)

        document.update("Replaced")
        assertEquals(listOf("Replaced"), document.segments.map { it.source })
    }

    @Test
    fun theSameTextIsNotParsedTwice() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("Some **text**")
        val parses = document.parses
        document.update("Some **text**")

        assertEquals(parses, document.parses)
    }

    /**
     * Only the open tail is re-parsed per token. Fed a long run in small pieces, the settled
     * prefix is parsed once per boundary and the parse that repeats is bounded by a paragraph,
     * which is what makes the scheme worth having over re-parsing from the top.
     */
    @Test
    fun onlyTheOpenTailIsReparsedPerToken() {
        val paragraph = "Some prose with **emphasis**, a [link](https://x.y) and `code` in it. ".repeat(3).trim()
        val run = List(40) { i -> if (i % 5 == 4) "```\nval x = $i\n\n```" else paragraph }.joinToString("\n\n")

        val document = StreamingMarkdownDocument(flavour)
        var reparsed = 0L
        var fed = 0
        while (fed < run.length) {
            fed = minOf(run.length, fed + 8)
            document.update(run.substring(0, fed))
            reparsed += document.segments.last().source.length
        }

        val fromTheTop = (8..run.length step 8).sumOf { it.toLong() } + run.length
        assertTrue(reparsed * 10 < fromTheTop, "re-parsed $reparsed chars against $fromTheTop from the top")
        assertEquals(flavour.parse(run).blocks.map { it.type }, document.segments.flatMap { it.blocks }.map { it.type })
    }

    @Test
    fun aClosingFenceWithAnInfoStringIsNotAClose() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("```\ncode\n```kotlin\n\nstill code")

        assertEquals(1, document.segments.size)
        assertEquals(listOf("CODE_FENCE"), blockTypes(document))
    }

    @Test
    fun anIndentedFenceAfterABlankContinuesItsListItem() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("- item\n\n  ```\n  code\n  ```\n- next")

        assertEquals(1, document.segments.size)
        assertEquals(listOf("UNORDERED_LIST"), blockTypes(document))
    }

    /**
     * The last line of the run is still being typed, so a bare number there may yet become an
     * ordered-list marker continuing the list above -- and a settle taken on it could not be
     * revoked once the `.` arrived.
     */
    @Test
    fun aBareNumberOnTheLastLineIsNotABoundary() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("1. a\n\n1")
        document.update("1. a\n\n1. b")

        assertEquals(1, document.segments.size)
        assertEquals(listOf("ORDERED_LIST"), blockTypes(document))
    }

    /**
     * What the per-segment resolution of link references costs: a definition in one segment
     * does not resolve a reference in another, in either direction.
     */
    @Test
    fun aLinkDefinitionInAnotherSegmentDoesNotResolve() {
        val document = StreamingMarkdownDocument(flavour)
        document.update("See [d].\n\n[d]: https://x")
        assertEquals(2, document.segments.size)
        assertEquals(emptyMap(), document.segments[0].linkDefinitions)
        assertEquals(mapOf("d" to "https://x"), document.segments[1].linkDefinitions)
    }

    @Test
    fun aLinkDefinitionIsFoundInsideAContainerAndTheFirstOneWins() {
        val segment = flavour.parse("- see [d]\n\n  [d]: https://one\n\n[Foo  Bar]: https://two\n[foo bar]: https://three")

        assertEquals(mapOf("d" to "https://one", "foo bar" to "https://two"), segment.linkDefinitions)
    }
}
