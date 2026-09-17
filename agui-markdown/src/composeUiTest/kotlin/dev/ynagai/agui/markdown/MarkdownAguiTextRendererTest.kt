package dev.ynagai.agui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import kotlin.test.Test

/**
 * What this module adds over `agui-compose`'s default renderer is that the syntax stops being
 * literal, and that a run still arriving is drawn as it arrives rather than only once it is done.
 * The first is one assertion; the second is where the code can actually be wrong, because the
 * renderer is handed the accumulated run and keeps a settled prefix of it parsed, so what it draws
 * is two trees stitched together rather than one -- and the seam is where a frame can go missing.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownAguiTextRendererTest {

    /** The whole point: `**bold**` is a word, not a word with four asterisks around it. */
    @Test
    fun markdownSyntaxIsParsedRatherThanShown() = runComposeUiTest {
        setContent { Rendered(text = "A **bold** claim.", streaming = false) }

        onNodeWithText("A bold claim.").assertIsDisplayed()
        onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    /**
     * Text that arrives in pieces is on screen at every piece, and whole at the end.
     *
     * Each recomposition hands the renderer the run so far; the settled prefix keeps its tree and
     * the open tail is parsed again. What can go wrong is at the seam: a tail that is not drawn
     * until it settles, or a prefix drawn twice once the tail has been folded into it.
     */
    @Test
    fun aRunThatArrivesInPiecesIsParsedAsOne() = runComposeUiTest {
        var text by mutableStateOf("A **bold**")

        setContent { Rendered(text = text, streaming = true) }

        // Asserted after every piece, not only at the end. The last frame is a finished run's
        // worth of text, and a renderer that held the tail back until it settled would still draw
        // that frame correctly; the intermediate assertions are what pin that the tail is drawn
        // while it is still open.
        onNodeWithText("A bold").assertIsDisplayed()

        text = "A **bold** claim"
        waitForIdle()
        onNodeWithText("A bold claim").assertIsDisplayed()

        text = "A **bold** claim, at last."
        waitForIdle()
        onNodeWithText("A bold claim, at last.").assertIsDisplayed()
    }

    /**
     * The constructor's flavour is the one that parses, asserted against a dialect that differs.
     *
     * `**bold**` is in every dialect, so no other test here would notice the flavour going astray.
     * Nor would asserting GFM alone: the constructor's default is GFM, so a renderer that ignored
     * its own parameter and built a `GFMFlavourDescriptor` at either parse site would change
     * nothing observable. What pins the wiring is a caller passing something else and getting it
     * -- CommonMark has no strikethrough, so the tildes stay on screen as the characters they are.
     */
    @Test
    fun theConstructorsFlavourIsTheOneThatParses() = runComposeUiTest {
        val commonMark = MarkdownAguiTextRenderer(flavour = CommonMarkFlavourDescriptor())

        setContent {
            commonMark.Render(text = "A ~~struck~~ claim.", streaming = false, modifier = Modifier)
        }

        onNodeWithText("A ~~struck~~ claim.").assertIsDisplayed()
    }

    /** The default dialect is GitHub's, which is the one that reads `~~struck~~` as struck. */
    @Test
    fun theDefaultFlavourIsGitHubs() = runComposeUiTest {
        setContent { Rendered(text = "A ~~struck~~ claim.", streaming = false) }

        onNodeWithText("A struck claim.").assertIsDisplayed()
    }

    /**
     * A run that begins empty is parsed from its first real token.
     *
     * `TEXT_MESSAGE_START` arrives before any content does, so the first frame of every streamed
     * message has `text == ""` -- a production path no other test here supplies. An empty run is
     * an empty document that every later run extends, so nothing is discarded when the first token
     * lands; what this asserts is that starting from empty does not read as a replaced run.
     */
    @Test
    fun aRunThatBeginsEmptyIsParsedFromItsFirstToken() = runComposeUiTest {
        var text by mutableStateOf("")

        setContent { Rendered(text = text, streaming = true) }

        text = "Now **it** starts."
        waitForIdle()

        onNodeWithText("Now it starts.").assertIsDisplayed()
    }

    /**
     * A renderer replaced while a run is still arriving does not take the transcript down with it.
     *
     * The KDoc tells a caller to `remember` the renderer and gives re-parsing every frame as the
     * cost of not doing so. An unremembered renderer is the single easiest mistake to make against
     * this API, and with the previous rendering layer it crashed rather than merely being slow: the
     * parser was replaced underneath a composable still holding the old tree's offsets. The test
     * outlived that layer because the mistake did not. A new renderer is a new
     * `GFMFlavourDescriptor()` and therefore a new streaming document keyed on it; what is pinned
     * is that the switch is a re-parse and not a crash.
     */
    @Test
    fun aRendererReplacedMidRunDoesNotCrash() = runComposeUiTest {
        var renderer by mutableStateOf(MarkdownAguiTextRenderer())

        setContent {
            renderer.Render(text = "A **bold** claim.", streaming = true, modifier = Modifier)
        }

        onNodeWithText("A bold claim.").assertIsDisplayed()

        renderer = MarkdownAguiTextRenderer()
        waitForIdle()

        onNodeWithText("A bold claim.").assertIsDisplayed()
    }

    /**
     * A run that is replaced rather than extended is replaced on screen too.
     *
     * A settled prefix is kept on the assumption that the run extends it, so this is the one case
     * the renderer has to notice and start over for -- a regenerated message, or a renderer reused
     * across two runs. Getting it wrong leaves the old text on screen with the new text welded
     * onto the end of it, which is why the old wording is asserted gone rather than the new one
     * merely present.
     */
    @Test
    fun aRunThatIsReplacedStartsOver() = runComposeUiTest {
        var text by mutableStateOf("First attempt.")

        setContent { Rendered(text = text, streaming = true) }

        onNodeWithText("First attempt.").assertIsDisplayed()

        text = "Second attempt."
        waitForIdle()

        onNodeWithText("First attempt.").assertDoesNotExist()
        onNodeWithText("Second attempt.").assertIsDisplayed()
    }

    /**
     * The end of a run is not a blank frame.
     *
     * `streaming` flipping to `false` swaps the settled segments for one complete parse of the same
     * text, and that parse happens in composition, on the same frame, precisely so that this
     * instant does not render an empty box. A reader would see the finished answer vanish and
     * return.
     */
    @Test
    fun theFinishedRunSurvivesTheSwitchOffStreaming() = runComposeUiTest {
        var streaming by mutableStateOf(true)

        setContent { Rendered(text = "Done, in **full**.", streaming = streaming) }

        onNodeWithText("Done, in full.").assertIsDisplayed()

        // One frame, not `waitForIdle`. Idling would drain a parse deferred to an effect too, so
        // this test would pass with one and assert nothing about the frame the reader actually
        // sees at the moment a run finishes -- which is the whole claim.
        mainClock.autoAdvance = false
        streaming = false
        mainClock.advanceTimeByFrame()

        onNodeWithText("Done, in full.").assertIsDisplayed()
    }

    /**
     * One `remember`ed renderer, as the KDoc tells a caller to write it -- and as the tests need it,
     * since a renderer rebuilt per frame would rebuild its streaming document with it and hide
     * exactly the settled-prefix behaviour being asserted.
     */
    @Composable
    private fun Rendered(text: String, streaming: Boolean) {
        val renderer = remember { MarkdownAguiTextRenderer() }
        renderer.Render(text = text, streaming = streaming, modifier = Modifier)
    }
}
