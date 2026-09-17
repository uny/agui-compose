package dev.ynagai.agui.markdown

import androidx.compose.ui.text.LinkAnnotation
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the inline walk puts on screen, asserted as characters.
 *
 * The parser keeps every delimiter as a token, so the renderer's whole job at this level is to
 * know which tokens are syntax and which are text -- and getting that wrong is invisible in the
 * renderer tests, which assert one bold word. These pin the cases where a delimiter character is
 * also a character: an asterisk in prose, a parenthesis outside a link, a `>` continuing a quote.
 */
class InlineBuilderTest {
    private val flavour = GFMFlavourDescriptor()

    private fun paragraphs(source: String): List<Pair<MarkdownSegment, ASTNode>> {
        val segment = flavour.parse(source)
        return segment.blocks.filter { it.type == MarkdownElementTypes.PARAGRAPH }.map { segment to it }
    }

    private fun text(source: String): String = paragraphs(source).joinToString("\n\n") { (segment, node) ->
        InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node).text
    }

    @Test
    fun delimitersAreDroppedOnlyInsideTheElementThatOwnsThem() {
        assertEquals("bold, italic, struck, code", text("**bold**, *italic*, ~~struck~~, `code`"))
        assertEquals("a * b and a_b and 2 ~ 3", text("a * b and a_b and 2 ~ 3"))
    }

    @Test
    fun parenthesesAndBracketsOutsideLinksAreText() {
        assertEquals("see (the note) and [not a link]", text("see (the note) and [not a link]"))
    }

    @Test
    fun linksKeepTheirTextAndCarryTheirDestination() {
        val (segment, node) = paragraphs("A [link](https://x.y \"title\") and <https://a.b> and https://c.d").single()
        val built = InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node)

        assertEquals("A link and https://a.b and https://c.d", built.text)
        assertEquals(
            listOf("https://x.y", "https://a.b", "https://c.d"),
            built.getLinkAnnotations(0, built.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun referenceLinksResolveAgainstTheSegmentsDefinitions() {
        val (segment, node) = paragraphs("See [the docs][d] or [d].\n\n[d]: https://docs.example").single()
        val built = InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node)

        assertEquals("See the docs or d.", built.text)
        assertEquals(
            listOf("https://docs.example", "https://docs.example"),
            built.getLinkAnnotations(0, built.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun anImageIsItsAltText() {
        assertEquals("Look: a diagram.", text("Look: ![a diagram](https://x.y/d.png)."))
    }

    @Test
    fun aSoftBreakIsASpaceAndAHardBreakIsALine() {
        assertEquals("one two", text("one\ntwo"))
        assertEquals("one\ntwo", text("one  \ntwo"))
        assertEquals("one\ntwo", text("one\\\ntwo"))
    }

    @Test
    fun aLazilyContinuedQuoteDoesNotShowItsSecondMarker() {
        val segment = flavour.parse("> quoted\n> more")
        val quote = segment.blocks.single { it.type == MarkdownElementTypes.BLOCK_QUOTE }
        val paragraph = quote.children.single { it.type == MarkdownElementTypes.PARAGRAPH }

        assertEquals("quoted more", InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(paragraph).text)
    }

    @Test
    fun escapesAndEntityReferencesAreResolvedInProseOnly() {
        assertEquals("snake_case, *not emphasis*, a & b, A, ©", text("snake\\_case, \\*not emphasis\\*, a &amp; b, &#65;, &copy;"))
        assertEquals("a\\_b", text("`a\\_b`"))
    }

    @Test
    fun aCodeSpanKeepsItsInnerBackticksAndSpaces() {
        assertEquals("x a`b y and     z", text("x `` a`b `` y and `   ` z"))
        assertEquals("a   b", text("`a\n  b`"))
    }

    @Test
    fun anEmailAutolinkIsAMailtoLinkAndAWwwAutolinkGetsItsScheme() {
        val (segment, node) = paragraphs("<a@b.com> or www.example.com").single()
        val built = InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node)

        assertEquals("a@b.com or www.example.com", built.text)
        assertEquals(
            listOf("mailto:a@b.com", "http://www.example.com"),
            built.getLinkAnnotations(0, built.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun aUrlInsideLinkTextOrAltTextIsNotASecondLink() {
        val (segment, node) = paragraphs("[https://a.b](https://c.d) ![see https://e.f](https://g.h)").single()
        val built = InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node)

        assertEquals("https://a.b see https://e.f", built.text)
        assertEquals(
            listOf("https://c.d"),
            built.getLinkAnnotations(0, built.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun anUnresolvedReferenceKeepsItsInlineSyntaxAndAShortcutImageDrawsItsAlt() {
        assertEquals("foo [bar] and pic", text("foo [*bar*] and ![pic]\n\n[pic]: /img"))
    }

    @Test
    fun aLinkDestinationIsUnescaped() {
        val (segment, node) = paragraphs("[b](https://example.com/?a=1&amp;b=2)").single()
        val built = InlineBuilder(segment, markdownAguiTypography(), markdownAguiColors()).build(node)

        assertEquals(listOf("https://example.com/?a=1&b=2"), built.getLinkAnnotations(0, built.length).map { (it.item as LinkAnnotation.Url).url })
    }
}
