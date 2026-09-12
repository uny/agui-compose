package dev.ynagai.agui.agent

import com.agui.client.agent.AgentConfig
import com.agui.client.agent.RunAgentParameters
import com.agui.core.types.AssistantMessage
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
     * How every transport failure arrives. `HttpAgent` turns a refused connection, a non-SSE
     * response or a timeout into a `RUN_ERROR` with nothing before it, and upstream's verifier
     * exempts that one event from its "first event must be RUN_STARTED" rule. The message and
     * code upstream chose have to survive to the transcript, not be replaced by a verifier
     * complaint about ordering.
     */
    @Test
    fun a_run_error_as_the_only_event_keeps_its_message_and_code() = runTest {
        val agent = ScriptedAgent({
            flow { emit(RunErrorEvent(message = "Server did not return an SSE stream.", code = "INVALID_RESPONSE")) }
        })
        val session = AgentSession(agent)

        val ended = session.run()

        assertEquals(RunState.Failed("Server did not return an SSE stream.", "INVALID_RESPONSE"), ended)
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
        // A fixed message, not the exception's: a causeless `cancel()` names the coroutine class.
        assertEquals(RunState.Failed("Run cancelled", AgentSession.CANCELLED_CODE), session.transcript.value.run)
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
        assertEquals(RunState.Failed("Run cancelled", AgentSession.CANCELLED_CODE), session.transcript.value.run)
    }

    /**
     * Upstream's verifier checks each event against the last and has no opinion about the end of
     * the stream, so a server that closes the connection cleanly mid-run reaches here as a flow
     * that simply completes. Left alone, that is the caret-blinks-forever state.
     */
    @Test
    fun a_stream_that_ends_without_a_terminal_event_is_a_client_error() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                emit(TextMessageStartEvent(messageId = "m1"))
                emit(TextMessageContentEvent(messageId = "m1", delta = "partial"))
            }
        })
        val session = AgentSession(agent)

        val ended = session.run()

        val failed = assertIs<RunState.Failed>(ended)
        assertEquals(AgentSession.CLIENT_ERROR_CODE, failed.code)
        assertEquals(false, (session.transcript.value.messages.single().parts.single() as TextPart).streaming)
    }

    /** The empty case of the above: without this, `run` would report the *previous* run's state. */
    @Test
    fun an_empty_stream_after_a_finished_run_does_not_report_the_earlier_run() = runTest {
        val agent = ScriptedAgent({ input ->
            if (input.runId == "r1") flow { answer("r1", "m1", "hi").forEach { emit(it) } } else emptyFlow()
        })
        val session = AgentSession(agent)

        session.run(RunAgentParameters(runId = "r1"))
        val ended = session.run(RunAgentParameters(runId = "r2"))

        assertIs<RunState.Failed>(ended)
    }

    /**
     * Once the agent has ended the run, a client-side throw afterwards -- here the verifier
     * rejecting an event sent after `RUN_ERROR` -- must not replace the agent's own verdict.
     */
    @Test
    fun a_throw_after_run_error_keeps_upstreams_message_and_code() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                emit(RunErrorEvent(message = "upstream said no", code = "SERVER"))
                emit(TextMessageStartEvent(messageId = "m1")) // the verifier throws here
            }
        })
        val session = AgentSession(agent)

        val ended = session.run()

        assertEquals(RunState.Failed("upstream said no", "SERVER"), ended)
    }

    /**
     * `HttpAgent`'s flow stays open after `RUN_FINISHED` until the server closes the connection;
     * a scope cancelled in that window must not turn a finished run into a cancelled one.
     */
    @Test
    fun cancelling_after_run_finished_keeps_it_finished() = runTest {
        val finished = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({
            flow {
                answer("r1", "m1", "done").forEach { emit(it) }
                finished.complete(Unit)
                CompletableDeferred<Unit>().await() // the connection is still open
            }
        })
        val session = AgentSession(agent)

        val job = launch { session.run() }
        finished.await()
        job.cancel()
        job.join()

        assertEquals(RunState.Finished("t", "r1"), session.transcript.value.run)
    }

    /** Nothing of the second run had started, so there is nothing to record. */
    @Test
    fun cancelling_a_run_still_waiting_for_the_mutex_records_nothing() = runTest {
        val release = CompletableDeferred<Unit>()
        val agent = ScriptedAgent({ input ->
            flow {
                emit(RunStartedEvent(threadId = "t", runId = input.runId))
                release.await()
                emit(RunFinishedEvent(threadId = "t", runId = input.runId))
            }
        })
        val session = AgentSession(agent)

        val first = launch { session.run(RunAgentParameters(runId = "r1")) }
        val second = launch { session.run(RunAgentParameters(runId = "r2")) }
        testScheduler.runCurrent()
        second.cancel()
        second.join()

        assertEquals(RunState.Running("t", "r1"), session.transcript.value.run)
        release.complete(Unit)
        first.join()
        assertEquals(RunState.Finished("t", "r1"), session.transcript.value.run)
        assertEquals(listOf("r1"), agent.inputs.map { it.runId })
    }

    /**
     * `HttpAgent` turns a connection that fails *after* `RUN_FINISHED` into a `RUN_ERROR`, and the
     * verifier lets it through. The answer arrived; the run did not then fail.
     */
    @Test
    fun a_run_error_after_run_finished_does_not_undo_the_finish() = runTest {
        val agent = ScriptedAgent({
            flow {
                answer("r1", "m1", "done").forEach { emit(it) }
                emit(RunErrorEvent(message = "connection reset", code = "TRANSPORT_ERROR"))
            }
        })
        val warnings = mutableListOf<String>()
        val session = AgentSession(agent, onWarning = warnings::add)

        val ended = session.run()

        assertEquals(RunState.Finished("t", "r1"), ended)
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("TRANSPORT_ERROR"), warnings.single())
    }

    /**
     * The verifier allows a stream to start a second run after finishing the first, so "the run
     * ended" is a fact about the current run, not the call. Otherwise the first run's finish would
     * hide the second run's failure.
     */
    @Test
    fun a_second_run_in_the_same_stream_is_settled_on_its_own() = runTest {
        val agent = ScriptedAgent({
            flow {
                answer("r1", "m1", "first").forEach { emit(it) }
                emit(RunStartedEvent(threadId = "t", runId = "r2"))
                emit(TextMessageStartEvent(messageId = "m2"))
                throw IllegalStateException("socket closed")
            }
        })
        val session = AgentSession(agent)

        val ended = session.run()

        assertEquals(RunState.Failed("socket closed", AgentSession.CLIENT_ERROR_CODE), ended)
        assertEquals(false, (session.transcript.value.messages[1].parts.single() as TextPart).streaming)
    }

    /** An `Error` is not a failed run; it is a broken process, and it propagates. */
    @Test
    fun an_error_is_not_folded_into_the_transcript() = runTest {
        val agent = ScriptedAgent({
            flow {
                emit(RunStartedEvent(threadId = "t", runId = "r1"))
                throw AssertionError("broken")
            }
        })
        val session = AgentSession(agent)

        assertFailsWith<AssertionError> { session.run() }
    }

    /**
     * A throw that lands *before* the first event does not escape either. `runAgentObservable`
     * does real work eagerly -- it adopts the input's messages and state, and calls the agent's
     * own `run` -- so an agent that fails while building its stream throws from the call that
     * builds it, not from the collection. The transcript is still the report.
     */
    @Test
    fun a_throw_while_the_stream_is_being_built_is_recorded_rather_than_rethrown() = runTest {
        val agent = ScriptedAgent({ error("offline") })
        val session = AgentSession(agent)

        val ended = session.run()

        assertEquals(RunState.Failed("offline", AgentSession.CLIENT_ERROR_CODE), ended)
        assertEquals(RunState.Failed("offline", AgentSession.CLIENT_ERROR_CODE), session.transcript.value.run)
    }
}
