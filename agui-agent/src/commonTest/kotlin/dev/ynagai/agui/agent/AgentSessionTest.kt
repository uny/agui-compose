package dev.ynagai.agui.agent

import com.agui.client.agent.AbstractAgent
import com.agui.client.agent.AgentConfig
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.AssistantMessage
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunAgentInput
import com.agui.core.types.RunErrorEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.TextMessageEndEvent
import com.agui.core.types.TextMessageStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.UserMessage
import dev.ynagai.agui.model.RunState
import dev.ynagai.agui.model.TextPart
import dev.ynagai.agui.model.ToolCallPart
import dev.ynagai.agui.model.ToolCallStatus
import dev.ynagai.agui.model.UiRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An [AbstractAgent] whose `run` is a script rather than a socket. What it is handed on each run
 * is kept, because what upstream sends as history is part of what is under test.
 */
private class ScriptedAgent(
    private val script: (RunAgentInput) -> Flow<BaseEvent>,
    config: AgentConfig = AgentConfig(threadId = "t"),
) : AbstractAgent(config) {
    val inputs = mutableListOf<RunAgentInput>()

    override fun run(input: RunAgentInput): Flow<BaseEvent> {
        inputs += input
        return script(input)
    }
}

private fun answer(runId: String, messageId: String, vararg deltas: String): List<BaseEvent> = buildList {
    add(RunStartedEvent(threadId = "t", runId = runId))
    add(TextMessageStartEvent(messageId = messageId))
    deltas.forEach { add(TextMessageContentEvent(messageId = messageId, delta = it)) }
    add(TextMessageEndEvent(messageId = messageId))
    add(RunFinishedEvent(threadId = "t", runId = runId))
}

class AgentSessionTest {

    @Test
    fun folds_an_upstream_run_into_a_transcript() = runTest {
        val agent = ScriptedAgent({ flow { answer("r1", "m1", "Hel", "lo").forEach { emit(it) } } })
        val session = AgentSession(agent)

        val ended = session.run(RunAgentParameters(runId = "r1"))

        assertEquals(RunState.Finished("t", "r1"), ended)
        val message = session.transcript.value.messages.single()
        assertEquals(UiRole.ASSISTANT, message.role)
        assertEquals("Hello", (message.parts.single() as TextPart).text)
        assertEquals(RunState.Finished("t", "r1"), session.transcript.value.run)
    }

    /**
     * The reason this class exists. A conversation is many runs, and the transcript is of the
     * conversation.
     */
    @Test
    fun a_second_run_appends_to_the_same_transcript() = runTest {
        val agent = ScriptedAgent({ input ->
            flow { answer(input.runId, "m-${input.runId}", "answer ", input.runId).forEach { emit(it) } }
        })
        val session = AgentSession(agent)

        session.run(RunAgentParameters(runId = "r1"))
        session.run(RunAgentParameters(runId = "r2"))

        val texts = session.transcript.value.messages.map { (it.parts.single() as TextPart).text }
        assertEquals(listOf("answer r1", "answer r2"), texts)
        assertEquals(RunState.Finished("t", "r2"), session.transcript.value.run)
    }

    /**
     * Upstream's own transcript is the history it sends with the next run. The session relies on
     * that rather than keeping a second copy, so it is measured here: the first run's answer
     * arrives in the second run's input, assembled from the deltas rather than left empty.
     */
    @Test
    fun the_agent_carries_the_previous_answer_into_the_next_run_input() = runTest {
        val agent = ScriptedAgent(
            { input -> flow { answer(input.runId, "m-${input.runId}", "hi ", input.runId).forEach { emit(it) } } },
            AgentConfig(threadId = "t", initialMessages = listOf(UserMessage(id = "u1", content = "hello"))),
        )
        val session = AgentSession(agent)

        session.run(RunAgentParameters(runId = "r1"))
        session.run(RunAgentParameters(runId = "r2"))

        val second = agent.inputs[1].messages
        assertEquals(listOf("u1", "m-r1"), second.map { it.id })
        assertEquals("hi r1", (second[1] as AssistantMessage).content)
    }

