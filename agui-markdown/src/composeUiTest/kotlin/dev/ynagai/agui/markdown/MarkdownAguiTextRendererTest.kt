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
 * literal, and that a run still arriving is parsed incrementally rather than from the top. The
 * first is one assertion; the second is where the code can actually be wrong, because the renderer
 * is handed the accumulated run and the parser it drives is append-only, so the delta between them
 * is reconstructed rather than given.
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
     * Text that arrives in pieces ends up rendered whole.
     *
     * This is the reconstruction working: each recomposition hands the renderer the run so far, and
     * only the part of it the parser has not seen may be appended. Feed the parser the whole run
     * every time and the document doubles; feed it nothing and it stops at the first token.
     */
    @Test
    fun aRunThatArrivesInPiecesIsParsedAsOne() = runComposeUiTest {
        var text by mutableStateOf("A **bold**")

        setContent { Rendered(text = text, streaming = true) }

        // Asserted after every piece, not only at the end. Feeding the parser the whole run each
        // time doubles the document -- but only until the *next* piece fails the prefix check,
        // which restarts the parser and launders the damage away. A single assertion on the
        // settled last frame therefore passes while the delta reconstruction is broken; these
        // intermediate ones are what fail.
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
     * Nor would asserting GFM alone: the parser's *own* default is GFM too, so dropping
     * `flavour = flavour` at either call site changes nothing observable. What pins the wiring is a
     * caller passing something else and getting it -- CommonMark has no strikethrough, so the
     * tildes stay on screen as the characters they are.
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
     * message has `text == ""` -- a production path no other test here supplies. It is covered
     * rather than pinned: the arm it lands in does nothing, and the neighbouring `>=` spelling
     * would append an empty string instead, which is a wasted call and not a defect. What this
     * asserts is that starting from empty does not read as a replaced run.
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
     * cost of not doing so. That understated it: `rememberStreamingMarkdownState` keys on the
     * flavour, and the default `GFMFlavourDescriptor()` is a fresh instance per renderer, so a new
     * renderer arriving mid-run replaced the parser *without* replacing the `Markdown` around it --
     * which then indexed the previous snapshot's node ranges into an empty buffer and threw
     * `StringIndexOutOfBoundsException`. An unremembered renderer is the single easiest mistake to
     * make against this API, and it crashed rather than merely being slow.
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
     * An append-only parser cannot retract, so this is the one case the renderer has to notice and
     * start over for -- a regenerated message, or a renderer reused across two runs. Getting it
     * wrong leaves the old text on screen with the new text welded onto the end of it, which is
     * why the old wording is asserted gone rather than the new one merely present.
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
     * `streaming` flipping to `false` swaps the incremental parser for a complete parse of the same
     * text, and the complete parse is asked for synchronously precisely so that this instant does
     * not render an empty box. A reader would see the finished answer vanish and return.
     */
    @Test
    fun theFinishedRunSurvivesTheSwitchOffStreaming() = runComposeUiTest {
        var streaming by mutableStateOf(true)

        setContent { Rendered(text = "Done, in **full**.", streaming = streaming) }

        onNodeWithText("Done, in full.").assertIsDisplayed()

        // One frame, not `waitForIdle`. Idling drains the asynchronous parse too, so this test
        // would pass with `immediate = false` and assert nothing about the frame the reader
        // actually sees at the moment a run finishes -- which is the whole claim.
        mainClock.autoAdvance = false
        streaming = false
        mainClock.advanceTimeByFrame()

        onNodeWithText("Done, in full.").assertIsDisplayed()
    }

    /**
     * One `remember`ed renderer, as the KDoc tells a caller to write it -- and as the tests need it,
     * since a renderer rebuilt per frame would rebuild the parser with it and hide exactly the
     * incremental behaviour being asserted.
     */
    @Composable
    private fun Rendered(text: String, streaming: Boolean) {
        val renderer = remember { MarkdownAguiTextRenderer() }
        renderer.Render(text = text, streaming = streaming, modifier = Modifier)
    }
}
