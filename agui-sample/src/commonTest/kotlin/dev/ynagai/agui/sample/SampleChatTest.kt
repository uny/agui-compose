package dev.ynagai.agui.sample

import com.agui.core.types.ToolMessage
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the sample's own layer does, with the socket replaced by a script.
 *
 * The library's behaviour is tested in `agui-agent`; what is measured here is the part this module
 * adds -- that connecting builds a session, that reconnecting disposes the agent it replaces
 * rather than leaking it, and that a failed run still reaches the status line. The double is this
 * module's own rather than `agui-agent`'s `ScriptedAgent`: sharing it across modules needs test
 * fixtures published between them, which is a build change for four lines of class.
 */
class SampleChatTest {

    @Test
    fun sends_a_turn_and_grows_the_transcript() = runTest {
        val chat = SampleChat { ScriptedAgent(it) }
        chat.connect("http://localhost:8000/")

        val state = chat.send("hello")

        assertTrue(state is RunState.Finished, "expected a finished run, got $state")
        val messages = assertNotNull(chat.connection.value).transcript.value.messages
        assertEquals(listOf(UiRole.USER, UiRole.ASSISTANT), messages.map { it.role })
    }

    @Test
    fun sends_the_agents_history_rather_than_only_the_new_turn() = runTest {
        val agents = mutableListOf<ScriptedAgent>()
        val chat = SampleChat { url -> ScriptedAgent(url).also { agents += it } }
        chat.connect("http://localhost:8000/")

        chat.send("first")
        chat.send("second")

        // The second run carries the first turn, the answer to it, and the new turn. This is the
        // library's contract rather than the sample's, and it is asserted here because it is the
        // one thing a sample that talked to a real server would show and a unit test would
        // otherwise leave to inference.
        val secondRun = agents.single().inputs.last()
        assertEquals(
            listOf("first", ANSWER, "second"),
            secondRun.messages.map { it.content },
        )
    }

    /**
     * The frontend tool, end to end against a scripted agent: the call is executed here, the
     * window is told what to paint, and the result goes back in a run of its own that the agent
     * answers. The second input is the wire, and what it ends in is what a server would see.
     */
    @Test
    fun a_change_background_call_paints_the_window_and_is_answered() = runTest {
        val agents = mutableListOf<ScriptedAgent>()
        val chat = SampleChat { url -> ScriptedAgent(url, callsTool = true).also { agents += it } }
        chat.connect("http://localhost:8000/")

        val state = chat.send("tool")

        assertEquals(BACKGROUND, chat.background.value)
        assertTrue(state is RunState.Finished, "expected a finished run, got $state")
        val inputs = agents.single().inputs
        assertEquals(2, inputs.size, "one run to call the tool, one to answer it")
        assertEquals(listOf("change_background"), inputs.first().tools.map { it.name })
        val result = assertIs<ToolMessage>(inputs.last().messages.last())
        assertEquals("call-${inputs.first().runId}", result.toolCallId)
        val messages = assertNotNull(chat.connection.value).transcript.value.messages
        assertEquals(listOf(UiRole.USER, UiRole.ASSISTANT, UiRole.ASSISTANT), messages.map { it.role })
        assertEquals(ToolCallStatus.COMPLETE, messages[1].parts.filterIsInstance<ToolCallPart>().single().status)
    }

    @Test
    fun reconnecting_forgets_the_background() = runTest {
        val chat = SampleChat { url -> ScriptedAgent(url, callsTool = true) }
        chat.connect("http://localhost:8000/")
        chat.send("tool")
        assertEquals(BACKGROUND, chat.background.value)

        chat.connect("http://localhost:8001/")

        assertNull(chat.background.value)
    }

    @Test
    fun background_colours_are_read_off_the_css_and_nothing_else_is() {
        assertEquals(listOf(0xFF667EEAL, 0xFF764BA2L), backgroundColors(BACKGROUND))
        assertEquals(listOf(0xFFFFFFFFL), backgroundColors("#fff"))
        assertEquals(emptyList(), backgroundColors("rebeccapurple"))
    }

    @Test
    fun reconnecting_disposes_the_agent_it_replaces() = runTest {
        val agents = mutableListOf<ScriptedAgent>()
        val chat = SampleChat { url -> ScriptedAgent(url).also { agents += it } }

        chat.connect("http://localhost:8000/")
        chat.connect("http://localhost:9000/")

        assertEquals(2, agents.size)
        assertTrue(agents[0].disposed, "the replaced agent was left open")
        assertTrue(!agents[1].disposed, "the current agent was disposed")
        assertEquals("http://localhost:9000/", assertNotNull(chat.connection.value).url)
    }

    @Test
    fun closing_disposes_and_forgets() = runTest {
        val agents = mutableListOf<ScriptedAgent>()
        val chat = SampleChat { url -> ScriptedAgent(url).also { agents += it } }
        chat.connect("http://localhost:8000/")

        chat.close()
        chat.close()

        assertTrue(agents.single().disposed)
        assertNull(chat.connection.value)
    }

    @Test
    fun sending_with_no_endpoint_is_not_an_error() = runTest {
        assertNull(SampleChat { ScriptedAgent(it) }.send("hello"))
    }

    @Test
    fun a_failed_run_reaches_the_status_line() = runTest {
        val chat = SampleChat { url -> ScriptedAgent(url, failing = true) }
        chat.connect("http://localhost:8000/")

        val state = chat.send("hello")

        assertTrue(state is RunState.Failed, "expected a failed run, got $state")
        assertEquals("thread t — failed: no (NOPE)", statusLine(threadId = "t", run = state))
        // The turn stays on screen, which is what makes a retry possible.
        val messages = assertNotNull(chat.connection.value).transcript.value.messages
        assertEquals(listOf(UiRole.USER), messages.map { it.role })
    }

    @Test
    fun the_status_line_names_every_run_state() {
        assertEquals("not connected — idle", statusLine(threadId = null, run = RunState.Idle))
        assertEquals("thread t — running r", statusLine("t", RunState.Running(threadId = "t", runId = "r")))
        assertEquals("thread t — finished", statusLine("t", RunState.Finished(threadId = "t", runId = "r")))
        assertEquals(
            "thread t — interrupted",
            statusLine("t", RunState.Finished(threadId = "t", runId = "r", interrupted = true)),
        )
        assertEquals("thread t — failed: boom", statusLine("t", RunState.Failed(message = "boom")))
    }
}