    @Test
    fun a_run_error_from_the_agent_ends_the_run_failed() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                emit(RunErrorEvent(message = "upstream said no", code = "SERVER"))
            }
        })
        val session = AgentSession(agent)

        val ended = session.run()

        assertEquals(RunState.Failed("upstream said no", "SERVER"), ended)
    }

    /**
     * Upstream's verifier throws on a stream that breaks the protocol's state machine, and the
     * observable rethrows it. The run is reported failed rather than the exception escaping into
     * whatever coroutine a UI launched the run from.
     */
    @Test
    fun a_stream_that_throws_is_recorded_as_a_client_error_and_not_rethrown() = runTest {
        // No RUN_STARTED: the first thing the verifier rejects.
        val agent = ScriptedAgent({ flow { emit(TextMessageStartEvent(messageId = "m1")) } })
        val session = AgentSession(agent)

        val ended = session.run()

        val failed = assertIs<RunState.Failed>(ended)
        assertEquals(AgentSession.CLIENT_ERROR_CODE, failed.code)
        assertTrue(failed.message.contains("RUN_STARTED"), failed.message)
    }

    @Test
    fun a_throw_mid_stream_settles_what_was_still_streaming() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                emit(TextMessageStartEvent(messageId = "m1"))
                emit(TextMessageContentEvent(messageId = "m1", delta = "partial"))
                emit(ToolCallStartEvent(toolCallId = "c1", toolCallName = "search"))
                emit(ToolCallArgsEvent(toolCallId = "c1", delta = "{\"q\":"))
                throw IllegalStateException("socket closed")
            }
        })
        val session = AgentSession(agent)

        session.run()

        val parts = session.transcript.value.messages.single().parts
        val text = assertIs<TextPart>(parts[0])
        assertEquals("partial", text.text)
        assertEquals(false, text.streaming)
        val call = assertIs<ToolCallPart>(parts[1])
        assertEquals(ToolCallStatus.FAILED, call.status)
        assertEquals(RunState.Failed("socket closed", AgentSession.CLIENT_ERROR_CODE), session.transcript.value.run)
    }

    /**
     * Cancellation propagates -- a cancelled coroutine has to stay cancelled -- but not before the
     * transcript stops claiming the run is in flight.
     */
    @Test
    fun cancelling_the_run_marks_the_transcript_and_rethrows() = runTest {
        val started = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                emit(TextMessageStartEvent(messageId = "m1"))
                emit(TextMessageContentEvent(messageId = "m1", delta = "so far"))
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // never
            }
        })
        val session = AgentSession(agent)

        val job = launch { session.run() }
        started.await()
        assertEquals(RunState.Running("t", "r1"), session.transcript.value.run)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        val failed = assertIs<RunState.Failed>(session.transcript.value.run)
        assertEquals(AgentSession.CANCELLED_CODE, failed.code)
        assertEquals(false, (session.transcript.value.messages.single().parts.single() as TextPart).streaming)
    }

    @Test
    fun runs_take_turns_rather_than_interleaving() = runTest {
        val release = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({ input ->
            flow {
                emit(RunStartedEvent(threadId = "t", runId = input.runId))
                if (input.runId == "r1") release.await()
                emit(TextMessageStartEvent(messageId = "m-${input.runId}"))
                emit(TextMessageContentEvent(messageId = "m-${input.runId}", delta = input.runId))
                emit(TextMessageEndEvent(messageId = "m-${input.runId}"))
                emit(RunFinishedEvent(threadId = "t", runId = input.runId))
            }
        })
        val session = AgentSession(agent)

        val first = launch { session.run(RunAgentParameters(runId = "r1")) }
        val second = launch { session.run(RunAgentParameters(runId = "r2")) }
        testScheduler.runCurrent()
        // The second run has not been handed to the agent while the first is still in flight.
        assertEquals(listOf("r1"), agent.inputs.map { it.runId })

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("r1", "r2"), agent.inputs.map { it.runId })
        assertEquals(
            listOf("r1", "r2"),
            session.transcript.value.messages.map { (it.parts.single() as TextPart).text },
        )
    }

    @Test
    fun cancellation_is_a_cancellation_exception_not_a_client_error() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                throw CancellationException("stopped")
            }
        })
        val session = AgentSession(agent)

        assertFailsWith<CancellationException> { session.run() }
        assertEquals(RunState.Failed("stopped", AgentSession.CANCELLED_CODE), session.transcript.value.run)
    }
}
