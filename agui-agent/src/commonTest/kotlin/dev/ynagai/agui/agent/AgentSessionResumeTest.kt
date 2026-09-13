package dev.ynagai.agui.agent

import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.BaseEvent
import com.agui.core.types.Interrupt
import com.agui.core.types.ResumeStatus
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunFinishedInterruptOutcome
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiInterrupt
import dev.ynagai.agui.model.UiResumeEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A run that stops to ask, and the run that answers it.
 *
 * The agent is scripted on what it is *sent*, as the tools tests are: a run carrying no `resume`
 * calls a tool and stops to ask about it; one carrying a `resume` answers with text. What matters
 * is the wire -- `agent.inputs` -- and the two rules the protocol puts on it: no run starts while
 * an interrupt is unanswered, and every answer names an interrupt the thread is waiting on.
 */
class AgentSessionResumeTest {

    @Test
    fun the_interrupt_reaches_the_transcript_whole_and_the_call_it_names_waits() = runTest {
        val session = AgentSession(ScriptedAgent(askThenAnswer()))

        val ended = session.run(RunAgentParameters(runId = "r1"))

        assertIs<RunState.Finished>(ended)
        val interrupt = ended.interrupts.single()
        assertEquals(
            UiInterrupt(
                id = "i1",
                reason = "tool_call",
                message = "Approve call to transfer?",
                toolCallId = "c1",
                responseSchema = APPROVAL_SCHEMA,
                expiresAt = "2026-09-13T09:00:00Z",
                metadata = buildJsonObject { put("tool", JsonPrimitive("transfer")) },
            ),
            interrupt,
        )
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, session.toolCall("c1").status)
        assertEquals(listOf(interrupt), session.pendingInterrupts)
    }

    @Test
    fun resume_carries_the_answers_and_the_thread_stops_waiting() = runTest {
        val agent = ScriptedAgent(askThenAnswer())
        val session = AgentSession(agent)
        val asked = session.run(RunAgentParameters(runId = "r1")) as RunState.Finished

        val ended = session.resume(
            listOf(UiResumeEntry.resolved(asked.interrupts.single(), buildJsonObject { put("approved", JsonPrimitive(true)) })),
            RunAgentParameters(runId = "r2"),
        )

        assertIs<RunState.Finished>(ended)
        assertEquals(false, ended.interrupted)
        val entry = agent.inputs[1].resume!!.single()
        assertEquals("i1", entry.interruptId)
        assertEquals(ResumeStatus.RESOLVED, entry.status)
        assertEquals(buildJsonObject { put("approved", JsonPrimitive(true)) }, entry.payload)
        assertEquals(emptyList(), session.pendingInterrupts)
        // The next run started, so the call is no longer waiting on anyone here.
        assertEquals(ToolCallStatus.AWAITING_RESULT, session.toolCall("c1").status)
    }

    @Test
    fun a_run_that_did_not_ask_sends_no_resume() = runTest {
        val agent = ScriptedAgent()
        val session = AgentSession(agent)
        session.run()
        session.send("more")
        assertEquals(listOf(null, null), agent.inputs.map { it.resume })
        assertEquals(emptyList(), session.pendingInterrupts)
    }

    @Test
    fun run_and_send_refuse_while_the_thread_is_waiting() = runTest {
        val agent = ScriptedAgent(askThenAnswer())
        val session = AgentSession(agent)
        session.run(RunAgentParameters(runId = "r1"))
        val before = session.transcript.value

        assertFailsWith<IllegalStateException> { session.run() }
        assertFailsWith<IllegalStateException> { session.send("please continue") }

        assertEquals(1, agent.inputs.size)
        // Refused before the line reached the screen: nothing was said.
        assertEquals(before, session.transcript.value)
    }

    @Test
    fun resume_refuses_answers_that_do_not_match_the_question() = runTest {
        val agent = ScriptedAgent(askThenAnswer(interrupts = listOf(interrupt("i1"), interrupt("i2"))))
        val session = AgentSession(agent)
        val asked = session.run(RunAgentParameters(runId = "r1")) as RunState.Finished
        val (i1, i2) = asked.interrupts

        // One left uncovered: omission is not abandonment.
        assertFailsWith<IllegalStateException> { session.resume(listOf(UiResumeEntry.cancelled(i1))) }
        // One the thread is not waiting on.
        assertFailsWith<IllegalArgumentException> {
            session.resume(listOf(UiResumeEntry.cancelled(i1), UiResumeEntry.cancelled(i2), UiResumeEntry.cancelled(interrupt("i3").toUi())))
        }
        // One answered twice.
        assertFailsWith<IllegalArgumentException> {
            session.resume(listOf(UiResumeEntry.cancelled(i1), UiResumeEntry.cancelled(i1)))
        }

        assertEquals(1, agent.inputs.size)
        assertEquals(listOf("i1", "i2"), session.pendingInterrupts.map { it.id })

        session.resume(listOf(UiResumeEntry.cancelled(i2), UiResumeEntry.cancelled(i1)))
        assertEquals(listOf("i2", "i1"), agent.inputs[1].resume?.map { it.interruptId })
    }

    @Test
    fun resume_refuses_a_thread_that_is_not_waiting() = runTest {
        val session = AgentSession(ScriptedAgent())
        session.run()
        assertFailsWith<IllegalStateException> {
            session.resume(listOf(UiResumeEntry.cancelled(interrupt("i1").toUi())))
        }
    }

    /**
     * The answers a failed run carried were not consumed, so the thread is still waiting and the
     * retry -- through `run`, as for any failed run -- carries them again. The transcript has
     * stopped naming the interrupts by then; the session has not.
     */
    @Test
    fun a_failed_resume_is_still_owed_and_run_retries_it() = runTest {
        var fail = true
        val agent = ScriptedAgent({ input ->
            when {
                input.resume == null -> ask(input.runId, listOf(interrupt("i1")))
                fail -> flow {
                    emit(RunStartedEvent(threadId = THREAD, runId = input.runId))
                    emit(RunErrorEvent(message = "upstream is down", code = "BOOM"))
                }
                else -> flow { answer(input.runId, "a-${input.runId}", "done").forEach { emit(it) } }
            }
        })
        val session = AgentSession(agent)
        val asked = session.run(RunAgentParameters(runId = "r1")) as RunState.Finished

        val failed = session.resume(listOf(UiResumeEntry.cancelled(asked.interrupts.single())))

        assertIs<RunState.Failed>(failed)
        assertEquals(listOf("i1"), session.pendingInterrupts.map { it.id })
        // A second answer to the same question is refused; the retry is `run`.
        assertFailsWith<IllegalArgumentException> {
            session.resume(listOf(UiResumeEntry.cancelled(asked.interrupts.single()), UiResumeEntry.cancelled(asked.interrupts.single())))
        }

        fail = false
        val ended = session.run()

        assertIs<RunState.Finished>(ended)
        assertEquals(3, agent.inputs.size)
        assertEquals(listOf("i1"), agent.inputs[2].resume?.map { it.interruptId })
        assertEquals(emptyList(), session.pendingInterrupts)
        assertNull(agent.inputs[0].resume)
    }

    private fun AgentSession.toolCall(id: String): ToolCallPart =
        transcript.value.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().single { it.toolCallId == id }

    private fun Interrupt.toUi() = UiInterrupt(id = id, reason = reason)

    private fun interrupt(id: String) = Interrupt(id = id, reason = "approve")

    /** A run that calls `transfer` and stops to ask about it. */
    private fun ask(runId: String, interrupts: List<Interrupt>): Flow<BaseEvent> = flow {
        emit(RunStartedEvent(threadId = THREAD, runId = runId))
        emit(ToolCallStartEvent(toolCallId = "c1", toolCallName = "transfer"))
        emit(ToolCallArgsEvent(toolCallId = "c1", delta = """{"amount":1}"""))
        emit(ToolCallEndEvent(toolCallId = "c1"))
        emit(RunFinishedEvent(threadId = THREAD, runId = runId, outcome = RunFinishedInterruptOutcome(interrupts = interrupts)))
    }

    /** Asks on a run that carries no `resume`; answers with text on one that does. */
    private fun askThenAnswer(
        interrupts: List<Interrupt> = listOf(
            Interrupt(
                id = "i1",
                reason = "tool_call",
                message = "Approve call to transfer?",
                toolCallId = "c1",
                responseSchema = APPROVAL_SCHEMA,
                expiresAt = "2026-09-13T09:00:00Z",
                metadata = buildJsonObject { put("tool", JsonPrimitive("transfer")) },
            ),
        ),
    ): (RunAgentInput) -> Flow<BaseEvent> = { input ->
        if (input.resume == null) {
            ask(input.runId, interrupts)
        } else {
            flow { answer(input.runId, "a-${input.runId}", "done").forEach { emit(it) } }
        }
    }

    private companion object {
        val APPROVAL_SCHEMA = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { put("approved", buildJsonObject { put("type", JsonPrimitive("boolean")) }) })
        }
    }
}
