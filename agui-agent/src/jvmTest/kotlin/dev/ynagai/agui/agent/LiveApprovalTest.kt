package dev.ynagai.agui.agent

import com.agui.client.agent.HttpAgent
import com.agui.client.agent.HttpAgentConfig
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiResumeEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The approval round-trip against a live agent, when one is offered.
 *
 * Every other interrupt test in this repository is fed by `ScriptedAgent`, which says whatever the
 * test wrote. This one is fed by a server with a model behind it, and asks the question the
 * scripts cannot: does a real producer's `RUN_FINISHED` carry an interrupt in the shape 0010
 * assumes, and does the answer the Material 3 card sends actually run the tool?
 *
 * The producer is AWS Strands with `ToolBehavior(interrupt_on_call=True)` on a server-executed
 * tool -- the only shipped adapter that publishes a `responseSchema` -- fronting whatever model
 * the server was started with. The model is not what is measured: the assertions are about the
 * protocol's shape and the tool's fate, and never about the text the model wrote.
 *
 * Skipped unless `AGUI_LIVE_APPROVAL_URL` names the endpoint, because CI has neither the server
 * nor a key for the model behind it, and a test that fails for want of a server measures nothing.
 * A skip is reported as one, not as a pass. Measured the day it was written, against
 * `ag_ui_strands` 0.4.0 and `gemini-2.5-flash`, and the raw traffic is in the pull request.
 */
class LiveApprovalTest {
    private val url = System.getenv("AGUI_LIVE_APPROVAL_URL")

    @Test
    fun approving_a_transfer_runs_the_tool() = live { session ->
        val asked = assertIs<RunState.Finished>(session.send("Transfer 100 to Alice"))
        val interrupt = asked.interrupts.single()

        // The producer's own shape: the reason the spec names for an approval, a prompt, the call
        // the approval concerns, and a schema asking for `approved` -- what the card reads.
        assertEquals("tool_call", interrupt.reason)
        assertNotNull(interrupt.message)
        val schema = assertNotNull(interrupt.responseSchema).jsonObject
        assertTrue("approved" in schema.getValue("properties").jsonObject)
        val call = assertNotNull(interrupt.toolCallId)
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, session.toolCall(call).status)
        assertEquals(listOf(interrupt), session.pendingInterrupts.value)

        val answered = session.resume(listOf(UiResumeEntry.resolved(interrupt, approved(true))))
        assertIs<RunState.Finished>(answered)
        assertTrue(answered.interrupts.isEmpty())
        assertTrue(session.pendingInterrupts.value.isEmpty())

        // The tool ran, and said so through the call the interrupt named.
        val ran = session.toolCall(call)
        assertEquals(ToolCallStatus.COMPLETE, ran.status)
        assertTrue(assertNotNull(ran.result).contains("Alice"), ran.result)
    }

    @Test
    fun declining_a_transfer_answers_the_call_without_running_the_tool() = live { session ->
        val asked = assertIs<RunState.Finished>(session.send("Send 50 to Bob"))
        val interrupt = asked.interrupts.single()
        val call = assertNotNull(interrupt.toolCallId)

        // What the card's Decline sends. Strands answers its own approval hook `{"approved":
        // false}` for a cancelled entry, so the call gets a result -- a denial -- rather than
        // the error a cancelled generic interrupt would raise. Either way the tool did not run.
        val answered = session.resume(listOf(UiResumeEntry.cancelled(interrupt)))
        assertIs<RunState.Finished>(answered)
        assertTrue(session.pendingInterrupts.value.isEmpty())

        val denied = session.toolCall(call)
        assertEquals(ToolCallStatus.COMPLETE, denied.status)
        val result = assertNotNull(denied.result)
        assertTrue("denied" in result.lowercase(), result)
        assertTrue("Bob" !in result || "not" in result.lowercase(), result)
    }

    private fun live(block: suspend (AgentSession) -> Unit) = runTest(timeout = 3.minutes) {
        assumeTrue("AGUI_LIVE_APPROVAL_URL is unset; no live server to measure", url != null)
        val agent = HttpAgent(HttpAgentConfig(url = url!!))
        val session = AgentSession(agent)
        try {
            block(session)
        } finally {
            agent.dispose()
        }
    }

    private fun approved(value: Boolean): JsonObject = buildJsonObject { put("approved", JsonPrimitive(value)) }

    private fun AgentSession.toolCall(id: String): ToolCallPart =
        transcript.value.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single { it.toolCallId == id }
}
