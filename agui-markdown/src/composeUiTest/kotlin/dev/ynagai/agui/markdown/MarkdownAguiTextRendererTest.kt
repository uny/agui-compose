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

        text = "A **bold** claim"
        waitForIdle()
        text = "A **bold** claim, at last."
        waitForIdle()

        onNodeWithText("A bold claim, at last.").assertIsDisplayed()
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
        onNodeWithText("First attempt.Second attempt.").assertDoesNotExist()
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

        streaming = false
        waitForIdle()

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
