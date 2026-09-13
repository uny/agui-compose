package dev.ynagai.agui.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.agui.core.types.ResumeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The window, without a window.
 *
 * A sample that compiles and draws nothing is the failure mode a sample exists to rule out, and
 * eyeballing it rules that out only for whoever is looking. These assertions are what is left when
 * nobody is: that the fields are there, that nothing can be sent before there is somewhere to send
 * it, and that both halves of a turn reach the transcript the composition draws.
 *
 * The agent is [ScriptedAgent], so nothing here opens a socket. What a real server adds -- that the
 * transport, the SSE parser and the verifier agree with a server written against the same
 * specification -- is not something a composition can assert. The README says which server to run
 * to see that, and it is why this module exists at all.
 */
@OptIn(ExperimentalTestApi::class)
class SampleAppTest {

    @Test
    fun nothing_can_be_sent_before_an_endpoint_is_given() = runComposeUiTest {
        setContent { SampleApp(chat = SampleChat { ScriptedAgent(it) }) }

        onNodeWithText("Connect").assertIsNotEnabled()
        onNodeWithText("Send").assertIsNotEnabled()
        onNodeWithText("not connected — idle").assertExists()
    }

    @Test
    fun a_sent_turn_and_its_answer_both_reach_the_transcript() = runComposeUiTest {
        setContent { SampleApp(chat = SampleChat { ScriptedAgent(it) }) }

        onNodeWithText("AG-UI endpoint").performTextInput("http://localhost:8000/")
        onNodeWithText("Connect").performClick()
        onNodeWithText("Message").performTextInput("hello")
        onNodeWithText("Send").performClick()

        // The run is launched rather than awaited, so the assertion waits the way the screen does.
        waitUntil { onAllNodesWithText(ANSWER_DRAWN).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("hello")[0].assertExists()
        // The answer went out as `**ok**` and is on screen as `ok`, which is the only thing that
        // separates "the Markdown renderer is fitted" from "something drew the text". `waitUntil`
        // above is what actually fails when `textRenderer = textRenderer` is dropped -- these
        // matchers are exact, so the node would read `**ok**` and the wait would time out. This
        // line says the same thing from the other side, so that a later change to the wait cannot
        // quietly take the check with it.
        onAllNodesWithText(ANSWER).assertCountEquals(0)
        // The transcript's own report, which `AguiTranscript` deliberately does not draw -- so if
        // the sample stopped drawing it, nothing else here would notice.
        onNodeWithText("thread $THREAD — finished").assertExists()
    }

    /**
     * A run that stops to ask closes the composer and puts the question on screen; the answer
     * goes out as the next input's `resume`, and the composer opens again once it has.
     */
    @Test
    fun a_run_that_stops_to_ask_is_answered_from_the_card() = runComposeUiTest {
        val agents = mutableListOf<ScriptedAgent>()
        setContent { SampleApp(chat = SampleChat { ScriptedAgent(it, asks = true).also { agents += it } }) }

        onNodeWithText("AG-UI endpoint").performTextInput("http://localhost:8000/")
        onNodeWithText("Connect").performClick()
        onNodeWithText("Message").performTextInput("transfer")
        onNodeWithText("Send").performClick()

        waitUntil { onAllNodesWithText(PROMPT).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Message").assertIsNotEnabled()
        onNodeWithText("thread $THREAD — waiting on 1 answer(s)").assertExists()

        onNodeWithText("Approve").performClick()

        waitUntil { onAllNodesWithText(ANSWER_DRAWN).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(PROMPT).assertCountEquals(0)
        onNodeWithText("Message").assertIsEnabled()
        val entry = agents.single().inputs[1].resume!!.single()
        assertEquals(INTERRUPT, entry.interruptId)
        assertEquals(ResumeStatus.RESOLVED, entry.status)
    }

    /**
     * A resume whose run fails leaves the thread waiting, and the card has to come back for the
     * reader to answer again -- the composer stays closed, because `send` would be refused.
     */
    @Test
    fun a_failed_resume_brings_the_card_back() = runComposeUiTest {
        val agents = mutableListOf<ScriptedAgent>()
        setContent {
            SampleApp(chat = SampleChat { ScriptedAgent(it, asks = true).also { a -> a.resumesToFail = 1; agents += a } })
        }

        onNodeWithText("AG-UI endpoint").performTextInput("http://localhost:8000/")
        onNodeWithText("Connect").performClick()
        onNodeWithText("Message").performTextInput("transfer")
        onNodeWithText("Send").performClick()
        waitUntil { onAllNodesWithText(PROMPT).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Decline").performClick()
        waitUntil { onAllNodesWithText("thread $THREAD — failed: no (NOPE)").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(PROMPT).assertExists()
        onNodeWithText("Message").assertIsNotEnabled()

        onNodeWithText("Decline").performClick()
        waitUntil { onAllNodesWithText(ANSWER_DRAWN).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Message").assertIsEnabled()
        assertEquals(
            listOf(ResumeStatus.CANCELLED, ResumeStatus.CANCELLED),
            agents.single().inputs.drop(1).map { it.resume!!.single().status },
        )
    }

    @Test
    fun leaving_the_composition_disposes_the_agent() = runComposeUiTest {
        val agents = mutableListOf<ScriptedAgent>()
        val chat = SampleChat { url -> ScriptedAgent(url).also { agents += it } }
        var windowed by mutableStateOf(true)

        setContent { if (windowed) SampleApp(chat = chat) }
        onNodeWithText("AG-UI endpoint").performTextInput("http://localhost:8000/")
        onNodeWithText("Connect").performClick()

        // Closing the window is this, and nothing else: the composition goes away and
        // `DisposableEffect` is what turns that into a disposed agent. Without it every window
        // that was ever opened keeps its HTTP client.
        windowed = false
        waitForIdle()

        assertTrue(agents.single().disposed, "the window closed and the agent was left open")
        assertNull(chat.connection.value)
    }
}
