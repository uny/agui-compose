package dev.ynagai.agui.material3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.agui.compose.AguiTranscript
import dev.ynagai.agui.model.ReasoningPart
import dev.ynagai.agui.model.UiMessage
import dev.ynagai.agui.model.UiRole
import dev.ynagai.agui.model.UiTranscript
import kotlin.test.Test

/**
 * The disclosure is the one piece of state this module owns, and so the only place it can be wrong
 * in a way the type checker will not catch.
 */
@OptIn(ExperimentalTestApi::class)
class Material3ReasoningTest {

    /** Open while it streams: watching the agent think is the point of showing reasoning at all. */
    @Test
    fun reasoningIsOpenWhileItStreams() = runComposeUiTest {
        val transcript = transcriptOf(streaming = true)

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        onNodeWithText("Checking the schema").assertIsDisplayed()
        onNodeWithText("the-thinking").assertIsDisplayed()
    }

    /**
     * Closed once it has finished, without the reader doing anything.
     *
     * The part streams and *then* stops, rather than arriving already finished. Drawing a finished
     * part closed is the easy half and an implementation can get it right while still leaving an
     * open disclosure open forever once the run ends, which is the case a reader actually hits.
     *
     * The header survives, because a collapsed disclosure with no handle is an invisible feature.
     */
    @Test
    fun reasoningCollapsesWhenTheStreamEnds() = runComposeUiTest {
        var streaming by mutableStateOf(true)

        setContent { Material3TestSurface { AguiTranscript(transcriptOf(streaming)) } }

        onNodeWithText("the-thinking").assertIsDisplayed()

        streaming = false
        waitForIdle()

        onNodeWithText("the-thinking").assertDoesNotExist()
        onNodeWithText("Checking the schema").assertIsDisplayed()
    }

    /**
     * The reader's choice outranks the stream, and keeps outranking it.
     *
     * Collapsing a still-streaming run and then having it reopen by itself when the run ended would
     * be the same bug in the other direction, so the assertions continue past the click.
     */
    @Test
    fun theReadersChoiceSurvivesTheStreamEnding() = runComposeUiTest {
        var streaming by mutableStateOf(true)

        setContent { Material3TestSurface { AguiTranscript(transcriptOf(streaming)) } }

        onNodeWithText("the-thinking").assertIsDisplayed()

        // "Hide" is the header's own affordance; clicking it is what a reader does.
        onNodeWithText(AguiStrings.HIDE).performClick()
        onNodeWithText("the-thinking").assertDoesNotExist()

        streaming = false
        waitForIdle()
        onNodeWithText("the-thinking").assertDoesNotExist()

        // And back open on a second click, still against a finished part.
        onNodeWithText(AguiStrings.SHOW).performClick()
        onNodeWithText("the-thinking").assertIsDisplayed()
    }

    /** No `title` from the protocol, so the generic word. */
    @Test
    fun reasoningWithoutATitleFallsBackToTheGenericLabel() = runComposeUiTest {
        val transcript = UiTranscript(
            messages = listOf(
                UiMessage(
                    id = "m1",
                    role = UiRole.ASSISTANT,
                    parts = listOf(ReasoningPart(id = "r1", text = "untitled", messageId = "m1")),
                ),
            ),
        )

        setContent { Material3TestSurface { AguiTranscript(transcript) } }

        onNodeWithText(AguiStrings.REASONING).assertIsDisplayed()
    }

    private fun transcriptOf(streaming: Boolean) = UiTranscript(
        messages = listOf(
            UiMessage(
                id = "m1",
                role = UiRole.ASSISTANT,
                parts = listOf(
                    ReasoningPart(
                        id = "r1",
                        text = "the-thinking",
                        messageId = "m1",
                        title = "Checking the schema",
                        streaming = streaming,
                    ),
                ),
            ),
        ),
    )
}
