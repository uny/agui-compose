package dev.ynagai.agui.sample

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

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
        waitUntil { onAllNodesWithText("ok").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("hello")[0].assertExists()
        // The transcript's own report, which `AguiTranscript` deliberately does not draw -- so if
        // the sample stopped drawing it, nothing else here would notice.
        onNodeWithText("thread $THREAD — finished").assertExists()
    }
}
